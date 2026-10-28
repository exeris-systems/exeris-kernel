/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.websocket;

import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * The credit a connection's data writer waits on: the bytes handed to the transport and not yet
 * released by it, against the stream's outbound bound.
 *
 * <p>A frame is admitted while the outstanding count is below the bound, so one frame larger than the
 * bound is still admitted when nothing is outstanding. The count is what the transport has not yet
 * let go of, so it is the transport's release of a frame's buffer that frees credit, and a buffer the
 * transport refuses frees it when its owner closes it. A bound of zero or less means no bound.
 *
 * <p>One writer waits at a time; the caller guarantees that. The waiting writer publishes itself and
 * then re-reads the count, while a release decrements the count and then reads the published writer.
 * Each side writes before it reads, so either the writer sees the release or the release sees the
 * writer, and no timed wait is needed.
 *
 * <p><b>Allocation:</b> one small closure per accounted frame, for the release action; none when the
 * connection is unbounded.
 * <p><b>Thread confinement:</b> none — {@link #await()} is called by the one waiting data writer,
 * while releases and {@link #close()} may run on any thread and take no lock.
 * <p><b>Ownership:</b> holds no buffers; it registers an action on each buffer it is handed.
 */
final class CommunityWebSocketEgressCredit {

    private final long streamId;
    private final long boundBytes;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong outstandingBytes = new AtomicLong();
    private final AtomicReference<Thread> parkedWriter = new AtomicReference<>();

    /* default */ CommunityWebSocketEgressCredit(long streamId, long boundBytes) {
        this.streamId = streamId;
        this.boundBytes = boundBytes;
    }

    /**
     * Waits until the transport holds fewer bytes than the bound, or the credit is closed.
     *
     * @return {@code false} when the credit was closed
     * @throws IllegalStateException if the calling thread is interrupted while it waits (its interrupt
     *                               status is restored)
     */
    /* default */ boolean await() {
        if (boundBytes <= 0) {
            return !closed.get();
        }
        Thread current = Thread.currentThread();
        while (true) {
            if (closed.get()) {
                return false;
            }
            if (outstandingBytes.get() < boundBytes) {
                return true;
            }
            parkedWriter.set(current);
            if (closed.get() || outstandingBytes.get() < boundBytes) {
                parkedWriter.set(null);
                continue;
            }
            LockSupport.park(this);
            parkedWriter.set(null);
            if (Thread.interrupted()) {
                current.interrupt();
                throw new IllegalStateException("WebSocket write interrupted on stream " + streamId);
            }
        }
    }

    /**
     * Counts {@code bytes} against the credit and registers their return on {@code frame}'s release.
     *
     * @param frame the buffer about to be handed to the transport
     * @param bytes the frame's length
     */
    /* default */ void account(LoanedBuffer frame, long bytes) {
        if (boundBytes <= 0) {
            return;
        }
        outstandingBytes.addAndGet(bytes);
        boolean registered = false;
        try {
            frame.addCloseAction(() -> release(bytes));
            registered = true;
        } finally {
            if (!registered) {
                outstandingBytes.addAndGet(-bytes);
            }
        }
    }

    /** Close action of a frame's buffer: it takes no lock, because the transport may run it anywhere. */
    private void release(long bytes) {
        outstandingBytes.addAndGet(-bytes);
        wake();
    }

    private void wake() {
        Thread writer = parkedWriter.get();
        if (writer != null) {
            LockSupport.unpark(writer);
        }
    }

    /** Ends the credit: a writer that is waiting returns {@code false}. Idempotent. */
    /* default */ void close() {
        closed.set(true);
        wake();
    }
}
