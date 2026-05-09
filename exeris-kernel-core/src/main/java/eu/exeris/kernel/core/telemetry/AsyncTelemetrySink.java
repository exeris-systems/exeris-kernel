/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.telemetry;

import eu.exeris.kernel.spi.telemetry.KernelEvent;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

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
 *   new AsyncTelemetrySink(sinks, capacity)  → consumer VT started
 *   sink.emit(event)                          → enqueue (drop-newest if full)
 *   sink.close()                              → drain pending events, join VT
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
 * @since 0.7.0
 */
@SuppressWarnings({
    "PMD.CloseResource",                  // wrapped sinks are owned externally; close() closes them once
    "PMD.AvoidCatchingGenericException",  // fan-out swallows RuntimeException to keep the consumer alive
    "PMD.CyclomaticComplexity"            // constructor + drain loop validation paths are intentional
})
public final class AsyncTelemetrySink implements TelemetrySink {

    /** Default ring capacity — covers typical lifecycle event bursts without sustained pressure. */
    public static final int DEFAULT_CAPACITY = 4_096;

    /** Default drain timeout on {@link #close()}. */
    public static final Duration DEFAULT_DRAIN_TIMEOUT = Duration.ofSeconds(2L);

    private static final String SINK_NAME = "ExerisCore/AsyncTelemetrySink";

    private final List<TelemetrySink> downstream;
    private final BlockingQueue<KernelEvent> ring;
    private final int capacity;
    private final Duration drainTimeout;
    private final Thread consumer;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final CountDownLatch consumerStopped = new CountDownLatch(1);
    private final LongAdder droppedCount = new LongAdder();

    /**
     * Creates an async sink with default capacity and drain timeout.
     *
     * @param downstream non-null, non-empty list of sinks to fan out to
     */
    public AsyncTelemetrySink(List<TelemetrySink> downstream) {
        this(downstream, DEFAULT_CAPACITY, DEFAULT_DRAIN_TIMEOUT);
    }

    /**
     * Creates an async sink with custom capacity and drain timeout.
     *
     * @param downstream   non-null, non-empty list of sinks to fan out to
     * @param capacity     ring capacity in events; must be {@code > 0}
     * @param drainTimeout maximum time {@link #close()} will wait for in-flight events
     */
    public AsyncTelemetrySink(List<TelemetrySink> downstream, int capacity, Duration drainTimeout) {
        Objects.requireNonNull(downstream, "downstream");
        Objects.requireNonNull(drainTimeout, "drainTimeout");
        if (downstream.isEmpty()) {
            throw new IllegalArgumentException("downstream must contain at least one sink");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, was " + capacity);
        }
        this.downstream = List.copyOf(downstream);
        this.ring = new ArrayBlockingQueue<>(capacity);
        this.capacity = capacity;
        this.drainTimeout = drainTimeout;
        this.consumer = Thread.ofVirtual().name(SINK_NAME + "-consumer").unstarted(this::drain);
        this.consumer.start();
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
        }
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
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        for (TelemetrySink sink : downstream) {
            sink.close();
        }
    }

    private void drain() {
        try {
            while (!closed.get() || !ring.isEmpty()) {
                KernelEvent event;
                try {
                    event = ring.poll(50L, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ex) {
                    // Either close() asked us to stop or a spurious interrupt — keep draining
                    // remaining items, then exit on the next loop check.
                    if (closed.get() && ring.isEmpty()) {
                        return;
                    }
                    continue;
                }
                if (event == null) {
                    continue;
                }
                fanOut(event);
            }
        } finally {
            consumerStopped.countDown();
        }
    }

    private void fanOut(KernelEvent event) {
        for (TelemetrySink sink : downstream) {
            try {
                sink.emit(event);
            } catch (RuntimeException ex) {
                // A misbehaving sink must not poison the consumer thread or starve other sinks.
                // We swallow the exception here; downstream telemetry frameworks will surface it
                // through their own diagnostic channels (e.g., SLF4J error log, JFR fail event).
            }
        }
    }
}
