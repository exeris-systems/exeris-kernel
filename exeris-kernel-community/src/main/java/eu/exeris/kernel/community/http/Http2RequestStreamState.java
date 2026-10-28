/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.core.http.http2.Http2FlowController;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;

import java.lang.foreign.MemorySegment;

/**
 * Community: one HTTP/2 stream's request under assembly — the decoded headers
 * ({@link Http2DecodedRequest}) plus the DATA-frame body accumulated so far, held by
 * {@link Http2SessionContext} between the stream's HEADERS and its end-of-stream DATA frame.
 *
 * <p><b>Allocation:</b> allocates its body buffer lazily, on the first non-empty
 * {@link #appendBody}, and replaces it with a larger one, doubling toward the caller-supplied
 * {@code needed} size, only when the next append would overflow the current capacity.
 * <p><b>Thread confinement:</b> owner thread — confined to the connection's single
 * frame-processing loop in {@link CommunityHttp2SessionProcessor}; a stream's frames are never
 * processed concurrently with another frame on the same connection, even though many streams are
 * multiplexed over it.
 * <p><b>Ownership:</b> owns the body buffer until {@link #detachBody} transfers it to the caller,
 * who must then close it, or {@link #close()} releases it; after either, the instance no longer
 * owns a buffer, even though {@link #detachBody} leaves the field referencing the one it gave away.
 */
// Retained suppressions:
// - TooManyMethods / CyclomaticComplexity: one request under assembly needs its accessors, the body
//   append/detach/close lifecycle and the send-window credit it holds for the response; the nested
//   BufferReservation counts toward the class total.
@SuppressWarnings({
        "PMD.TooManyMethods",
        "PMD.CyclomaticComplexity"
})
/* default */ final class Http2RequestStreamState implements AutoCloseable {
    private static final int INITIAL_BODY_BUFFER_BYTES = 1024;

    private final int streamId;
    private final Http2DecodedRequest request;
    private LoanedBuffer bodyBuffer;
    private boolean bodyDetached;
    private int bodyBytes;
    private long pendingSendWindowIncrement;
    private final Http2FlowController receiveWindow =
            new Http2FlowController(Http2FlowController.DEFAULT_WINDOW_SIZE);

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

    /**
     * Returns the send-window credit the peer granted this stream while its request was still
     * arriving, which the response stream starts with.
     */
    /* default */ long pendingSendWindowIncrement() {
        return pendingSendWindowIncrement;
    }

    /**
     * Accumulates a WINDOW_UPDATE that arrived before the response stream exists.
     *
     * @param increment            the window increment
     * @param peerInitialWindowSize the peer's current initial window size, which the response stream
     *                             will start from
     * @return {@code false} when the credit would push the stream's window past the maximum, so the
     *         caller resets the stream with FLOW_CONTROL_ERROR; {@code true} after adding it
     */
    /* default */ boolean addSendWindowIncrement(int increment, int peerInitialWindowSize) {
        if (pendingSendWindowIncrement + increment + peerInitialWindowSize
                > Http2FlowController.MAX_WINDOW_SIZE) {
            return false;
        }
        pendingSendWindowIncrement += increment;
        return true;
    }

    /**
     * Charges {@code bytes} of the peer's DATA frame, padding included, to this stream's receive
     * window.
     *
     * @return {@code false} when the frame exceeds the window the server has granted, so the caller
     *         resets the stream with FLOW_CONTROL_ERROR
     */
    /* default */ boolean consumeReceiveWindow(int bytes) {
        return receiveWindow.consume(bytes);
    }

    /**
     * Raises the receive window back to {@code target} once it has fallen to half of it or less, so a
     * peer is credited in batches rather than once per frame.
     *
     * @param target the window the stream may be offered
     * @return the increment to advertise, or {@code 0} when no credit is due
     */
    /* default */ int replenishReceiveWindow(long target) {
        long window = receiveWindow.windowSize();
        if (target <= window || window > target / 2) {
            return 0;
        }
        int increment = (int) (target - window);
        receiveWindow.increment(increment);
        return increment;
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
