/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;

import java.lang.foreign.MemorySegment;

@SuppressWarnings({
    "PMD.CommentDefaultAccessModifier",
    "PMD.NullAssignment",
    "PMD.CloseResource"
})
final class Http2RequestStreamState implements AutoCloseable {
    private static final int INITIAL_BODY_BUFFER_BYTES = 1024;

    private final int streamId;
    private final Http2DecodedRequest request;
    private LoanedBuffer bodyBuffer;
    private int bodyBytes;

    Http2RequestStreamState(int streamId, Http2DecodedRequest request) {
        this.streamId = streamId;
        this.request = request;
        this.bodyBuffer = null;
        this.bodyBytes = 0;
    }

    int streamId() {
        return streamId;
    }

    Http2DecodedRequest request() {
        return request;
    }

    int bodyBytes() {
        return bodyBytes;
    }

    void appendBody(MemoryAllocator allocator,
                    MemorySegment source,
                    long sourceOffset,
                    int length) {
        if (length <= 0) {
            return;
        }
        long needed = (long) bodyBytes + length;
        ensureCapacity(allocator, needed);
        MemorySegment.copy(source, sourceOffset, bodyBuffer.segment(), bodyBytes, length);
        bodyBytes += length;
        bodyBuffer.setSize(bodyBytes);
    }

    LoanedBuffer detachBody() {
        LoanedBuffer detached = bodyBuffer;
        bodyBuffer = null;
        bodyBytes = 0;
        return detached;
    }

    private void ensureCapacity(MemoryAllocator allocator, long needed) {
        if (bodyBuffer != null && bodyBuffer.capacity() >= needed) {
            return;
        }
        if (needed > Integer.MAX_VALUE) {
            throw new IllegalStateException("HTTP/2 request body exceeds allocator limits");
        }

        int nextCapacity = bodyBuffer == null
                ? INITIAL_BODY_BUFFER_BYTES
                : (int) bodyBuffer.capacity();
        while (nextCapacity < needed) {
            nextCapacity = Math.max(nextCapacity * 2, (int) needed);
        }

        LoanedBuffer nextBuffer = allocator.allocateNetwork(nextCapacity);
        if (bodyBuffer != null && bodyBytes > 0) {
            MemorySegment.copy(bodyBuffer.segment(), 0, nextBuffer.segment(), 0, bodyBytes);
            nextBuffer.setSize(bodyBytes);
            bodyBuffer.close();
        }
        bodyBuffer = nextBuffer;
    }

    @Override
    public void close() {
        if (bodyBuffer != null) {
            bodyBuffer.close();
            bodyBuffer = null;
        }
        bodyBytes = 0;
    }
}
