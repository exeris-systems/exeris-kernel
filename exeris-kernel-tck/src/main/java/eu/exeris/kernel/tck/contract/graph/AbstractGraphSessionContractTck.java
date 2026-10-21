/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.graph;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.graph.GraphQueryException;
import eu.exeris.kernel.spi.graph.GraphSession;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.PathResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK: Observable contract for {@link GraphSession} path resolution and session lifecycle.
 *
 * <h2>Purpose</h2>
 * <p>Every {@code GraphSession} implementation (SQL/PGQ, Cypher, Enterprise FFM-Bolt)
 * MUST pass these assertions to prove structural correctness independent of backend.
 *
 * <h2>Safe test scope</h2>
 * <p>Only calls methods whose behaviour is guaranteed without a live backend:
 * <ul>
 *   <li>{@code findShortestPath(edge, id, id)} — same-node shortcut (no DB call)</li>
 *   <li>{@code findShortestPath(a, b)} for distinct nodes — refuses with EX-GRPH-5002</li>
 *   <li>{@code findShortestPath(id, id)} for equal nodes — refuses with EX-GRPH-5002</li>
 *   <li>{@code close()} — idempotent lifecycle</li>
 * </ul>
 *
 * @since 0.5
 */
@DisplayName("TCK: GraphSession — path and lifecycle contract")
public abstract class AbstractGraphSessionContractTck {

    /**
     * Creates the contract; subclasses supply a fresh {@link GraphSession} via {@link #createSession()}.
     */
    public AbstractGraphSessionContractTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Factory: creates a fresh, ready-to-use {@link GraphSession} for each test.
     *
     * @return a fresh graph session
     */
    protected abstract GraphSession createSession();

    @Test
    @DisplayName("findShortestPath(edge, id, id) returns a found single-node path with zero hop count")
    void sameNodeThreeArgShortestPathReturnsFoundSingleNodePath() {
        GraphEdgeDescriptor edge = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");
        UUID id = UUID.randomUUID();
        try (GraphSession session = createSession()) {
            PathResult result = session.findShortestPath(edge, id, id);
            assertThat(result.found()).isTrue();
            assertThat(result.path()).containsExactly(id);
            assertThat(result.hopCount()).isZero();
            assertThat(result.totalCost()).isEqualTo(0.0);
        }
    }

    @Test
    @SuppressWarnings("removal")
    @DisplayName("findShortestPath(a, b) for distinct nodes refuses and throws GraphQueryException")
    void distinctNodesShortestPathRefuses() {
        UUID src = UUID.randomUUID();
        UUID tgt = UUID.randomUUID();
        try (GraphSession session = createSession()) {
            assertThatThrownBy(() -> session.findShortestPath(src, tgt))
                    .isInstanceOf(GraphQueryException.class)
                    .satisfies(ex -> {
                        GraphQueryException gqe = (GraphQueryException) ex;
                        assertThat(gqe.errorCode()).isEqualTo(KernelErrorCodes.EX_GRPH_5002);
                        assertThat(gqe.rawArgs()[0]).isEqualTo("SHORTEST_PATH");
                        assertThat(String.valueOf(gqe.rawArgs()[1]))
                                .contains("findShortestPath(GraphEdgeDescriptor, UUID, UUID)");
                    });
        }
    }

    @Test
    @SuppressWarnings("removal")
    @DisplayName("findShortestPath(id, id) for equal nodes refuses and throws GraphQueryException")
    void sameNodeTwoArgShortestPathRefuses() {
        UUID id = UUID.randomUUID();
        try (GraphSession session = createSession()) {
            assertThatThrownBy(() -> session.findShortestPath(id, id))
                    .isInstanceOf(GraphQueryException.class)
                    .satisfies(ex -> {
                        GraphQueryException gqe = (GraphQueryException) ex;
                        assertThat(gqe.errorCode()).isEqualTo(KernelErrorCodes.EX_GRPH_5002);
                        assertThat(gqe.rawArgs()[0]).isEqualTo("SHORTEST_PATH");
                        assertThat(String.valueOf(gqe.rawArgs()[1]))
                                .contains("findShortestPath(GraphEdgeDescriptor, UUID, UUID)");
                    });
        }
    }

    @Test
    @DisplayName("close() is idempotent — double close does not throw")
    void closeIsIdempotent() {
        GraphSession session = createSession();
        assertThatCode(() -> {
            session.close();
            session.close();
        }).doesNotThrowAnyException();
    }
}
