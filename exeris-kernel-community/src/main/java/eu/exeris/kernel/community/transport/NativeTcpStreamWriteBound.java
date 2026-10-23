/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.core.transport.jfr.TransportWriteParkEvent;
import eu.exeris.kernel.spi.transport.TransportConfig;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * The outbound byte accounting of one {@link NativeTcpStream} and the single parked writer it wakes.
 *
 * <p>For a bound {@code B > 0} a writer enqueues chunks of at most {@code C = max(1, B / 2)} and does
 * so only while the queued bytes sit at or below the resume mark {@code L = B - C}. What a writer
 * queues therefore never exceeds {@code B}, and a parked writer wakes once half the bound has drained
 * rather than on every byte. Bytes handed over through {@code queueWrite} are counted here and never
 * wait on the bound. {@link TransportConfig#UNBOUNDED_QUEUED_WRITE_BYTES} switches the parking off.
 *
 * <p><b>Allocation:</b> none per call; one instance per stream.
 * <p><b>Thread confinement:</b> any thread; every field is atomic. At most one thread is parked as the
 * writer at a time.
 * <p><b>Ownership:</b> holds no buffer; the stream owns the pending writes whose lengths are counted.
 */
final class NativeTcpStreamWriteBound {

    private final long streamId;
    private final int maxQueuedWriteBytes;
    private final boolean bounded;
    private final int chunkBytes;
    private final int resumeBytes;
    private final Runnable ensureOpen;
    // Raised before an entry is offered and lowered only where an entry leaves the queue, so a drain
    // can never subtract before the matching add and the crossing test in release() stays exact.
    private final AtomicLong queuedBytes = new AtomicLong(0);
    private final AtomicReference<Thread> waiter = new AtomicReference<>();
    private final AtomicBoolean parked = new AtomicBoolean(false);

    /**
     * Creates the accounting for one stream.
     *
     * @param streamId            the owning stream's identifier, for diagnostics
     * @param maxQueuedWriteBytes a positive bound, or {@link TransportConfig#UNBOUNDED_QUEUED_WRITE_BYTES};
     *                            the caller has already refused any other value
     * @param ensureOpen          throws the stream's closed-stream signal once the stream is ending or
     *                            ended, and returns otherwise
     */
    /* default */ NativeTcpStreamWriteBound(long streamId, int maxQueuedWriteBytes, Runnable ensureOpen) {
        this.streamId = streamId;
        this.maxQueuedWriteBytes = maxQueuedWriteBytes;
        this.bounded = maxQueuedWriteBytes > 0;
        this.chunkBytes = bounded ? Math.max(1, maxQueuedWriteBytes / 2) : Integer.MAX_VALUE;
        this.resumeBytes = bounded ? maxQueuedWriteBytes - chunkBytes : Integer.MAX_VALUE;
        this.ensureOpen = Objects.requireNonNull(ensureOpen, "ensureOpen must not be null");
    }

    /* default */ int maxQueuedWriteBytes() {
        return maxQueuedWriteBytes;
    }

    /** The most a single enqueue carries; unbounded when there is no bound. */
    /* default */ int chunkBytes() {
        return chunkBytes;
    }

    /* default */ long queuedBytes() {
        return queuedBytes.get();
    }

    /** Counts {@code length} bytes as queued, or takes them back when negative; call before the offer. */
    /* default */ void add(long length) {
        queuedBytes.addAndGet(length);
    }

    /**
     * Counts {@code length} bytes as no longer queued and wakes the parked writer when that is the
     * step that crosses the resume mark. Any path from above the mark to at or below it contains such
     * a step, so no wake is lost to the increments in between.
     *
     * @param length bytes of the entry that left the queue
     */
    /* default */ void release(long length) {
        long after = queuedBytes.addAndGet(-length);
        if (bounded && after <= resumeBytes && after + length > resumeBytes) {
            signal();
        }
    }

    /** Wakes the parked writer, if any. */
    /* default */ void signal() {
        if (!parked.get()) {
            return;
        }
        Thread writer = waiter.get();
        if (writer != null) {
            LockSupport.unpark(writer);
        }
    }

    /** The thread registered as the writer, or {@code null}; for terminal wakes. */
    /* default */ Thread waiter() {
        return waiter.get();
    }

    /**
     * Parks the caller until the queue has drained to the resume mark or the stream ends.
     *
     * <p>No monitor and no outbound slot is held across the park. The waiter is registered before the
     * terminal check, and the parked flag is raised before the byte check, which pairs with
     * {@link #release}: the crossing decrement either sees the flag and unparks the waiter, or happens
     * before the waiter reads the count. A FIN is a half-close and does not end the wait; a TLS
     * {@code close_notify} ends writes through the next drain, which reaches the waiter as a terminal
     * wake.
     *
     * @throws IllegalStateException if another thread is already parked as this stream's writer, or the
     *                               calling thread is interrupted (its interrupt status is restored)
     */
    /* default */ void awaitCapacity() {
        if (queuedBytes.get() <= resumeBytes || NativeTcpReactor.isReactorThread()) {
            return;
        }
        Thread current = Thread.currentThread();
        claimWriter(current);
        parked.set(true);
        try {
            parkUntilResumable(current);
        } finally {
            parked.set(false);
            waiter.compareAndSet(current, null);
        }
    }

    private void claimWriter(Thread current) {
        Thread prior = waiter.compareAndExchange(null, current);
        if (prior != null && !prior.equals(current)) {
            throw new IllegalStateException("Concurrent blocking writers on stream " + streamId);
        }
    }

    private void parkUntilResumable(Thread current) {
        boolean recorded = false;
        while (true) {
            ensureOpen.run();
            long queued = queuedBytes.get();
            if (queued <= resumeBytes) {
                return;
            }
            if (!recorded) {
                TransportWriteParkEvent.emit(streamId, queued, maxQueuedWriteBytes);
                recorded = true;
            }
            LockSupport.park(this);
            if (Thread.interrupted()) {
                current.interrupt();
                throw new IllegalStateException("Write interrupted on stream " + streamId);
            }
        }
    }
}
