/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.telemetry;

import jdk.jfr.Event;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Commits {@link jdk.jfr.Event}s on a dedicated <em>platform</em> thread, off the request
 * virtual thread that produced them.
 *
 * <h2>Why this exists (VT-JFR safety)</h2>
 * <p>On JDK 26 GA (build 26+35) committing a custom JFR event from a virtual thread that has
 * parked and remounted on a different carrier — while a {@link jdk.jfr.Recording} is arming and
 * the JFR epoch rotates ({@code JFRSafepointClear}/{@code RedefineClasses}) — flushes a stale,
 * carrier-bound {@code JfrBuffer} and crashes the JVM in {@code JfrStorage::flush_regular_buffer}.
 * Single-phase commit (construct + commit with no blocking op between) narrows but does not close
 * this window: the staleness is established by the earlier park/remount, not by a begin/commit
 * straddle. The only robust fix is to perform the actual {@link Event#commit()} on a thread that
 * never unmounts — a platform thread, whose {@code EventWriter} → {@code JfrBuffer} binding is
 * always carrier-stable.
 *
 * <h2>Handoff discipline</h2>
 * <p>Producers <em>construct</em> the typed event and set its fields on their own (possibly virtual)
 * thread — this is pure heap manipulation and never touches the carrier-bound {@code EventWriter}.
 * They then hand the <em>event object itself</em> to {@link #offer(Event)} (the event is the queue
 * element — no wrapper, no lambda, no boxing). Only the platform drainer calls {@link Event#commit()}.
 * Producers MUST gate on event-type enablement before constructing/offering, so that when JFR is
 * inactive nothing is allocated and the committer is never touched.
 *
 * <h2>Drop policy</h2>
 * <p>Bounded ring with drop-newest on overflow (mirrors {@link AsyncTelemetrySink}); each drop
 * increments {@link #droppedCount()} and emits a {@link JfrCommitDropEvent} so operators can detect
 * runaway emission rates.
 *
 * @since 0.7
 * @see AsyncTelemetrySink
 */
public final class JfrEventCommitter implements AutoCloseable {

    /** Default ring capacity — parity with {@link AsyncTelemetrySink#DEFAULT_CAPACITY}. */
    public static final int DEFAULT_CAPACITY = 4_096;

    /** Default drain timeout on {@link #close()}. */
    public static final Duration DEFAULT_DRAIN_TIMEOUT = Duration.ofSeconds(2L);

    private static final String THREAD_NAME = "exeris/jfr-committer";
    private static final long POLL_TIMEOUT_MS = 50L;

    private final BlockingQueue<Event> ring;
    private final int capacity;
    private final Duration drainTimeout;
    private final Thread consumer;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final CountDownLatch consumerStopped = new CountDownLatch(1);
    private final LongAdder droppedCount = new LongAdder();

    private JfrEventCommitter(int capacity, Duration drainTimeout) {
        this.ring = new ArrayBlockingQueue<>(capacity);
        this.capacity = capacity;
        this.drainTimeout = drainTimeout;
        // MUST be a platform thread: a virtual-thread consumer would re-introduce the very
        // carrier-bound-buffer hazard this class exists to avoid.
        this.consumer = Thread.ofPlatform().daemon(true).name(THREAD_NAME).unstarted(this::drain);
    }

    /** Creates and starts a committer with default capacity and drain timeout. */
    public static JfrEventCommitter start() {
        return start(DEFAULT_CAPACITY, DEFAULT_DRAIN_TIMEOUT);
    }

    /**
     * Creates and starts a committer.
     *
     * @param capacity     ring capacity in events; must be {@code > 0}
     * @param drainTimeout maximum time {@link #close()} waits for in-flight events
     * @return a started committer ready to accept events
     */
    public static JfrEventCommitter start(int capacity, Duration drainTimeout) {
        Objects.requireNonNull(drainTimeout, "drainTimeout");
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, was " + capacity);
        }
        JfrEventCommitter committer = new JfrEventCommitter(capacity, drainTimeout);
        committer.consumer.start();
        return committer;
    }

    /** Returns the configured ring capacity (informational; useful for diagnostics). */
    public int capacity() {
        return capacity;
    }

    /** Returns the running total of dropped events since construction. */
    public long droppedCount() {
        return droppedCount.sum();
    }

    /**
     * Hands an already-constructed event to the platform drainer for commit.
     *
     * <p>Non-blocking. Returns {@code false} (drop-newest) when the ring is full or the committer
     * is closed; the caller may then choose to commit inline as a fallback, but on a request
     * virtual thread that is exactly what is unsafe — prefer letting the event drop.
     *
     * @param event a constructed, field-populated JFR event (never committed by the caller)
     * @return {@code true} if enqueued; {@code false} if dropped
     */
    public boolean offer(Event event) {
        Objects.requireNonNull(event, "event");
        if (closed.get() || !ring.offer(event)) {
            droppedCount.increment();
            JfrCommitDropEvent.emit(THREAD_NAME, droppedCount.sum(), capacity);
            return false;
        }
        return true;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // Wake the consumer so it can exit promptly once the ring is drained.
        consumer.interrupt();
        try {
            if (!consumerStopped.await(drainTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
                Thread.currentThread().interrupt();
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private void drain() {
        try {
            while (!closed.get() || !ring.isEmpty()) {
                Event event = pollNext();
                if (event != null) {
                    commitOne(event);
                }
            }
        } finally {
            consumerStopped.countDown();
        }
    }

    private Event pollNext() {
        try {
            return ring.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            // Clear the interrupt status now that we've recorded the cancellation intent —
            // otherwise the next ring.poll() would short-circuit forever and we'd never drain.
            Thread.interrupted();
            return null;
        }
    }

    // Swallows RuntimeException to keep the single drainer alive — same rationale as
    // AsyncTelemetrySink.fanOut: one misbehaving event's commit() must not stop all telemetry.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void commitOne(Event event) {
        try {
            event.commit();
        } catch (RuntimeException _) {
            // JFR surfaces its own failures through the recording; we keep draining so others commit.
        }
    }
}
