/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.graph;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.graph.GraphSyncException;
import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.spi.graph.GraphSession;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * TCK: Abstract base for L1→L2 dual-write contract verification via {@link GraphEngine}.
 *
 * <h2>What this TCK asserts</h2>
 * <ul>
 *   <li>Node and edge upsert/delete complete without exception across a full
 *       {@code beginTransaction()}/{@code commit()} block when the session is healthy</li>
 *   <li>When {@link #setSessionFailMode(boolean)} is armed, the write operation or its
 *       {@code commit()} throws — caught here as a generic {@link RuntimeException}, the
 *       only type {@link #setSessionFailMode(boolean)} promises — and the session's
 *       {@link GraphSession#rollback()} can then be invoked without itself throwing</li>
 *   <li>{@link GraphSyncException#errorCode()} reports {@code EX-GRPH-5003} and
 *       {@link GraphSyncException#rawArgs()}[0] carries the edge type or node label passed
 *       to its constructor</li>
 * </ul>
 *
 * <p>The last bullet is asserted against a directly-constructed {@link GraphSyncException},
 * not one thrown by a session under fail mode: this TCK does not assert that a driver's own
 * fail-mode failure is specifically a {@link GraphSyncException}, or that its code is
 * {@code EX-GRPH-5003} — the session-level failure/rollback contract and the exception
 * type's own accessor contract are verified independently of each other.
 *
 * <h2>Test approach</h2>
 * <p>Implementations supply a {@link GraphEngine} whose sessions can be put into
 * "fail mode" via {@link #setSessionFailMode(boolean)} to exercise the rollback path.
 * Tests operate directly on {@link GraphSession} to verify session-level write, rollback,
 * and failure-signalling behaviour.
 *
 * @since 0.5
 */
@DisplayName("TCK: GraphSession contract — dual-write, rollback, session-level operations")
public abstract class AbstractGraphSyncServiceTck {

    /**
     * Creates a {@link GraphEngine} whose sessions support failure injection
     * via {@link #setSessionFailMode(boolean)}.
     *
     * @return a graph engine whose sessions can be switched into failure mode
     */
    protected abstract GraphEngine createEngine();

    /**
     * Toggles session-level failure injection.
     *
     * @param fail {@code true} to make the next session operation throw a {@link RuntimeException}
     */
    protected abstract void setSessionFailMode(boolean fail);

    private GraphEngine engine;

    /**
     * Creates the contract; subclasses supply the {@link GraphEngine} under test via {@link #createEngine()}
     * and wire failure injection through {@link #setSessionFailMode(boolean)}.
     */
    public AbstractGraphSyncServiceTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    @BeforeEach
    final void setUpEngine() {
        engine = createEngine();
        setSessionFailMode(false);
    }

    @AfterEach
    final void tearDownEngine() {
        if (engine != null) engine.close();
    }

    // =========================================================================
    // Happy path — direct session API
    // =========================================================================

    @Nested
    @DisplayName("Happy path — session-level CRUD succeeds")
    class HappyPath {

        /**
         * Groups the assertions for the happy path, where session-level CRUD succeeds.
         */
        HappyPath() {
            // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
            super();
        }

        @Test
        @DisplayName("openSession().upsertNode() completes without exception")
        void nodeUpsertSucceeds() {
            assertThatCode(() -> {
                try (GraphSession session = engine.openSession()) {
                    session.beginTransaction();
                    session.upsertNode("User", UUID.randomUUID(), null);
                    session.commit();
                }
            })
                    .as("Node upsert happy path must complete without throwing across the full transaction block")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("openSession().upsertEdge() completes without exception")
        void edgeUpsertSucceeds() {
            assertThatCode(() -> {
                try (GraphSession session = engine.openSession()) {
                    GraphEdgeDescriptor follows = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");
                    session.beginTransaction();
                    session.upsertEdge(follows, UUID.randomUUID(), UUID.randomUUID(), 1.0, null);
                    session.commit();
                }
            })
                    .as("Edge upsert happy path must complete without throwing across the full transaction block")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("openSession().deleteNode() completes without exception")
        void nodeDeleteSucceeds() {
            assertThatCode(() -> {
                try (GraphSession session = engine.openSession()) {
                    session.beginTransaction();
                    session.deleteNode("User", UUID.randomUUID());
                    session.commit();
                }
            })
                    .as("Node delete happy path must complete without throwing across the full transaction block")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("openSession().deleteEdge() completes without exception")
        void edgeDeleteSucceeds() {
            assertThatCode(() -> {
                try (GraphSession session = engine.openSession()) {
                    GraphEdgeDescriptor follows = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");
                    session.beginTransaction();
                    session.deleteEdge(follows, UUID.randomUUID(), UUID.randomUUID());
                    session.commit();
                }
            })
                    .as("Edge delete happy path must complete without throwing across the full transaction block")
                    .doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // Failure / rollback path
    // =========================================================================

    @Nested
    @DisplayName("Failure path — session throws, rollback expected")
    class FailurePath {

        /**
         * Groups the assertions for the failure path, where the session throws and rollback is expected.
         */
        FailurePath() {
            // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
            super();
        }

        @Test
        @DisplayName("session failure during node upsert \u2192 rollback is called")
        void nodeUpsertFailureRollsBack() {
            setSessionFailMode(true);
            AtomicBoolean rolledBack = new AtomicBoolean(false);
            try (GraphSession session = engine.openSession()) {
                session.beginTransaction();
                try {
                    session.upsertNode("User", UUID.randomUUID(), null);
                    session.commit();
                } catch (RuntimeException _) {
                    session.rollback();
                    rolledBack.set(true);
                }
            }
            assertThat(rolledBack.get()).isTrue();
        }

        @Test
        @DisplayName("session failure during edge upsert → rollback is called")
        void edgeUpsertFailureRollsBack() {
            setSessionFailMode(true);
            AtomicBoolean rolledBack = new AtomicBoolean(false);
            GraphEdgeDescriptor follows = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");
            try (GraphSession session = engine.openSession()) {
                session.beginTransaction();
                try {
                    session.upsertEdge(follows, UUID.randomUUID(), UUID.randomUUID(), 1.0, null);
                    session.commit();
                } catch (RuntimeException _) {
                    session.rollback();
                    rolledBack.set(true);
                }
            }
            assertThat(rolledBack.get()).isTrue();
        }

        @Test
        @DisplayName("session failure during node delete → rollback is called")
        void nodeDeleteFailureRollsBack() {
            setSessionFailMode(true);
            AtomicBoolean rolledBack = new AtomicBoolean(false);
            try (GraphSession session = engine.openSession()) {
                session.beginTransaction();
                try {
                    session.deleteNode("User", UUID.randomUUID());
                    session.commit();
                } catch (RuntimeException _) {
                    session.rollback();
                    rolledBack.set(true);
                }
            }
            assertThat(rolledBack.get()).isTrue();
        }

        @Test
        @DisplayName("session failure during edge delete → rollback is called")
        void edgeDeleteFailureRollsBack() {
            setSessionFailMode(true);
            AtomicBoolean rolledBack = new AtomicBoolean(false);
            GraphEdgeDescriptor follows = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");
            try (GraphSession session = engine.openSession()) {
                session.beginTransaction();
                try {
                    session.deleteEdge(follows, UUID.randomUUID(), UUID.randomUUID());
                    session.commit();
                } catch (RuntimeException _) {
                    session.rollback();
                    rolledBack.set(true);
                }
            }
            assertThat(rolledBack.get()).isTrue();
        }
    }

    // =========================================================================
    // GraphSyncException error contract
    // =========================================================================

    @Test
    @DisplayName("GraphSyncException.errorCode() equals EX-GRPH-5003")
    void syncExceptionErrorCode() {
        GraphSyncException ex = new GraphSyncException("FOLLOWS", "test failure");
        assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_GRPH_5003);
    }

    @Test
    @DisplayName("GraphSyncException.rawArgs()[0] carries the edge type / label")
    void syncExceptionRawArgsEdgeType() {
        GraphSyncException ex = new GraphSyncException("FOLLOWS", "detail");
        assertThat(ex.rawArgs()[0]).hasToString("FOLLOWS");
    }
}

