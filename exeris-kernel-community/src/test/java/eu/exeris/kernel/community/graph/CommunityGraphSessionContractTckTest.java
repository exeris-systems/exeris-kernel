/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.spi.graph.GraphConfig;
import eu.exeris.kernel.spi.graph.GraphSession;
import eu.exeris.kernel.tck.contract.graph.AbstractGraphSessionContractTck;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

/**
 * Community concrete TCK: {@link AbstractGraphSessionContractTck} bound for
 * both the SQL/PGQ backend and the Cypher (Neo4j) backend.
 *
 * <h2>Parity guarantee</h2>
 * <p>Both nested classes extend the same abstract TCK and must pass identical assertions.
 * This verifies that the same observable {@link eu.exeris.kernel.spi.graph.GraphSession}
 * contract is honoured regardless of backend.
 *
 * <h2>No live backend required</h2>
 * <p>The TCK exercises only same-node path shortcuts and session lifecycle operations
 * that are safe without a running database.
 *
 * @since 0.5.0
 */
@DisplayName("Community: GraphSession path contract — SQL and Cypher backends")
class CommunityGraphSessionContractTckTest {

    @Nested
    @DisplayName("SQL/PGQ backend")
    class SqlBackend extends AbstractGraphSessionContractTck {

        private CommunityGraphEngine engine;

        @BeforeEach
        void setUp() {
            engine = new CommunityGraphEngine(GraphConfig.create("postgresql"));
        }

        @AfterEach
        void tearDown() {
            engine.close();
        }

        @Override
        protected GraphSession createSession() {
            return engine.openSession();
        }
    }

    @Nested
    @DisplayName("Cypher (Neo4j) backend")
    class CypherBackend extends AbstractGraphSessionContractTck {

        private CommunityGraphEngine engine;

        @BeforeEach
        void setUp() {
            engine = new CommunityGraphEngine(GraphConfig.create("neo4j"));
        }

        @AfterEach
        void tearDown() {
            engine.close();
        }

        @Override
        protected GraphSession createSession() {
            return engine.openSession();
        }
    }
}
