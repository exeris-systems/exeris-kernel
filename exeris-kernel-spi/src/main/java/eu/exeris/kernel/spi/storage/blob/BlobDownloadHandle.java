/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.storage.blob;

import eu.exeris.kernel.spi.exceptions.storage.BlobStorageException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.lang.foreign.MemorySegment;

/**
 * SPI: An open read over one object, or over a byte range of one (ADR-056 §3).
 *
 * <p>The caller supplies the destination and owns it throughout — this handle allocates nothing on the
 * caller's behalf and hands nothing back to be closed. It is the mirror of {@link BlobUploadHandle},
 * and it is deliberately the same shape as
 * {@link eu.exeris.kernel.spi.transport.TransportStream#read(MemorySegment, int)}: a store that produced
 * buffers would decide the caller's chunk size and pool for it.
 *
 * {@snippet lang="java" :
 * try (BlobDownloadHandle download = store.openDownload(ref);
 *      LoanedBuffer chunk = allocator.allocateInfrastructure(64 * 1024)) {
 *     int n;
 *     while ((n = download.read(chunk.segment(), (int) chunk.segment().byteSize())) > 0) {
 *         sink.accept(chunk.segment(), n);
 *     }
 * }
 * }
 *
 * <p><b>Allocation:</b> zero-alloc on the SPI surface — this handle allocates nothing on the caller's
 * behalf; {@link #read} writes into the caller's own segment.
 * <p><b>Thread confinement:</b> owner thread — one download is driven by one thread; handles are not
 * thread-safe.
 * <p><b>Ownership:</b> the caller supplies and owns the destination segment throughout; this handle
 * hands nothing back that needs closing.
 *
 * @since 0.11
 */
public interface BlobDownloadHandle extends AutoCloseable {

    /**
     * Returns metadata for the object being read.
     *
     * <p>For a ranged download, {@link BlobMetadata#sizeBytes()} is the size of the <em>whole</em>
     * object, not of the requested range — a caller computing "how much of this have I fetched" needs
     * the total, and the range it asked for is already known to it.
     *
     * @return metadata for the underlying object; never {@code null}
     */
    BlobMetadata metadata();

    /**
     * Reads up to {@code maxBytes} bytes into {@code target}.
     *
     * <p>Follows {@link eu.exeris.kernel.spi.transport.TransportStream#read} exactly, including its
     * non-positive no-op, so a caller draining a budget needs no special case:
     *
     * @param target   off-heap segment to read into (typically {@link LoanedBuffer#segment()})
     * @param maxBytes maximum bytes to read; must be {@code <=} the segment size. A value {@code <= 0}
     *                 is a no-op returning {@code 0}, and takes precedence over closed state
     * @return the number of bytes read, {@code 0} if {@code maxBytes <= 0}, or {@code -1} at end of the
     *         object or range
     * @throws BlobStorageException     on I/O failure ({@code EX-BLOB-8003})
     * @throws IllegalStateException    if this handle is closed and {@code maxBytes > 0}
     * @throws IllegalArgumentException if {@code maxBytes} exceeds the segment size
     */
    int read(MemorySegment target, int maxBytes);

    /**
     * Releases this download's resources. Idempotent, and never throws for an already-closed handle.
     */
    @Override
    void close();
}
