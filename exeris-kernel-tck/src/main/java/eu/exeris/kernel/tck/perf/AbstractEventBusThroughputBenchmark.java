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
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

/**
 * TCK JMH Benchmark: Event Bus Publish Throughput.
 *
 * <h2>What is measured</h2>
 * <p>The pure overhead of {@link EventBus#publish} with a pre-allocated
 * {@link EventDescriptor} and the empty no-data {@link EventPayload} sentinel.
 * The descriptor is built once at setup time so that allocation of the record
 * itself does not pollute the measurement window — only the routing and
 * enqueueing logic is exercised in the hot path.
 *
 * <h2>Why this matters</h2>
 * <p>Community implementations route through a {@code ConcurrentHashMap} and fork
 * virtual threads via {@code StructuredTaskScope} per publish. Enterprise
 * implementations write a 56-byte descriptor to a lock-free off-heap ring buffer
 * in O(1). This benchmark forces both tiers to reveal the true cost of their
 * dispatch path — the difference will typically be one to two orders of magnitude.
 *
 * <h2>Handler lifecycle</h2>
 * <p>The registered dummy handler correctly calls {@link EventPayload#close()} so
 * that the Enterprise slab ref-count reaches zero and the backing memory is
 * returned to the pool. Omitting the close would saturate the slab allocator
 * and produce misleading results (OOM or allocation-failure JFR events).
 *
 * <h2>Implementing this benchmark</h2>
 * <pre>{@code
 * public class MyCommunityEventBusBenchmark
 *         extends AbstractEventBusThroughputBenchmark {
 *
 *     \@Override
 *     protected EventEngine createTargetEngine() {
 *         return new CommunityEventEngine(new EventEngineConfig(...));
 *     }
 * }
 * }</pre>
 *
 * @since 0.5.0
 * @see AbstractExerisBenchmark
 */
public abstract class AbstractEventBusThroughputBenchmark extends AbstractExerisBenchmark {

    /** The event type name registered in the benchmark registry. */
    private static final String BENCHMARK_EVENT_TYPE = "BenchmarkEvent";

    /** Ordinal assigned at setup time — drives O(1) routing on the hot path. */
    private static final int BENCHMARK_EVENT_ORDINAL = 1;

    /** The engine under test — initialised once per trial. */
    protected EventEngine engine;

    /**
     * Pre-allocated hot-path descriptor — all 7 fields are primitives, Valhalla-ready.
     * C2 may scalarize this on the Community heap path via escape analysis.
     */
    protected EventDescriptor hotDescriptor;

    /**
     * Implementations must return a fully configured, but <em>not yet started</em>,
     * {@link EventEngine}. Memory allocators and {@code ScopedValue} context must be
     * prepared if the implementation requires them.
     *
     * @return non-null, pre-configured engine ready for {@link EventEngine#start()}
     */
    protected abstract EventEngine createTargetEngine();

    /**
     * Trial-level setup: registers the benchmark event type, attaches a consuming
     * handler that correctly closes the payload, starts the engine, and pre-allocates
     * the hot-path descriptor used throughout the measurement window.
     */
    @Setup(Level.Trial)
    public void setupEngine() {
        this.engine = createTargetEngine();

        // Register the event type before start() — mandatory for Enterprise
        // (off-heap routing table is built once at startup; late registration throws).
        engine.registry().register(EventTypeSpec.of(BENCHMARK_EVENT_TYPE, BENCHMARK_EVENT_ORDINAL));

        // Dummy handler: force the bus to execute its full routing + dispatch logic.
        // Correctly close the payload so Enterprise slab ref-counts are balanced.
        engine.bus().subscribe(BENCHMARK_EVENT_TYPE, (descriptor, payload) -> {
            // Hot-path consumption — RAII lifecycle maintained via close().
            payload.close();
        });

        this.engine.start();

        // Pre-allocate the descriptor once — eliminates record-allocation cost from
        // the measurement window, isolating pure dispatch overhead.
        this.hotDescriptor = EventDescriptor.of(
                1L, 2L,       // eventIdHigh / eventIdLow
                3L, 4L,       // streamIdHigh / streamIdLow
                BENCHMARK_EVENT_ORDINAL,
                EventDescriptor.FLAG_ASYNC,
                System.currentTimeMillis()
        );
    }

    /**
     * Trial-level teardown: gracefully shuts down the engine and releases all
     * off-heap resources (slab pools, carrier loops, routing tables).
     */
    @TearDown(Level.Trial)
    public void tearDownEngine() {
        if (engine != null) {
            engine.close();
        }
    }

    /**
     * Hot-path benchmark — measures the pure overhead of event bus routing and enqueueing.
     *
     * <p>The {@link EventPayload#empty()} sentinel is used deliberately: its retain/close
     * are no-ops, ensuring that payload lifecycle management does not bias the result.
     * Only the routing lookup (ordinal → subscriber list) and the enqueue operation
     * are measured.
     *
     * @param bh JMH blackhole — prevents the JIT from eliminating the descriptor reference
     */
    @Benchmark
    public void publishOverhead(Blackhole bh) {
        // Ownership of EventPayload.empty() is transferred to the bus.
        // The bus calls close() (no-op for the empty sentinel) after dispatch.
        engine.bus().publish(hotDescriptor, EventPayload.empty());
        bh.consume(hotDescriptor);
    }
}

