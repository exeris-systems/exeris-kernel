/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.graph;

import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.spi.graph.GraphSession;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphNodeDescriptor;
import eu.exeris.kernel.tck.contract.AbstractSubsystemCarrierPinningTck;
import org.junit.jupiter.api.DisplayName;

import java.util.List;
import java.util.UUID;

/**
 * TCK: Carrier pinning verifier for the Graph shortest-path hot path.
 *
 * <h2>Hot Path Under Test</h2>
 * <p>{@code openSession() → findShortestPath(src, tgt) → close()} — graph traversal
 * must never pin a carrier thread.
 *
 * @since 0.5
 * @see AbstractSubsystemCarrierPinningTck
 * @see ExecutionGraphZeroAllocTck
 */
@DisplayName("Graph carrier pinning TCK")
public abstract class GraphCarrierPinningTck extends AbstractSubsystemCarrierPinningTck {

    /**
     * Creates a bootstrapped {@link GraphEngine}. Schema registration happens in bootstrap.
     *
     * @return a bootstrapped graph engine
     */
    protected abstract GraphEngine createEngine();

    private GraphEngine engine;
    private UUID        src;
    private UUID        dst;

    /**
     * Creates the contract; subclasses supply the bootstrapped {@link GraphEngine} via {@link #createEngine()}.
     */
    public GraphCarrierPinningTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    @Override protected String subsystemName()      { return "Graph"; }
    @Override protected String hotPathDescription() { return "openSession() → findShortestPath(src, tgt) → close()"; }

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
        src = UUID.randomUUID();
        dst = UUID.randomUUID();
    }

    @Override
    protected void runSingleIteration() {
        try (GraphSession session = engine.openSession()) {
            session.findShortestPath(src, dst);
        }
    }

    @Override
    protected void tearDownSubsystem() {
        engine.close();
    }
}

