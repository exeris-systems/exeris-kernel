/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.tck.perf;

import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.spi.graph.GraphSession;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphTraversal;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * JMH Benchmark: Graph Engine session throughput and traversal latency.
 *
 * <h2>Front 4 — Arena wydajności (Graph SLO)</h2>
 * <p>Measures two critical hot-paths:
 * <ol>
 *   <li><b>Session churn:</b> {@code openSession()} + {@code close()} — verifies that
 *       the Enterprise slab-pool pattern produces zero heap objects per cycle.</li>
 *   <li><b>Single-hop BFS:</b> {@link GraphSession#traverseBreadthFirst(GraphTraversal)}
 *       with depth=1 — the dominant read pattern in Exeris domain graphs.</li>
 * </ol>
 *
 * <h2>SLO</h2>
 * <ul>
 *   <li>Session open/close: {@code ≥ 1 000 000 ops/s} (Enterprise slab pool, 0 B/op).</li>
 *   <li>Single-hop BFS p99: {@code ≤ 500 µs} on a cache-warm in-memory graph.</li>
 * </ul>
 *
 * <h2>Valhalla note</h2>
 * <p>{@link GraphTraversal} is a Valhalla-ready record (no identity ops).
 * The pre-built {@code hotTraversal} field is intentionally reused across iterations —
 * its immutability is guaranteed by the record contract.
 *
 * @since 0.5.0
 */
@State(Scope.Benchmark)
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
public abstract class AbstractGraphEngineBenchmark extends AbstractExerisBenchmark {

    // =========================================================================
    // Template methods
    // =========================================================================

    /** Creates a fully bootstrapped, running {@link GraphEngine}. */
    protected abstract GraphEngine createEngine();

    /**
     * Returns the {@link UUID} of a node that exists in the benchmark graph.
     * The engine must have this node registered before {@link #setUpTrial()} returns.
     */
    protected abstract UUID benchmarkStartNodeId();

    /**
     * Returns the {@link GraphEdgeDescriptor} representing the edge type
     * used in the single-hop BFS benchmark query.
     */
    protected abstract GraphEdgeDescriptor benchmarkEdgeDescriptor();

    /** Returns {@code true} for Enterprise-tier (zero-GC session pool). Default: false. */
    protected boolean isEnterpriseTier() { return false; }

    // =========================================================================
    // State
    // =========================================================================

    private GraphEngine    engine;
    private GraphTraversal hotTraversal; // pre-built, Valhalla-ready record — reused every iteration

    @Setup(Level.Trial)
    public void setUpTrial() {
        engine = createEngine();
        // Pre-build the hot traversal record once — immutable, safe for concurrent reuse.
        // maxDepth=1: single-hop lookup, the most common pattern.
        hotTraversal = GraphTraversal.create(
                benchmarkStartNodeId(),
                benchmarkEdgeDescriptor(),
                1);
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() {
        if (engine != null) {
            engine.close();
        }
    }

    // =========================================================================
    // Benchmark 1: Session open/close churn
    // SLO: ≥ 1 000 000 ops/s | Enterprise: 0 B/op (slab pool pop/push)
    // =========================================================================

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void sessionOpenClose(Blackhole bh) {
        // TWR ensures close() is called even if openSession() allocates a wrapper.
        // Enterprise: pop from Treiber-stack pool — O(1) CAS, 0 B/op.
        // Community:  new heap object wrapping a connection-pool checkout.
        try (GraphSession session = engine.openSession()) {
            bh.consume(session);
        }
    }

    // =========================================================================
    // Benchmark 2: Single-hop BFS latency
    // SLO: p99 ≤ 500 µs on in-memory/cache-warm graph
    // =========================================================================

    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public void singleHopTraversal(Blackhole bh) {
        // hotTraversal is a Valhalla-ready record — no identity, safely shared.
        try (GraphSession session = engine.openSession()) {
            var nodeIds = session.traverseBreadthFirst(hotTraversal);
            bh.consume(nodeIds);
        }
    }
}
