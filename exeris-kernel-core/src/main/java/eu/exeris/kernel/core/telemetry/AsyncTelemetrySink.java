/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.telemetry;

import eu.exeris.kernel.spi.telemetry.KernelEvent;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.MpscArrayQueue;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * Core-internal asynchronous {@link TelemetrySink} that decouples the caller's
 * critical path from sink fan-out.
 *
 * <h2>Why async</h2>
 * <p>The Glass-Box {@code emit} contract is zero-allocation, but synchronous
 * fan-out to multiple downstream sinks (JFR + SLF4J + File) still pays the
 * latency of the slowest sink on the caller thread. Wrapping the fan-out in an
 * async dispatcher gives each caller a bounded enqueue cost while a dedicated
 * virtual-thread consumer drains the ring into the wrapped sinks.
 *
 * <h2>Lifecycle</h2>
 * <pre>
 *   AsyncTelemetrySink.start(sinks, capacity, drainTimeout)  → consumer VT started
 *   sink.emit(event)                                          → enqueue (drop-newest if full)
 *   sink.close()                                              → drain pending events, join VT
 * </pre>
 *
 * <h2>Drop policy</h2>
 * <p>When the ring is full, {@link #emit(KernelEvent)} discards the incoming
 * event (drop-newest), increments the drop counter, and emits an
 * {@link AsyncTelemetryDropEvent} JFR event so operators can detect runaway
 * emission rates. Drop-newest is preferred over drop-oldest because the oldest
 * frames carry causal context that may be needed to diagnose the very burst
 * causing the overflow.
 *
 * <h2>Metrics pass-through</h2>
 * <p>{@link #increment(String, long)}, {@link #gauge(String, long)} and
 * {@link #latency(String, long)} are forwarded synchronously to all wrapped
 * sinks. These calls are already cheap (primitives only) and asynchronous
 * dispatch would add overhead without benefit.
 *
 * <h2>Thread safety</h2>
 * <p>All public methods are safe to call from any number of producer threads.
 * The single internal consumer is the only thread that calls {@code emit} on
 * wrapped sinks, simplifying their thread-safety story.
 *
 * @since 0.7
 */
@SuppressWarnings({
    "PMD.CloseResource",                  // wrapped sinks are owned externally; close() closes them once
    "PMD.AvoidCatchingGenericException",  // fan-out swallows RuntimeException to keep the consumer alive
    "PMD.CyclomaticComplexity",           // constructor + drain loop validation paths are intentional
    "PMD.TooManyMethods"                  // TelemetrySink contract surface + 2-arg/3-arg start() factories
})
public final class AsyncTelemetrySink implements TelemetrySink {

    /** Default ring capacity — covers typical lifecycle event bursts without sustained pressure. */
    public static final int DEFAULT_CAPACITY = 4_096;

    /** Default drain timeout on {@link #close()}. */
    public static final Duration DEFAULT_DRAIN_TIMEOUT = Duration.ofSeconds(2L);

    private static final String SINK_NAME = "ExerisCore/AsyncTelemetrySink";

    /** Idle-poll park budget when the ring is empty. Matches the legacy ABQ poll timeout. */
    private static final long IDLE_PARK_NANOS = 50L * 1_000_000L;

    private final List<TelemetrySink> downstream;
    /**
     * Lock-free MPSC ring backing the producer/consumer split (PERF-070).
     * Many producer threads call {@link #emit(KernelEvent)} → wait-free offer;
     * exactly one consumer (the dedicated VT in {@link #drain()}) polls and
     * fans out to downstream sinks.
     */
    private final MessagePassingQueue<KernelEvent> ring;
    private final int capacity;
    private final Duration drainTimeout;
    private final Thread consumer;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final CountDownLatch consumerStopped = new CountDownLatch(1);
    private final LongAdder droppedCount = new LongAdder();

    private AsyncTelemetrySink(List<TelemetrySink> downstream, int capacity, Duration drainTimeout) {
        this.downstream = List.copyOf(downstream);
        // MpscArrayQueue rounds capacity up to nearest power of 2 internally — the configured
        // value remains the operator-visible budget for drop-newest accounting.
        this.ring = new MpscArrayQueue<>(capacity);
        this.capacity = capacity;
        this.drainTimeout = drainTimeout;
        this.consumer = Thread.ofVirtual().name(SINK_NAME + "-consumer").unstarted(this::drain);
    }

    /**
     * Creates and starts an async sink with default capacity and drain timeout.
     *
     * @param downstream non-null, non-empty list of sinks to fan out to
     * @return a started sink ready to accept events
     */
    public static AsyncTelemetrySink start(List<TelemetrySink> downstream) {
        return start(downstream, DEFAULT_CAPACITY, DEFAULT_DRAIN_TIMEOUT);
    }

    /**
     * Creates and starts an async sink with custom capacity and drain timeout.
     *
     * @param downstream   non-null, non-empty list of sinks to fan out to
     * @param capacity     ring capacity in events; must be {@code > 0}
     * @param drainTimeout maximum time {@link #close()} will wait for in-flight events
     * @return a started sink ready to accept events
     */
    public static AsyncTelemetrySink start(List<TelemetrySink> downstream, int capacity, Duration drainTimeout) {
        Objects.requireNonNull(downstream, "downstream");
        Objects.requireNonNull(drainTimeout, "drainTimeout");
        if (downstream.isEmpty()) {
            throw new IllegalArgumentException("downstream must contain at least one sink");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, was " + capacity);
        }
        AsyncTelemetrySink sink = new AsyncTelemetrySink(downstream, capacity, drainTimeout);
        sink.consumer.start();
        return sink;
    }

    /** Returns the configured ring capacity (informational; useful for diagnostics). */
    public int capacity() {
        return capacity;
    }

    /** Returns the running total of dropped events since construction. */
    public long droppedCount() {
        return droppedCount.sum();
    }

    @Override
    public void emit(KernelEvent event) {
        Objects.requireNonNull(event, "event");
        if (closed.get()) {
            return;
        }
        if (!ring.offer(event)) {
            droppedCount.increment();
            AsyncTelemetryDropEvent.emit(SINK_NAME, event.code(), droppedCount.sum(), capacity);
            return;
        }
        // Wake the consumer if it's parked on an empty ring. LockSupport.unpark is
        // permit-counted (single permit max), so producer-side over-signal is harmless;
        // when the consumer is actively draining, the call is a cheap no-op kernel hop.
        // Required because MpscArrayQueue.offer is wait-free but does NOT signal a
        // blocking consumer (unlike ArrayBlockingQueue.offer which woke ABQ's internal
        // condition variable). Without this unpark, events sat in the ring up to the
        // full IDLE_PARK_NANOS budget — observed as multi-second fan-out latency
        // under CoreFlowEngineTest's 512-iteration schedule/park/wake load (PR #139).
        LockSupport.unpark(consumer);
    }

    @Override
    public void increment(String name, long delta) {
        for (TelemetrySink sink : downstream) {
            sink.increment(name, delta);
        }
    }

    @Override
    public void gauge(String name, long value) {
        for (TelemetrySink sink : downstream) {
            sink.gauge(name, value);
        }
    }

    @Override
    public void latency(String name, long nanoseconds) {
        for (TelemetrySink sink : downstream) {
            sink.latency(name, nanoseconds);
        }
    }

    @Override
    public String sinkName() {
        return SINK_NAME;
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
                // Drain timed out — leave the consumer to exit on its own; downstream sinks
                // will be closed below regardless so no resource leak.
                Thread.currentThread().interrupt();
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
        for (TelemetrySink sink : downstream) {
            sink.close();
        }
    }

    private void drain() {
        try {
            while (!closed.get() || !ring.isEmpty()) {
                KernelEvent event = ring.poll();
                if (event != null) {
                    fanOut(event);
                } else {
                    parkIdle();
                }
            }
        } finally {
            consumerStopped.countDown();
        }
    }

    /**
     * Parks the consumer when the ring is empty. Bounded to {@link #IDLE_PARK_NANOS}
     * (50 ms) to preserve the legacy idle-wake cadence of the prior
     * {@code ArrayBlockingQueue.poll(50, MILLISECONDS)} implementation.
     *
     * <p>{@link #close()} interrupts the consumer to break out of the park promptly.
     * The interrupt status is cleared after observing the cancellation so that the
     * next loop iteration can re-check {@link #closed} / {@code ring.isEmpty()} and
     * decide whether to exit.
     */
    private void parkIdle() {
        LockSupport.parkNanos(IDLE_PARK_NANOS);
        if (Thread.interrupted()) {
            // Cancellation observed via interrupt — re-set status so the loop predicate
            // sees the closed flag (set by close() before interrupting us) on next pass.
            Thread.currentThread().interrupt();
            Thread.interrupted();
        }
    }

    private void fanOut(KernelEvent event) {
        for (TelemetrySink sink : downstream) {
            try {
                sink.emit(event);
            } catch (RuntimeException _) {
                // A misbehaving sink must not poison the consumer thread or starve other sinks.
                // We swallow the exception here; downstream telemetry frameworks will surface it
                // through their own diagnostic channels (e.g., SLF4J error log, JFR fail event).
            }
        }
    }
}
