/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;

import java.lang.foreign.MemorySegment;

/* default */ final class Http2RequestStreamState implements AutoCloseable {
    private static final int INITIAL_BODY_BUFFER_BYTES = 1024;

    private final int streamId;
    private final Http2DecodedRequest request;
    private LoanedBuffer bodyBuffer;
    private boolean bodyDetached;
    private int bodyBytes;

    /* default */ Http2RequestStreamState(int streamId, Http2DecodedRequest request) {
        this.streamId = streamId;
        this.request = request;
    }

    /* default */ int streamId() {
        return streamId;
    }

    /* default */ Http2DecodedRequest request() {
        return request;
    }

    /* default */ int bodyBytes() {
        return bodyBytes;
    }

    /* default */ void appendBody(MemoryAllocator allocator,
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

    /* default */ LoanedBuffer detachBody() {
        if (bodyBuffer == null || bodyDetached) {
            return null;
        }
        bodyDetached = true;
        bodyBytes = 0;
        return bodyBuffer;
    }

    private void ensureCapacity(MemoryAllocator allocator, long needed) {
        if (!bodyDetached && bodyBuffer != null && bodyBuffer.capacity() >= needed) {
            return;
        }
        if (needed > Integer.MAX_VALUE) {
            throw new IllegalStateException("HTTP/2 request body exceeds allocator limits");
        }

        int nextCapacity = bodyDetached || bodyBuffer == null
                ? INITIAL_BODY_BUFFER_BYTES
                : (int) bodyBuffer.capacity();
        while (nextCapacity < (int) needed) {
            nextCapacity = Math.max(nextCapacity * 2, (int) needed);
        }

        try (BufferReservation reservation = new BufferReservation(
                allocator.allocateNetwork(nextCapacity))) {
            copyExistingBodyTo(reservation.buffer());
            replaceBodyBuffer(reservation.take());
        }
    }

    private void copyExistingBodyTo(LoanedBuffer targetBuffer) {
        if (!bodyDetached && bodyBuffer != null && bodyBytes > 0) {
            MemorySegment.copy(bodyBuffer.segment(), 0, targetBuffer.segment(), 0, bodyBytes);
            targetBuffer.setSize(bodyBytes);
        }
    }

    @Override
    public void close() {
        closeOwnedBodyBuffer();
        bodyDetached = true;
        bodyBytes = 0;
    }

    private void replaceBodyBuffer(LoanedBuffer nextBuffer) {
        closeOwnedBodyBuffer();
        bodyBuffer = nextBuffer;
        bodyDetached = false;
    }

    private void closeOwnedBodyBuffer() {
        if (!bodyDetached && bodyBuffer != null) {
            bodyBuffer.close();
        }
    }

    private static final class BufferReservation implements AutoCloseable {
        private final LoanedBuffer buffer;
        private boolean taken;

        private BufferReservation(LoanedBuffer buffer) {
            this.buffer = buffer;
        }

        private LoanedBuffer buffer() {
            return buffer;
        }

        private LoanedBuffer take() {
            taken = true;
            return buffer;
        }

        @Override
        public void close() {
            if (!taken) {
                buffer.close();
            }
        }
    }
}
