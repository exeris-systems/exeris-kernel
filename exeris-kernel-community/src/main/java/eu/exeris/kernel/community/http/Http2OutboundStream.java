/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.core.http.http2.Http2FlowController;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Community: the server-to-client half of one HTTP/2 stream — its send window, how many of its bytes
 * the transport still holds, and the thread parked waiting for credit, tracked by
 * {@link Http2ConnectionWriter} for as long as the response is being written.
 *
 * <p>Every mutable field other than {@link #outstandingBytes} and {@link #parkedSender} is guarded by
 * the owning writer's lock and is read or written only while it is held.
 *
 * <p><b>Allocation:</b> one instance and one {@link Http2FlowController} per response stream whose
 * body does not fit the windows at the moment its head is written; none for a response that fits.
 * <p><b>Thread confinement:</b> none for {@link #outstandingBytes}, {@link #parkedSender} and
 * {@link #releaseOutstanding}; every other field belongs to the writer's lock. {@link #bodyOffset} is
 * written by the frame loop before the continuation task is forked and read only by that task.
 * <p><b>Ownership:</b> holds no buffers; the writer's table owns the instance until
 * {@link Http2ConnectionWriter#release} drops it.
 */
final class Http2OutboundStream {

    /** Where a response stream is in its life; every transition happens under the writer's lock. */
    /* default */ enum State {
        /** Frames may still be written. */
        OPEN,
        /** The last frame, carrying END_STREAM, has been written. */
        END_STREAM_SENT,
        /** This side sent RST_STREAM. */
        RESET_LOCALLY,
        /** The peer sent RST_STREAM; nothing is written in reply. */
        RESET_BY_PEER,
        /** The connection ended before the stream did. */
        CONNECTION_CLOSED
    }

    /** The HTTP/2 stream id. */
    /* default */ final int streamId;

    /** The stream's send window; guarded by the writer's lock. */
    /* default */ final Http2FlowController sendWindow;

    /** Bytes handed to the transport and not yet released by it. */
    /* default */ final AtomicLong outstandingBytes = new AtomicLong();

    /** The thread parked waiting for credit, or {@code null}; published under the writer's lock. */
    /* default */ volatile Thread parkedSender;

    /** Guarded by the writer's lock. */
    /* default */ State state = State.OPEN;

    /** Whether the response head has been written; guarded by the writer's lock. */
    /* default */ boolean headWritten;

    /** Index in the writer's table, {@code -1} once released; guarded by the writer's lock. */
    /* default */ int slot = -1;

    /** Offset of the first body byte a continuation has still to send. */
    /* default */ long bodyOffset;

    /**
     * Creates a response stream in {@link State#OPEN}.
     *
     * @param streamId      the HTTP/2 stream id
     * @param initialWindow the stream's send window in bytes, not negative
     */
    /* default */ Http2OutboundStream(int streamId, int initialWindow) {
        this.streamId = streamId;
        this.sendWindow = new Http2FlowController(initialWindow);
    }

    /**
     * Close action of a buffer handed to the transport: returns its bytes and wakes the sender. It
     * never takes the writer's lock, because the transport may run it on a thread that holds the
     * transport's own outbound slot.
     *
     * @param bytes the number of bytes the transport has finished with
     */
    /* default */ void releaseOutstanding(long bytes) {
        outstandingBytes.addAndGet(-bytes);
        Thread sender = parkedSender;
        if (sender != null) {
            LockSupport.unpark(sender);
        }
    }
}
