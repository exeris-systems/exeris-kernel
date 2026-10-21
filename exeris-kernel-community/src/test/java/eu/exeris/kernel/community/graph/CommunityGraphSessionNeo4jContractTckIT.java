/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.security.StorageContext;
import eu.exeris.kernel.spi.graph.GraphConfig;
import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.tck.contract.graph.AbstractGraphSessionLiveContractTck;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Community binding of {@link AbstractGraphSessionLiveContractTck} against a live Neo4j
 * instance through the Cypher backend, via Testcontainers.
 *
 * <h2>Execution</h2>
 * <p>Tagged {@code integration} and excluded by default. Runs in the
 * {@code persistence-rls-gate} CI job, not in {@code build-and-verify}.
 *
 * @since 0.13
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Community: GraphSession contract — live Neo4j (Cypher backend)")
class CommunityGraphSessionNeo4jContractTckIT extends AbstractGraphSessionLiveContractTck {

    private static final String ADMIN_PASSWORD = "testpass";

    @Container
    static final Neo4jContainer<?> NEO4J = new Neo4jContainer<>("neo4j:5-community")
            .withAdminPassword(ADMIN_PASSWORD);

    private static MemoryAllocator allocator;
    private static CommunityGraphEngine graph;

    CommunityGraphSessionNeo4jContractTckIT() {
        super();
    }

    private static GraphConfig neo4jConfig() {
        return new GraphConfig("neo4j", "live_contract", false, false, false, false,
                Map.of(
                        "neo4j.uri", NEO4J.getBoltUrl(),
                        "neo4j.user", "neo4j",
                        "neo4j.password", ADMIN_PASSWORD
                ));
    }

    @BeforeAll
    static void startEngines() {
        allocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
        graph = new CommunityGraphEngine(neo4jConfig());
        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator).run(() -> {
            graph.registerNodes(nodeTypes());
            graph.registerEdges(edgeTypes());
        });
    }

    @AfterAll
    static void stopEngines() {
        if (graph != null) {
            graph.close();
        }
        if (allocator != null) {
            allocator.close();
        }
    }

    @Override
    protected GraphEngine engine() {
        return graph;
    }

    @Override
    protected <T> T callInScope(StorageContext ctx, Supplier<T> action) {
        return ScopedValue.where(KernelProviders.STORAGE_CONTEXT, ctx)
                .where(KernelProviders.MEMORY_ALLOCATOR, allocator)
                .call(action::get);
    }

    @Override
    protected Set<StorageContext.IsolationStrategy> scopableStrategies() {
        return EnumSet.noneOf(StorageContext.IsolationStrategy.class);
    }

    @Override
    protected List<StorageContext> tenantContexts(StorageContext.IsolationStrategy strategy) {
        throw new UnsupportedOperationException("Neo4j backend does not support tenant contexts");
    }
}
