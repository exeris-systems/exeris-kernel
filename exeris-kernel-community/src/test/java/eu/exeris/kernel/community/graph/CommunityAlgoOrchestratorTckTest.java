/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.spi.graph.algorithm.PathFinder;
import eu.exeris.kernel.tck.contract.graph.AbstractAlgoOrchestratorTck;
import org.junit.jupiter.api.DisplayName;

import java.util.UUID;

/**
 * Community concrete TCK: {@link AbstractAlgoOrchestratorTck} backed by
 * a minimal Dijkstra-based PathFinder implementation.
 *
 * <h2>What this proves for Community tier</h2>
 * <ul>
 *   <li>PathFinder.findShortestPath() returns correct paths</li>
 *   <li>Path results include correct hop counts and costs</li>
 *   <li>Not-found results are properly shaped (empty path, Double.POSITIVE_INFINITY cost)</li>
 *   <li>K-shortest paths are returned in cost-ascending order</li>
 *   <li>Concurrent access is safe</li>
 * </ul>
 *
 * @since 0.5.0
 */
@DisplayName("Community: PathFinder algorithm contract TCK")
class CommunityAlgoOrchestratorTckTest extends AbstractAlgoOrchestratorTck {

    private static final UUID SOURCE_NODE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TARGET_NODE = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID ISOLATED_NODE = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    @Override
    protected PathFinder createPathFinder() {
        UUID b = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID d = UUID.fromString("00000000-0000-0000-0000-000000000004");
        return CommunityPathFinder.builder()
                .addEdge(SOURCE_NODE, b, 1.0)
                .addEdge(SOURCE_NODE, TARGET_NODE, 5.0)
                .addEdge(SOURCE_NODE, d, 2.0)
                .addEdge(b, TARGET_NODE, 1.0)
                .addEdge(d, TARGET_NODE, 2.0)
                .build();
        // ISOLATED_NODE has no edges — absent from adjacency map intentionally
    }

    @Override
    protected UUID reachableSource() {
        return SOURCE_NODE;
    }

    @Override
    protected UUID reachableTarget() {
        return TARGET_NODE;
    }

    @Override
    protected UUID isolatedNode() {
        return ISOLATED_NODE;
    }

}
