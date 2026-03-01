/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
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
 * <p>Building a dependency DAG, compiling to an ExecutionPlan, and running it
 * through the GraphScheduler MUST produce ZERO {@code eu.exeris.*} heap objects
 * in the steady-state phase. This is the Enterprise contract.
 *
 * <h2>Community Contract</h2>
 * <p>Community allocations are bounded and proportional — no runaway churn.
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
