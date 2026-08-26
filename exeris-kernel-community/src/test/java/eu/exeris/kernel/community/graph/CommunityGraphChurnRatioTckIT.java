/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.spi.graph.GraphConfig;
import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.spi.graph.GraphSession;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.tck.contract.graph.GraphChurnRatioTck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

/**
 * Community concrete TCK: {@link GraphChurnRatioTck} backed by a live Neo4j
 * Cypher backend via Testcontainers.
 *
 * <h2>What this proves for Community tier</h2>
 * <p>The Community traversal hot path
 * {@code openSession() → traverseBreadthFirst(1-hop) → close()} stays below the
 * documented churn-to-data SLO ({@code < 20x} of {@code eu.exeris.*} allocated
 * bytes per useful data byte). The Cypher backend is used deliberately: it builds
 * its helper from an explicit {@link CommunityNeo4jClient} passed via
 * {@link GraphConfig}, so no {@code ScopedValue}-bound persistence engine is
 * required to drive the traversal (unlike the SQL/PGQ path).
 *
 * <h2>Execution</h2>
 * <p>Tagged {@code integration} and excluded by default. Runs in the
 * {@code persistence-rls-gate} CI job, which executes all community
 * integration-tagged tests. Run locally with:
 * <pre>mvn -pl exeris-kernel-community -DincludedGroups=integration -DexcludedGroups= test</pre>
 * <p>Skipped automatically when Docker is not available.
 *
 * @since 0.5.0
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Community: Graph churn-to-data ratio TCK (live Neo4j)")
class CommunityGraphChurnRatioTckIT extends GraphChurnRatioTck {

    private static final String ADMIN_PASSWORD = "testpass";

    @Container
    static final Neo4jContainer<?> NEO4J = new Neo4jContainer<>("neo4j:5-community")
            .withAdminPassword(ADMIN_PASSWORD);

    private static final GraphEdgeDescriptor FOLLOWS =
            GraphEdgeDescriptor.create("Person", "FOLLOWS", "Person");

    private final UUID startNode = UUID.randomUUID();

    private GraphConfig neo4jConfig() {
        return new GraphConfig("neo4j", "churn_ratio_test", false, false, false, false,
                Map.of(
                        "neo4j.uri", NEO4J.getBoltUrl(),
                        "neo4j.user", "neo4j",
                        "neo4j.password", ADMIN_PASSWORD
                ));
    }

    @Override
    protected GraphEngine createEngine() {
        CommunityGraphEngine engine = new CommunityGraphEngine(neo4jConfig());
        // Seed one edge startNode -> target so the measured 1-hop BFS returns a node.
        try (GraphSession session = engine.openSession()) {
            session.upsertEdge(FOLLOWS, startNode, UUID.randomUUID(), 1.0, "{}");
        }
        return engine;
    }

    @Override
    protected UUID startNodeId() {
        return startNode;
    }

    @Override
    protected GraphEdgeDescriptor testEdge() {
        return FOLLOWS;
    }
}
