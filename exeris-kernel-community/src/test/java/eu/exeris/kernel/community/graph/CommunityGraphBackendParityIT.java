/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.spi.graph.GraphConfig;
import eu.exeris.kernel.spi.graph.GraphDialect;
import eu.exeris.kernel.spi.graph.GraphSession;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphTraversal;
import eu.exeris.kernel.spi.graph.model.PathResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: live Cypher backend parity against a Neo4j Testcontainers instance.
 *
 * <h2>What this proves</h2>
 * <ul>
 *   <li>The Cypher backend can execute MERGE writes and read back traversal results</li>
 *   <li>Shortest-path resolution works end-to-end via Neo4j adjacency loading</li>
 *   <li>Both dialect implementations produce structurally valid non-blank queries
 *       for the same edge descriptor inputs (dialect parity)</li>
 * </ul>
 *
 * <h2>Execution</h2>
 * <p>Tagged {@code integration} and excluded by default. Run with:
 * <pre>mvn test -DincludedGroups=integration -DexcludedGroups=</pre>
 * <p>Skipped automatically when Docker is not available.
 *
 * @since 0.5.0
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Community: Graph backend parity — live Cypher operations (Testcontainers)")
class CommunityGraphBackendParityIT {

    private static final String ADMIN_PASSWORD = "testpass";

    @Container
    static final Neo4jContainer<?> NEO4J = new Neo4jContainer<>("neo4j:5-community")
            .withAdminPassword(ADMIN_PASSWORD);

    private static final GraphEdgeDescriptor FOLLOWS =
            GraphEdgeDescriptor.create("Person", "FOLLOWS", "Person");

    private GraphConfig neo4jConfig() {
        return new GraphConfig("neo4j", "parity_test", false, false, false, false,
                Map.of(
                        "neo4j.uri", NEO4J.getBoltUrl(),
                        "neo4j.user", "neo4j",
                        "neo4j.password", ADMIN_PASSWORD
                ));
    }

    // =========================================================================
    // Dialect parity (no live backend required)
    // =========================================================================

    @Test
    @DisplayName("both dialects produce non-blank MATCH queries for the same edge")
    void bothDialectsProduceNonBlankMatchQuery() {
        GraphDialect sqlDialect = new CommunityGraphDialect("parity_graph");
        GraphDialect cypherDialect = new CommunityGraphDialect("parity_graph", "neo4j");

        assertThat(sqlDialect.buildMatchQuery(FOLLOWS)).isNotBlank();
        assertThat(cypherDialect.buildMatchQuery(FOLLOWS)).isNotBlank();
        assertThat(sqlDialect.buildMultiHopQuery(FOLLOWS, 1, 3)).isNotBlank();
        assertThat(cypherDialect.buildMultiHopQuery(FOLLOWS, 1, 3)).isNotBlank();
        assertThat(sqlDialect.buildShortestPathQuery(FOLLOWS, 5)).isNotBlank();
        assertThat(cypherDialect.buildShortestPathQuery(FOLLOWS, 5)).isNotBlank();
        assertThat(sqlDialect.dialectName()).isNotEqualTo(cypherDialect.dialectName());
    }

    // =========================================================================
    // Live Cypher backend: traversal
    // =========================================================================

    @Test
    @DisplayName("Cypher session upserts an edge and BFS traversal finds the target node")
    void cypherSessionUpsertEdgeAndBfsFindsTarget() {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        try (CommunityGraphEngine engine = new CommunityGraphEngine(neo4jConfig());
             GraphSession session = engine.openSession()) {
            session.upsertEdge(FOLLOWS, source, target, 1.0, "{}");
            List<UUID> reachable = session.traverseBreadthFirst(
                    GraphTraversal.create(source, FOLLOWS, 1));
            assertThat(reachable).contains(target);
        }
    }

    // =========================================================================
    // Live Cypher backend: shortest path
    // =========================================================================

    @Test
    @DisplayName("Cypher session finds 1-hop shortest path after edge upsert")
    void cypherSessionFindsShortestPathAfterUpsert() {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        try (CommunityGraphEngine engine = new CommunityGraphEngine(neo4jConfig());
             GraphSession session = engine.openSession()) {
            session.upsertEdge(FOLLOWS, source, target, 1.0, "{}");
            PathResult result = session.findShortestPath(FOLLOWS, source, target);
            assertThat(result.found()).isTrue();
            assertThat(result.hopCount()).isEqualTo(1);
            assertThat(result.path()).containsExactly(source, target);
        }
    }

    // =========================================================================
    // Live Cypher backend: transaction lifecycle
    // =========================================================================

    @Test
    @DisplayName("Cypher session commit following upsertEdge persists readable edge")
    void cypherTransactionCommitPersistsEdge() {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        try (CommunityGraphEngine engine = new CommunityGraphEngine(neo4jConfig());
             GraphSession writeSession = engine.openSession()) {
            writeSession.beginTransaction();
            writeSession.upsertEdge(FOLLOWS, source, target, 2.0, "{}");
            writeSession.commit();
        }

        try (CommunityGraphEngine engine = new CommunityGraphEngine(neo4jConfig());
             GraphSession readSession = engine.openSession()) {
            PathResult result = readSession.findShortestPath(FOLLOWS, source, target);
            assertThat(result.found()).isTrue();
        }
    }
}
