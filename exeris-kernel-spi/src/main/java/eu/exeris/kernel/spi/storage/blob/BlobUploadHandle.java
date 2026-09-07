/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.storage.blob;

import eu.exeris.kernel.spi.exceptions.storage.BlobStorageException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.lang.foreign.MemorySegment;

/**
 * SPI: An in-progress upload of exactly one object (ADR-056 §3).
 *
 * <p>The caller owns every buffer it writes, for the whole upload. {@link #write} copies out of the
 * supplied segment before returning and MUST NOT retain a reference to it, so one pooled
 * {@link LoanedBuffer} can drive an entire transfer — the alternative, transferring ownership per chunk,
 * would force a fresh allocation per chunk and put an ownership question on the hot path.
 *
 * <p>Written bytes become visible as a stored object only on {@link #commit()}. Closing without
 * committing aborts: no partially written object is left visible, so an interrupted upload can never be
 * mistaken for a complete one.
 *
 * {@snippet lang="java" :
 * try (BlobUploadHandle upload = store.beginUpload(ref, size, "image/png");
 *      LoanedBuffer chunk = allocator.allocateInfrastructure(64 * 1024)) {
 *     while (source.hasNext()) {
 *         int n = source.fill(chunk.segment());
 *         upload.write(chunk.segment(), n);
 *     }
 *     BlobMetadata stored = upload.commit();
 * } // close() after commit() is a no-op; close() without commit() aborts
 * }
 *
 * <p><b>Allocation:</b> not specified at the SPI level — {@link #write} guarantees no retained
 * reference to the caller's segment, not the absence of internal copying; a driver's own buffering
 * is its concern.
 * <p><b>Thread confinement:</b> owner thread — one upload is driven by one thread; handles are not
 * thread-safe.
 * <p><b>Ownership:</b> the caller owns every buffer it writes, for the whole upload; this handle
 * never retains a reference to one past the call that supplied it.
 *
 * @since 0.11
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
     * @throws BlobStorageException     on I/O failure ({@code EX-BLOB-8003}), or if the total written
     *                                  would exceed the content length declared at
     *                                  {@link BlobStore#beginUpload} ({@code EX-BLOB-8004})
     * @throws IllegalStateException    if this upload was already committed or closed
     * @throws IllegalArgumentException if {@code length} is negative or exceeds the segment size
     */
    void write(MemorySegment source, int length);

    /**
     * Completes the upload and makes the object visible.
     *
     * @return metadata for the stored object; never {@code null}
     * @throws BlobStorageException  on I/O failure ({@code EX-BLOB-8003}), or if the bytes written do
     *                               not match the content length declared at
     *                               {@link BlobStore#beginUpload} ({@code EX-BLOB-8004})
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
