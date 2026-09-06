/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.graph;

import eu.exeris.kernel.spi.graph.GraphSession;
import eu.exeris.kernel.spi.graph.model.PathResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
 *   <li>{@code findShortestPath(id, id)} — same-node shortcut (no DB call)</li>
 *   <li>{@code findShortestPath(a, b)} for unknown distinct nodes — returns notFound (no DB call)</li>
 *   <li>{@code close()} — idempotent lifecycle</li>
 * </ul>
 *
 * @since 0.5
 */
@DisplayName("TCK: GraphSession — path and lifecycle contract")
public abstract class AbstractGraphSessionContractTck {

    /**
     * Factory: creates a fresh, ready-to-use {@link GraphSession} for each test.
     *
     * @return a fresh graph session
     */
    protected abstract GraphSession createSession();

    @Test
    @DisplayName("findShortestPath(id, id) returns a found single-node path with zero hop count")
    void sameNodeShortestPathReturnsFoundSingleNodePath() {
        UUID id = UUID.randomUUID();
        try (GraphSession session = createSession()) {
            PathResult result = session.findShortestPath(id, id);
            assertThat(result.found()).isTrue();
            assertThat(result.path()).containsExactly(id);
            assertThat(result.hopCount()).isZero();
            assertThat(result.totalCost()).isEqualTo(0.0);
        }
    }

    @Test
    @DisplayName("findShortestPath(a, b) for unknown distinct nodes returns notFound")
    void unknownDistinctNodesShortestPathReturnsNotFound() {
        UUID src = UUID.randomUUID();
        UUID tgt = UUID.randomUUID();
        try (GraphSession session = createSession()) {
            PathResult result = session.findShortestPath(src, tgt);
            assertThat(result.found()).isFalse();
            assertThat(result.source()).isEqualTo(src);
            assertThat(result.target()).isEqualTo(tgt);
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
