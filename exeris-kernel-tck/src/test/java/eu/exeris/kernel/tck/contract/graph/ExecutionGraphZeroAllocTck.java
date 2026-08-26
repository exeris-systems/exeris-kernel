/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.graph;

import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.spi.graph.GraphSession;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphNodeDescriptor;
import eu.exeris.kernel.tck.contract.AbstractSubsystemZeroAllocTck;

import java.util.List;
import java.util.UUID;

/**
 * TCK: JFR Zero-Allocation monitor for the Execution Graph hot path.
 *
 * <h2>The Holy Grail (Enterprise)</h2>
 * <p>A pre-bootstrapped {@link GraphEngine} (with a simple Task/Step schema)
 * repeatedly opens a {@link GraphSession}, invokes
 * {@link GraphSession#findShortestPath(Object, Object) findShortestPath(src, tgt)}
 * using fresh random {@link java.util.UUID} identifiers for {@code src} and
 * {@code tgt}, and then closes the session. This
 * {@code openSession() → findShortestPath() → close()} sequence MUST produce
 * ZERO {@code eu.exeris.*} heap objects in the steady-state phase. This is the
 * Enterprise contract for the graph shortest-path hot path.
 *
 * <h2>Community Contract</h2>
 * <p>For the same shortest-path hot path, Community implementations may allocate,
 * but allocations MUST remain bounded and proportional to the query workload —
 * no runaway churn or per-iteration allocation growth is allowed.
 *
 * @since 0.5.0
 */
public abstract class ExecutionGraphZeroAllocTck extends AbstractSubsystemZeroAllocTck {

    /** Creates a bootstrapped {@link GraphEngine}. Schema registration happens in bootstrap. */
    protected abstract GraphEngine createEngine();

    private GraphEngine engine;

    @Override protected String subsystemName()      { return "Graph"; }
    @Override protected String hotPathDescription()  { return "GraphSession.openSession() → findShortestPath() → close()"; }
    @Override protected int warmupIterations()       { return 100; }
    @Override protected int hotPathIterations()      { return 1_000; }
    @Override protected int maxExerisAllocationsPerIteration() { return 10; }

    @Override
    protected void bootstrapSubsystem() {
        engine = createEngine();
        engine.registerNodes(List.of(
                GraphNodeDescriptor.create("Task", "tasks"),
                GraphNodeDescriptor.create("Step", "steps")
        ));
        engine.registerEdges(List.of(
                GraphEdgeDescriptor.create("Task", "DEPENDS_ON", "Task"),
                GraphEdgeDescriptor.create("Task", "HAS_STEP", "Step")
        ));
    }

    @Override
    protected void runSingleIteration() {
        try (GraphSession session = engine.openSession()) {
            UUID src = UUID.randomUUID();
            UUID tgt = UUID.randomUUID();
            session.findShortestPath(src, tgt);
        }
    }

    @Override
    protected void tearDownSubsystem() {
        engine.close();
    }
}
