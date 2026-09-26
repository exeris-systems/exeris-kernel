/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.spi.graph.GraphConfig;
import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.tck.contract.graph.ExecutionGraphZeroAllocTck;
import org.junit.jupiter.api.DisplayName;

/**
 * Community concrete TCK: {@link ExecutionGraphZeroAllocTck} backed by
 * {@link CommunityGraphEngine}.
 *
 * <h2>What this proves for Community tier</h2>
 * <p>The shortest-path hot path
 * {@code openSession() → findShortestPath(src, tgt) → close()} stays within the
 * bounded-allocation budget ({@code maxExerisAllocationsPerIteration() = 10} over
 * 1 000 iterations). The single-argument
 * {@link eu.exeris.kernel.spi.graph.GraphSession#findShortestPath(Object, Object)}
 * overload is fully in-process: distinct {@code src}/{@code tgt} resolve to
 * {@code PathResult.notFound(...)} without touching the persistence backend, so
 * this binding needs no database and runs in the main {@code build-and-verify} lane.
 *
 * @since 0.5.0
 */
@DisplayName("Community: ExecutionGraph zero-allocation TCK")
class CommunityExecutionGraphZeroAllocTckTest extends ExecutionGraphZeroAllocTck {

    @Override
    protected GraphEngine createEngine() {
        return new CommunityGraphProvider()
                .createEngine(GraphConfig.create("test-graph"));
    }
}
