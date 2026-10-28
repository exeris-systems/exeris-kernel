/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.spi.graph.GraphConfig;
import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.tck.contract.graph.ExecutionGraphZeroAllocTck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.neo4j.Neo4jContainer;

import java.util.Map;

/**
 * Community concrete TCK: {@link ExecutionGraphZeroAllocTck} backed by a live Neo4j
 * Cypher backend via Testcontainers.
 *
 * <h2>Execution</h2>
 * <p>Tagged {@code integration} and excluded by default. Runs in the
 * {@code persistence-rls-gate} CI job, not in {@code build-and-verify}.
 *
 * @since 0.13
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Community: ExecutionGraph zero-allocation TCK (live Neo4j)")
class CommunityExecutionGraphZeroAllocTckIT extends ExecutionGraphZeroAllocTck {

    private static final String ADMIN_PASSWORD = "testpass";

    @Container
    static final Neo4jContainer NEO4J = new Neo4jContainer("neo4j:5-community")
            .withAdminPassword(ADMIN_PASSWORD);

    private static GraphConfig neo4jConfig() {
        return new GraphConfig("neo4j", "zero_alloc_test", false, false, false, false,
                Map.of(
                        "neo4j.uri", NEO4J.getBoltUrl(),
                        "neo4j.user", "neo4j",
                        "neo4j.password", ADMIN_PASSWORD
                ));
    }

    @Override
    protected GraphEngine createEngine() {
        return new CommunityGraphEngine(neo4jConfig());
    }
}
