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
import eu.exeris.kernel.spi.storage.blob.BlobDownloadHandle;
import eu.exeris.kernel.spi.storage.blob.BlobMetadata;
import eu.exeris.kernel.spi.storage.blob.BlobRange;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Filesystem {@link BlobDownloadHandle}: reads into the caller's segment, optionally limited to a range.
 *
 * @since 0.11.0
 */
final class CommunityFilesystemBlobDownloadHandle implements BlobDownloadHandle {

    private static final String PROVIDER_NAME = "ExerisCommunity/FilesystemBlob";

    private final BlobMetadata metadata;
    private final FileChannel channel;

    private long position;
    private long remaining;
    private boolean closed;

    /* default */ CommunityFilesystemBlobDownloadHandle(Path target, BlobMetadata metadata,
                                                        BlobRange range) {
        this.metadata = metadata;
        try {
            this.channel = FileChannel.open(target, StandardOpenOption.READ);
        } catch (IOException e) {
            throw BlobStorageException.transferFailed(PROVIDER_NAME, metadata.ref().container(), e);
        }
        if (range == null) {
            this.position = 0;
            this.remaining = metadata.sizeBytes();
        } else {
            // A range starting past the end yields an immediate EOF, and one extending past the end is
            // truncated — a caller walking a large object with a fixed window must not need the size to
            // avoid an exception on its final window (ADR-056, BlobStore#openDownload).
            this.position = Math.min(range.offset(), metadata.sizeBytes());
            this.remaining = Math.min(range.length(), metadata.sizeBytes() - position);
        }
    }

    @Override
    public BlobMetadata metadata() {
        return metadata;
    }

    @Override
    public int read(MemorySegment target, int maxBytes) {
        if (maxBytes <= 0) {
            // Non-positive is a no-op that takes precedence over closed state, matching
            // TransportStream.read so a drained read loop needs no special case.
            return 0;
        }
        requireReadable(target, maxBytes);
        if (remaining <= 0) {
            return -1;
        }
        return transfer(target, (int) Math.min(maxBytes, remaining));
    }

    private void requireReadable(MemorySegment target, int maxBytes) {
        if (closed) {
            throw new IllegalStateException("download handle is closed");
        }
        if (maxBytes > target.byteSize()) {
            throw new IllegalArgumentException("maxBytes exceeds segment size");
        }
    }

    private int transfer(MemorySegment target, int toRead) {
        // A view over the caller's off-heap memory, not a copy.
        ByteBuffer view = target.asSlice(0, toRead).asByteBuffer();
        int read;
        try {
            read = channel.read(view, position);
        } catch (IOException e) {
            throw BlobStorageException.transferFailed(PROVIDER_NAME, metadata.ref().container(), e);
        }
        if (read <= 0) {
            return -1;
        }
        position += read;
        remaining -= read;
        return read;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            channel.close();
        } catch (IOException e) {
            throw BlobStorageException.transferFailed(PROVIDER_NAME, metadata.ref().container(), e);
        }
    }
}
