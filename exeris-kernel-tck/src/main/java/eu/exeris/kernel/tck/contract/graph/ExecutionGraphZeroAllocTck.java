/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.graph;

import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.spi.graph.GraphSession;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphNodeDescriptor;
import eu.exeris.kernel.spi.graph.model.PathResult;
import eu.exeris.kernel.tck.contract.AbstractSubsystemZeroAllocTck;

import java.util.List;
import java.util.UUID;

/**
 * TCK: JFR Zero-Allocation monitor for the Execution Graph hot path.
 *
 * <h2>The Holy Grail (Enterprise)</h2>
 * <p>A pre-bootstrapped {@link GraphEngine} (with a simple Task/Step schema)
 * repeatedly opens a {@link GraphSession}, invokes
 * {@link GraphSession#findShortestPath(UUID, UUID) findShortestPath(src, tgt)}
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
 * <p>A binding needs a live backend whose session works on the calling thread.
 *
 * @since 0.5
 */
public abstract class ExecutionGraphZeroAllocTck extends AbstractSubsystemZeroAllocTck {

    private static final GraphEdgeDescriptor DEPENDS_ON =
            GraphEdgeDescriptor.create("Task", "DEPENDS_ON", "Task");

    /**
     * Creates a bootstrapped {@link GraphEngine}. Schema registration happens in bootstrap.
     *
     * @return a bootstrapped graph engine
     */
    protected abstract GraphEngine createEngine();

    private GraphEngine engine;
    private UUID src;
    private UUID mid;
    private UUID tgt;

    /**
     * Creates the contract; subclasses supply the bootstrapped {@link GraphEngine} via {@link #createEngine()}.
     */
    public ExecutionGraphZeroAllocTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    @Override protected String subsystemName()      { return "Graph"; }
    @Override protected String hotPathDescription()  { return "GraphSession.openSession() → findShortestPath(edge, src, tgt) → close()"; }
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
                DEPENDS_ON,
                GraphEdgeDescriptor.create("Task", "HAS_STEP", "Step")
        ));
        src = UUID.randomUUID();
        mid = UUID.randomUUID();
        tgt = UUID.randomUUID();
        try (GraphSession session = engine.openSession()) {
            session.createEdge(DEPENDS_ON, src, mid, 1.0, null);
            session.createEdge(DEPENDS_ON, mid, tgt, 1.0, null);
        }
        try (GraphSession session = engine.openSession()) {
            PathResult result = session.findShortestPath(DEPENDS_ON, src, tgt);
            if (!result.found() || result.hopCount() != 2) {
                throw new IllegalStateException("Shortest path search failed to find path with 2 hops, got: " + result);
            }
        }
    }

    @Override
    protected void runSingleIteration() {
        try (GraphSession session = engine.openSession()) {
            session.findShortestPath(DEPENDS_ON, src, tgt);
        }
    }

    @Override
    protected void tearDownSubsystem() {
        engine.close();
    }
}
