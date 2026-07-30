/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.storage.blob;

import eu.exeris.kernel.spi.exceptions.storage.BlobStorageException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.lang.foreign.MemorySegment;

/**
 * SPI: An in-progress upload of exactly one object (ADR-056 §3).
 *
 * <h2>Ownership</h2>
 * <p>The caller owns every buffer it writes, for the whole upload. {@link #write} copies out of the
 * supplied segment before returning and MUST NOT retain a reference to it, so one pooled
 * {@link LoanedBuffer} can drive an entire transfer — the alternative, transferring ownership per chunk,
 * would force a fresh allocation per chunk and put an ownership question on the hot path.
 *
 * <h2>Visibility</h2>
 * <p>Written bytes become visible as a stored object only on {@link #commit()}. Closing without
 * committing aborts: no partially written object is left visible, so an interrupted upload can never be
 * mistaken for a complete one.
 *
 * <pre>{@code
 * try (BlobUploadHandle upload = store.beginUpload(ref, size, "image/png");
 *      LoanedBuffer chunk = allocator.allocateInfrastructure(64 * 1024)) {
 *     while (source.hasNext()) {
 *         int n = source.fill(chunk.segment());
 *         upload.write(chunk.segment(), n);
 *     }
 *     BlobMetadata stored = upload.commit();
 * } // close() after commit() is a no-op; close() without commit() aborts
 * }</pre>
 *
 * <p>Handles are not thread-safe: one upload is driven by one thread.
 *
 * @since 0.11.0
 */
public interface BlobUploadHandle extends AutoCloseable {

    /**
     * Writes {@code length} bytes from the start of {@code source} to this upload.
     *
     * <p>The caller retains ownership of the segment, and the implementation MUST NOT hold a reference
     * to it after this call returns.
     *
     * @param source off-heap segment holding the bytes (typically {@link LoanedBuffer#segment()})
     * @param length number of bytes to write; must be {@code >= 0} and {@code <=} the segment size.
     *               A value of {@code 0} is a no-op, so a drained read loop needs no special case
     * @throws BlobStorageException     on I/O failure, or if the total written would exceed the content
     *                                  length declared at {@link BlobStore#beginUpload}
     * @throws IllegalStateException    if this upload was already committed or closed
     * @throws IllegalArgumentException if {@code length} is negative or exceeds the segment size
     */
    void write(MemorySegment source, int length);

    /**
     * Completes the upload and makes the object visible.
     *
     * @return metadata for the stored object; never {@code null}
     * @throws BlobStorageException  on I/O failure, or if the bytes written do not match the content
     *                               length declared at {@link BlobStore#beginUpload}
     * @throws IllegalStateException if this upload was already committed or closed
     */
    BlobMetadata commit();

    /**
     * Releases this upload's resources, aborting it if {@link #commit()} has not been called.
     *
     * <p>Idempotent, and never throws for an already-closed handle — so try-with-resources is safe after
     * either outcome.
     */
    @Override
    void close();
}
