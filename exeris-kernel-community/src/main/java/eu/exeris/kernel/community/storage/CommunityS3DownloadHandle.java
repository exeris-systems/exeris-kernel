/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.storage;

import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.storage.blob.BlobDownloadHandle;
import eu.exeris.kernel.spi.storage.blob.BlobMetadata;

import java.lang.foreign.MemorySegment;

/**
 * A completed S3 response, handed out one caller-sized chunk at a time.
 *
 * <p>The bytes have already arrived when this exists: the Community HTTP client engine buffers a whole
 * response before returning it. So this is a cursor over an off-heap buffer, not a live stream — the
 * chunked {@code read} shape is kept because it is the SPI's, and because a driver that does stream
 * (Enterprise) must be substitutable for this one without the caller noticing.
 *
 * <p>Owns the response buffer and releases it on {@link #close()}. That buffer came from the HTTP
 * engine's allocator, so failing to close it leaks a pooled segment rather than heap.
 *
 * @since 0.11.0
 */
final class CommunityS3DownloadHandle implements BlobDownloadHandle {

    private final BlobMetadata metadata;
    private final LoanedBuffer body;
    private final long start;
    private final long available;

    private long position;
    private boolean closed;

    /**
     * @param metadata  metadata of the whole object, not of the slice
     * @param body      the response buffer, or {@code null} when there is nothing to read
     * @param start     offset within {@code body} where the readable bytes begin — non-zero only when a
     *                  store answered a ranged request with the whole object
     * @param available number of readable bytes from {@code start}
     */
    /* default */ CommunityS3DownloadHandle(BlobMetadata metadata, LoanedBuffer body, long start,
                                            long available) {
        this.metadata = metadata;
        this.body = body;
        this.start = start;
        this.available = available;
    }

    @Override
    public BlobMetadata metadata() {
        return metadata;
    }

    @Override
    public int read(MemorySegment target, int maxBytes) {
        if (maxBytes <= 0) {
            return 0;
        }
        if (closed) {
            throw new IllegalStateException("Download handle is closed");
        }
        if (maxBytes > target.byteSize()) {
            throw new IllegalArgumentException(
                    "maxBytes must not exceed the segment size, got: " + maxBytes);
        }
        // A null buffer is not a special case: a response with no body has nothing left to give,
        // which is exactly what an exhausted one reports.
        long remaining = body == null ? 0L : available - position;
        if (remaining <= 0) {
            return -1;
        }
        int chunk = (int) Math.min(remaining, maxBytes);
        MemorySegment.copy(body.segment(), start + position, target, 0L, chunk);
        position += chunk;
        return chunk;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (body != null) {
            body.close();
        }
    }
}
