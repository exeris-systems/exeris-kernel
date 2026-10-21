/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.spi.exceptions.graph.GraphQueryException;
import eu.exeris.kernel.spi.graph.GraphConfig;
import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.spi.graph.GraphSession;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphHop;
import eu.exeris.kernel.spi.graph.model.GraphPathSpec;
import eu.exeris.kernel.spi.graph.model.GraphTraversal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 Unit: {@link CommunityGraphSession} path limit guard.
 *
 * <p>Verifies that traversals whose path length bound exceeds the Community limit (64)
 * are refused before any I/O with {@code PATH_LIMIT}.
 *
 * @since 0.13
 */
@DisplayName("L1 Unit: CommunityGraphSession path limit guard")
class CommunityGraphSessionGuardTest {

    private GraphEngine engine;

    CommunityGraphSessionGuardTest() {
        super();
    }

    @BeforeEach
    void setUp() {
        engine = new CommunityGraphEngine(GraphConfig.create("postgresql"));
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    @Test
    @DisplayName("traversePath with hops summing to 65 throws PATH_LIMIT")
    void traversePathExceedingLimitThrowsPathLimit() {
        try (GraphSession session = engine.openSession()) {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("Product", "LINKED", "Product");
            UUID start = UUID.randomUUID();
            GraphPathSpec path = new GraphPathSpec(start, List.of(new GraphHop(edge, 1, 65)));

            assertThatThrownBy(() -> session.traversePath(path))
                    .isInstanceOf(GraphQueryException.class)
                    .satisfies(ex -> {
                        GraphQueryException gqe = (GraphQueryException) ex;
                        assertThat(gqe.rawArgs()[0]).isEqualTo("PATH_LIMIT");
                    });
        }
    }

    @Test
    @DisplayName("traversePath with hops summing to 64 passes limit guard and fails on missing persistence")
    void traversePathAtLimitPassesGuard() {
        try (GraphSession session = engine.openSession()) {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("Product", "LINKED", "Product");
            UUID start = UUID.randomUUID();
            GraphPathSpec path = new GraphPathSpec(start, List.of(new GraphHop(edge, 1, 64)));

            assertThatThrownBy(() -> session.traversePath(path))
                    .isInstanceOf(GraphQueryException.class)
                    .satisfies(ex -> {
                        GraphQueryException gqe = (GraphQueryException) ex;
                        assertThat(gqe.rawArgs()[0]).isNotEqualTo("PATH_LIMIT");
                    });
        }
    }

    @Test
    @DisplayName("traverseBreadthFirst with depth 65 throws PATH_LIMIT")
    void traverseBreadthFirstExceedingLimitThrowsPathLimit() {
        try (GraphSession session = engine.openSession()) {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("Product", "LINKED", "Product");
            UUID start = UUID.randomUUID();
            GraphTraversal traversal = GraphTraversal.create(start, edge, 65);

            assertThatThrownBy(() -> session.traverseBreadthFirst(traversal))
                    .isInstanceOf(GraphQueryException.class)
                    .satisfies(ex -> {
                        GraphQueryException gqe = (GraphQueryException) ex;
                        assertThat(gqe.rawArgs()[0]).isEqualTo("PATH_LIMIT");
                    });
        }
    }

    @Test
    @DisplayName("traversePath with a hop bound of Integer.MAX_VALUE after another hop throws PATH_LIMIT")
    void traversePathSumOverflowingIntThrowsPathLimit() {
        try (GraphSession session = engine.openSession()) {
            GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("Product", "LINKED", "Product");
            GraphPathSpec path = new GraphPathSpec(UUID.randomUUID(),
                    List.of(GraphHop.of(edge), new GraphHop(edge, 1, Integer.MAX_VALUE)));

            assertThatThrownBy(() -> session.traversePath(path))
                    .isInstanceOf(GraphQueryException.class)
                    .satisfies(ex -> assertThat(((GraphQueryException) ex).rawArgs()[0]).isEqualTo("PATH_LIMIT"));
        }
    }
}
