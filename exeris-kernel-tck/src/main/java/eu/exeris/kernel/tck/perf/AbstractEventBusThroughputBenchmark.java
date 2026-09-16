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
 * itself does not pollute the measurement window — only resolving the
 * subscribers and handing each one the payload is exercised in the hot path.
 *
 * <h2>Why this matters</h2>
 * <p>An event bus can dispatch in more than one shape, and the shape is what this
 * number is about. The binding that ships in this repository resolves subscribers
 * through a map and starts one virtual thread per subscriber directly from the
 * calling thread; a binding built on a pre-allocated ring buffer would instead
 * write a descriptor and return. Both satisfy {@link EventBus#publish}, and the
 * throughput report is where the difference between them becomes visible rather
 * than assumed. This template states no cost relation between them — it measures
 * whichever one is bound.
 *
 * <h2>Handler lifecycle</h2>
 * <p>The registered dummy handler calls {@link EventPayload#close()}, which the
 * payload contract requires of every holder. It matters more here than in a
 * correctness test: a binding that serves payloads from a pool has no collector
 * behind it, so a benchmark that skips the close measures an allocator sliding
 * into exhaustion rather than the dispatch path.
 *
 * <h2>Implementing this benchmark</h2>
 * {@snippet lang="java" :
 * public class MyCommunityEventBusBenchmark
 *         extends AbstractEventBusThroughputBenchmark {
 *
 *     @Override
 *     protected EventEngine createTargetEngine() {
 *         return new CommunityEventEngine(new EventEngineConfig(...));
 *     }
 * }
 * }
 *
 * @since 0.5
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
     * Pre-allocated hot-path descriptor. Every component is a primitive, so the record carries
     * no reference the measurement would have to chase; whether the JIT then keeps it off the
     * heap is not something this template asserts or measures.
     */
    protected EventDescriptor hotDescriptor;

    /**
     * Creates the contract; subclasses supply the engine under test via
     * {@link #createTargetEngine()}.
     */
    public AbstractEventBusThroughputBenchmark() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

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

        // Register before start(): a binding is free to build its routing table once at startup
        // and reject a late registration, so registering first is what every binding accepts.
        engine.registry().register(EventTypeSpec.of(BENCHMARK_EVENT_TYPE, BENCHMARK_EVENT_ORDINAL));

        // Dummy handler: force the bus to execute its full routing + dispatch logic.
        // Close the payload, as its contract requires of every holder — see the class comment
        // for why a benchmark in particular cannot skip it.
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
     * Shuts the engine down between trials, so whatever the binding acquired at setup —
     * threads, buffers, its routing table — is released before the next one measures.
     */
    @TearDown(Level.Trial)
    public void tearDownEngine() {
        if (engine != null) {
            engine.close();
        }
    }

    /**
     * Measures one {@link EventBus#publish} and nothing around it.
     *
     * <p>The {@link EventPayload#empty()} sentinel is used deliberately: its retain and close
     * are no-ops, so payload lifecycle does not bias the result. What remains inside the
     * window is the subscriber lookup by ordinal and whatever the binding does to hand each
     * subscriber the event — which is the thing being compared.
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

