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
 * JMH Benchmark: EventBus Dispatch Latency at 100k msg/s.
 *
 * <h2>Front 4 — Arena wydajności (SLO: p99 &lt; 200 µs at 100k msg/s)</h2>
 * <p>Measures end-to-end publish-to-handle latency of {@link EventBus#publish}
 * under sustained 100 000 msg/s load with a single registered handler.
 *
 * <h2>SLO</h2>
 * <ul>
 *   <li>Average throughput: {@code ≥ 100 000 ops/s}</li>
 *   <li>p99 latency: {@code ≤ 200 µs}</li>
 * </ul>
 *
 * <h2>Measurement methodology</h2>
 * <p>A single pre-registered handler processes every published event (registered at
 * setup). JMH runs a single-threaded {@code SampleTime} benchmark calling
 * {@link EventBus#publish} in a tight loop. This measures the <em>enqueue</em>
 * cost — the latency from the caller's perspective. Use the
 * {@link EventBus#publishAndAwait} variant for full end-to-end measurement.
 *
 * @since 0.5.0
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
     * Subclass provides a fully configured, not-yet-started {@link EventEngine}.
     */
    protected abstract EventEngine createTargetEngine();

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

    @TearDown(Level.Trial)
    public void tearDownTrial() {
        if (engine != null) {
            engine.close();
        }
    }

    /**
     * Hot path: single publish call with the pre-allocated descriptor.
     *
     * <p>Community: enqueues to a StructuredTaskScope-backed queue.
     * Enterprise: single CAS write to the off-heap lock-free ring buffer.
     *
     * <h2>SLO</h2>
     * <p>p99 latency of this method MUST be ≤ 200 µs at 100k ops/s throughput.
     */
    @Benchmark
    public void publishEventLatency(Blackhole bh) {
        // EventPayload.empty() is a sentinel with no backing memory — zero allocation.
        engine.bus().publish(hotDescriptor, EventPayload.empty());
        bh.consume(hotDescriptor.eventTypeOrdinal());
    }

    /**
     * Throughput baseline: measures raw publish throughput.
     * SLO: ≥ 100 000 ops/s.
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void publishEventThroughput(Blackhole bh) {
        engine.bus().publish(hotDescriptor, EventPayload.empty());
        bh.consume(hotDescriptor.eventTypeOrdinal());
    }
}

