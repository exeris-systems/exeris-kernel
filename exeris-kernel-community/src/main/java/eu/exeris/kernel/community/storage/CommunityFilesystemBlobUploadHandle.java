/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.storage;

import eu.exeris.kernel.spi.exceptions.storage.BlobStorageException;
import eu.exeris.kernel.spi.storage.blob.BlobMetadata;
import eu.exeris.kernel.spi.storage.blob.BlobRef;
import eu.exeris.kernel.spi.storage.blob.BlobUploadHandle;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Filesystem {@link BlobUploadHandle}: writes to a staging file, moves it into place on commit.
 *
 * <p>Nothing is visible at the target path until {@link #commit()} succeeds, so an aborted or crashed
 * upload cannot be mistaken for a complete object.
 *
 * @since 0.11.0
 */
final class CommunityFilesystemBlobUploadHandle implements BlobUploadHandle {

    private static final CommunityBlobFailures FAILURES =
            CommunityBlobFailures.forProvider(CommunityBlobFailures.FILESYSTEM_PROVIDER);

    private final Path staging;
    private final Path target;
    private final BlobRef ref;
    private final long declaredLength;
    private final String contentType;
    private final FileChannel channel;

    private long written;
    private boolean committed;
    private boolean closed;

    /* default */ CommunityFilesystemBlobUploadHandle(Path staging, Path target, BlobRef ref,
                                                      long declaredLength, String contentType) {
        this.staging = staging;
        this.target = target;
        this.ref = ref;
        this.declaredLength = declaredLength;
        this.contentType = contentType;
        try {
            this.channel = FileChannel.open(staging,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw FAILURES.transferFailed(
                    CommunityBlobFailures.OP_UPLOAD, ref.container(), e);
        }
    }

    @Override
    public void write(MemorySegment source, int length) {
        requireOpen();
        requireWritable(source, length);
        // A view over the caller's off-heap memory, not a copy — the bytes go straight from the
        // caller's segment to the channel. The caller keeps ownership throughout (ADR-056 §3).
        // A zero-length write needs no special case: the drain loop simply does not run.
        drain(source.asSlice(0, length).asByteBuffer());
        written += length;
    }

    private void requireWritable(MemorySegment source, int length) {
        if (length < 0) {
            throw new IllegalArgumentException("length must not be negative");
        }
        if (length > source.byteSize()) {
            throw new IllegalArgumentException("length exceeds segment size");
        }
        if (written + length > declaredLength) {
            throw BlobStorageException.uploadLengthMismatch(
                    FAILURES.providerName(), declaredLength, written + length);
        }
    }

    private void drain(ByteBuffer view) {
        try {
            while (view.hasRemaining()) {
                channel.write(view);
            }
        } catch (IOException e) {
            throw FAILURES.transferFailed(
                    CommunityBlobFailures.OP_UPLOAD, ref.container(), e);
        }
    }

    @Override
    public BlobMetadata commit() {
        requireOpen();
        if (written != declaredLength) {
            throw BlobStorageException.uploadLengthMismatch(
                    FAILURES.providerName(), declaredLength, written);
        }
        try {
            channel.force(true);
            channel.close();
            CommunityFilesystemBlobLayout.writeContentType(target, contentType);
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw FAILURES.transferFailed(
                    CommunityBlobFailures.OP_UPLOAD, ref.container(), e);
        }
        committed = true;
        closed = true;
        return new BlobMetadata(ref, written, contentType);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // One try for both steps: this is the abort path, and if closing the channel fails there is
        // nothing left worth salvaging from the staging file either. Throwing here is safe inside
        // try-with-resources — an in-flight exception suppresses this one rather than losing it.
        try {
            channel.close();
            Files.deleteIfExists(staging);
        } catch (IOException e) {
            throw FAILURES.transferFailed(
                    CommunityBlobFailures.OP_UPLOAD, ref.container(), e);
        }
    }

    private void requireOpen() {
        if (closed) {
            // commit() sets both flags, so one check covers committed and aborted alike.
            throw new IllegalStateException(
                    committed ? "upload already committed" : "upload already closed");
        }
    }
}
