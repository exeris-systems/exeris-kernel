/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.perf;

import eu.exeris.kernel.spi.events.EventBus;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventTypeSpec;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * JMH Benchmark: EventBus dispatch latency and throughput.
 *
 * <p>Measures end-to-end publish-to-handle latency of {@link EventBus#publish} with a single
 * registered handler, single-threaded and unthrottled: the benchmark loop calls {@code publish}
 * as fast as it can, it does not pace itself to any particular message rate.
 *
 * <h2>SLO targets</h2>
 * <p>These are the production-load targets this benchmark is calibrated to read against, not
 * figures the benchmark drives or asserts — JMH does not fail the build on either being missed,
 * so conformance means comparing the printed report to these numbers by hand.
 * <ul>
 *   <li>Average throughput: {@code ≥ 100 000 ops/s}</li>
 *   <li>p99 latency: {@code ≤ 200 µs}, read against a 100 000 msg/s production target</li>
 * </ul>
 *
 * <h2>Measurement methodology</h2>
 * <p>A single pre-registered handler processes every published event (registered at
 * setup). JMH runs a single-threaded {@code SampleTime} benchmark calling
 * {@link EventBus#publish} in a tight loop. This measures the <em>enqueue</em>
 * cost — the latency from the caller's perspective. Use the
 * {@link EventBus#publishAndAwait} variant for full end-to-end measurement.
 *
 * @since 0.5
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
public abstract class AbstractEventBusDispatchLatencyBenchmark extends AbstractExerisBenchmark {

    private static final String EVENT_TYPE    = "LatencyBenchEvent";
    private static final int    EVENT_ORDINAL = 42;

    /** The engine under test. */
    protected EventEngine engine;

    /** Pre-allocated hot-path descriptor — all-primitive, Valhalla-ready. */
    protected EventDescriptor hotDescriptor;

    /**
     * Creates the contract; subclasses supply the engine under test via
     * {@link #createTargetEngine()}.
     */
    public AbstractEventBusDispatchLatencyBenchmark() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Subclass provides a fully configured, not-yet-started {@link EventEngine}.
     *
     * @return non-null, pre-configured engine ready for {@link EventEngine#start()}
     * @implSpec Implementations must return a new engine instance; {@link #setUpTrial()} starts
     *           it and registers the benchmark event type before any {@code @Benchmark} method
     *           runs.
     */
    protected abstract EventEngine createTargetEngine();

    /**
     * Trial-level setup: creates the engine, registers the benchmark event type, subscribes a
     * handler that closes each payload, starts the engine, and pre-allocates the hot-path
     * descriptor reused by every measurement iteration.
     */
    @Setup(Level.Trial)
    public void setUpTrial() {
        engine = createTargetEngine();
        engine.registry().register(EventTypeSpec.of(EVENT_TYPE, EVENT_ORDINAL));
        // Pre-register a handler that closes the payload correctly (no slab leak)
        engine.bus().subscribe(EVENT_TYPE, (descriptor, payload) -> {
            try (payload) {  // NOPMD EmptyControlStatement - closing the payload IS the contract
                // intentionally minimal — we measure routing overhead, not handler work
            }
        });
        engine.start();

        UUID id = UUID.randomUUID();
        hotDescriptor = new EventDescriptor(
                id.getMostSignificantBits(), id.getLeastSignificantBits(),
                0L, 0L,
                EVENT_ORDINAL, EventDescriptor.FLAG_ASYNC, System.currentTimeMillis());
    }

    /**
     * Trial-level teardown: closes the engine and releases any resources it holds.
     */
    @TearDown(Level.Trial)
    public void tearDownTrial() {
        if (engine != null) {
            engine.close();
        }
    }

    /**
     * Hot path: single publish call with the pre-allocated descriptor, sampled by JMH's
     * {@code SampleTime} mode to report latency percentiles.
     *
     * <p>Community: enqueues to a StructuredTaskScope-backed queue.
     * Enterprise: single CAS write to the off-heap lock-free ring buffer.
     *
     * <p><b>SLO target:</b> p99 latency target is {@code ≤ 200 µs} against a 100k ops/s
     * production load; this is not enforced by the benchmark and must be checked against
     * the printed percentile report.
     *
     * @param bh JMH blackhole — prevents the JIT from eliminating the descriptor reference
     */
    @Benchmark
    public void publishEventLatency(Blackhole bh) {
        // EventPayload.empty() is a sentinel with no backing memory — zero allocation.
        engine.bus().publish(hotDescriptor, EventPayload.empty());
        bh.consume(hotDescriptor.eventTypeOrdinal());
    }

    /**
     * Throughput baseline: measures raw publish throughput.
     *
     * <p>Throughput target is {@code ≥ 100 000 ops/s}; not enforced by the benchmark.
     *
     * @param bh JMH blackhole — prevents the JIT from eliminating the descriptor reference
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void publishEventThroughput(Blackhole bh) {
        engine.bus().publish(hotDescriptor, EventPayload.empty());
        bh.consume(hotDescriptor.eventTypeOrdinal());
    }
}

