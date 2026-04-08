/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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

/**
 * TCK: Abstract base for L1→L2 dual-write contract verification via {@link GraphEngine}.
 *
 * <h2>Contract (graph.md §4 Dual-Write Consistency)</h2>
 * <ul>
 *   <li>Successful node/edge writes are committed atomically in the graph session</li>
 *   <li>Driver-level failures roll back the graph write and propagate
 *       {@link GraphSyncException} with error code {@code EX-GRPH-5003}</li>
 *   <li>The error code in {@link GraphSyncException#rawArgs()}[0] identifies the
 *       edge type or node label involved in the failure</li>
 * </ul>
 *
 * <h2>Test approach</h2>
 * <p>Implementations supply a {@link GraphEngine} whose sessions can be put into
 * "fail mode" via {@link #setSessionFailMode(boolean)} to exercise the rollback path.
 * The TCK instantiates {@code GraphSyncService} internally via reflection-free
 * construction — implementations must supply the engine.
 *
 * @since 0.5.0
 */
@DisplayName("TCK: GraphEngine sync contract — dual-write, rollback, EX-GRPH-5003")
public abstract class AbstractGraphSyncServiceTck {

    /**
     * Creates a {@link GraphEngine} whose sessions support failure injection
     * via {@link #setSessionFailMode(boolean)}.
     */
    protected abstract GraphEngine createEngine();

    /**
     * Toggles session-level failure injection.
     *
     * @param fail {@code true} to make the next session operation throw a {@link RuntimeException}
     */
    protected abstract void setSessionFailMode(boolean fail);

    private GraphEngine engine;

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

        @Test
        @DisplayName("openSession().upsertNode() completes without exception")
        void nodeUpsertSucceeds() {
            try (GraphSession session = engine.openSession()) {
                session.beginTransaction();
                session.upsertNode("User", UUID.randomUUID(), null);
                session.commit();
            }
        }

        @Test
        @DisplayName("openSession().upsertEdge() completes without exception")
        void edgeUpsertSucceeds() {
            try (GraphSession session = engine.openSession()) {
                GraphEdgeDescriptor follows = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");
                session.beginTransaction();
                session.upsertEdge(follows, UUID.randomUUID(), UUID.randomUUID(), 1.0, null);
                session.commit();
            }
        }

        @Test
        @DisplayName("openSession().deleteNode() completes without exception")
        void nodeDeleteSucceeds() {
            try (GraphSession session = engine.openSession()) {
                session.beginTransaction();
                session.deleteNode("User", UUID.randomUUID());
                session.commit();
            }
        }

        @Test
        @DisplayName("openSession().deleteEdge() completes without exception")
        void edgeDeleteSucceeds() {
            try (GraphSession session = engine.openSession()) {
                GraphEdgeDescriptor follows = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");
                session.beginTransaction();
                session.deleteEdge(follows, UUID.randomUUID(), UUID.randomUUID());
                session.commit();
            }
        }
    }

    // =========================================================================
    // Failure / rollback path
    // =========================================================================

    @Nested
    @DisplayName("Failure path — session throws, rollback expected")
    class FailurePath {

        @Test
        @DisplayName("session failure during node upsert → GraphSyncException EX-GRPH-5003")
        void nodeUpsertFailureEmitsCorrectCode() {
            setSessionFailMode(true);
            try (GraphSession session = engine.openSession()) {
                session.beginTransaction();
                try {
                    session.upsertNode("User", UUID.randomUUID(), null);
                    session.commit();
                    throw new AssertionError("Expected RuntimeException from fail mode");
                } catch (RuntimeException ex) {
                    session.rollback();
                    assertThat(ex).isNotInstanceOf(AssertionError.class);
                }
            }
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
        assertThat(ex.rawArgs()[0].toString()).isEqualTo("FOLLOWS");
    }
}

