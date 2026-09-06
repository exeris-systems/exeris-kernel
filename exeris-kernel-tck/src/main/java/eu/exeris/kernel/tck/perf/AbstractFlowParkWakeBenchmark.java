/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.perf;

import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.FlowScheduler;
import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowDefinition;
import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;
import eu.exeris.kernel.spi.flow.model.FlowOutcome;
import eu.exeris.kernel.spi.flow.model.FlowState;
import eu.exeris.kernel.spi.flow.model.FlowStepAction;
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
 * JMH Benchmark: FlowEngine virtual-thread park/wake cycle.
 *
 * <p>Measures the heap allocation overhead of the FlowEngine's scheduler park→wake
 * cycle. In Enterprise tier (off-heap Saga wait-state), this is required to be
 * {@code 0 B/op} — all state transitions operate on pre-allocated off-heap slabs.
 *
 * <h2>SLO</h2>
 * <ul>
 *   <li>Community: allocation per op is acceptable (heap-backed contexts).</li>
 *   <li>Enterprise: <b>0 B/op</b> — the Flyweight {@link FlowContext} slides over
 *       the off-heap slab; no object is created per park/wake cycle.</li>
 * </ul>
 *
 * <h2>JMH Allocation Profiler</h2>
 * <p>Run with {@code -prof gc} to measure {@code norm.alloc} (bytes per operation). This
 * benchmark does not assert the allocation figure itself — conformance means reading
 * {@code norm.alloc} from that profiler output. Enterprise is required to report
 * {@code 0.0 B/op} in steady state.
 *
 * @since 0.5
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
public abstract class AbstractFlowParkWakeBenchmark extends AbstractExerisBenchmark {

    /**
     * Creates a fully configured, not-yet-started {@link FlowEngine}.
     *
     * @return non-null, pre-configured engine ready for {@link FlowEngine#start()}
     */
    protected abstract FlowEngine createEngine();

    /**
     * Returns {@code true} if this implementation is Enterprise-tier.
     * When {@code true}, the SLO for this benchmark requires 0 B/op allocation,
     * checked via the JMH {@code -prof gc} allocation profiler.
     * Default: {@code false}.
     *
     * @return {@code true} for an Enterprise-tier implementation, {@code false} otherwise
     */
    protected boolean isEnterpriseTier() {
        return false;
    }

    private FlowEngine        engine;
    private FlowScheduler     scheduler;
    private FlowExecutionPlan plan;
    private FlowContext       preWiredContext;

    /**
     * Creates the contract; subclasses supply the engine under test via {@link #createEngine()}.
     */
    public AbstractFlowParkWakeBenchmark() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Trial-level setup: creates and starts the engine, compiles a two-step no-op flow
     * definition, and schedules a reusable {@link FlowContext} that every measurement
     * iteration parks and wakes.
     */
    @Setup(Level.Trial)
    public void setUpTrial() {
        engine = createEngine();
        engine.start();

        FlowStepAction noOp = ctx -> FlowOutcome.CONTINUE;
        FlowDefinition def = engine.plans().newDefinition("park-wake-bench")
                .step("step-a", noOp, null)
                .step("step-b", noOp, null)
                .transition(0, 1)
                .build();
        plan = engine.plans().compile(def);
        scheduler = engine.scheduler();

        // Pre-wire a reusable context — Enterprise returns the Flyweight for this address
        preWiredContext = createReusableContext();
        scheduler.schedule(plan, preWiredContext);
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
     * Hot path: park the pre-wired context, then wake it.
     *
     * <p><b>Enterprise SLO:</b> the park/wake cycle is required to operate on raw
     * {@code long} addresses in the off-heap ring buffer and parked-set slab, creating
     * no object — {@code 0 B/op}, checked via the JMH {@code -prof gc} allocation profiler.
     *
     * <p><b>Community:</b> park/wake involves a {@code ConcurrentHashMap} put/remove.
     * Some allocation is expected (Map.Entry, etc.).
     *
     * @param bh JMH blackhole — prevents the JIT from eliminating the context reference
     */
    @Benchmark
    public void parkAndWakeCycle(Blackhole bh) {
        scheduler.park(preWiredContext);
        scheduler.wake(preWiredContext);
        bh.consume(preWiredContext.instanceIdMost());
    }

    /**
     * Throughput baseline: schedule + park + wake in a tight loop.
     *
     * <p>The context is reused across iterations — Enterprise implementations
     * must support this via their Flyweight design.
     *
     * @param bh JMH blackhole — prevents the JIT from eliminating the context reference
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void scheduleParkWakeThroughput(Blackhole bh) {
        scheduler.schedule(plan, preWiredContext);
        scheduler.park(preWiredContext);
        scheduler.wake(preWiredContext);
        bh.consume(preWiredContext.currentStep());
    }

    // =========================================================================
    // Helper — creates a stable context for reuse across iterations
    // =========================================================================

    private static FlowContext createReusableContext() {
        UUID uuid = UUID.nameUUIDFromBytes("bench-ctx-stable".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new FlowContext() {
            @Override public long instanceIdMost()    { return uuid.getMostSignificantBits(); }
            @Override public long instanceIdLeast()   { return uuid.getLeastSignificantBits(); }
            @Override public String definitionName()  { return "park-wake-bench"; }
            @Override public int currentStep()        { return 0; }
            @Override public FlowState state()        { return FlowState.RUNNING; }
            @Override public long timeoutNanos()      { return Long.MAX_VALUE; }
        };
    }
}



