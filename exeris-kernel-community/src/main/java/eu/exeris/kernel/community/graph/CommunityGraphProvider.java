/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.spi.graph.GraphConfig;
import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.spi.graph.GraphProvider;

/**
 * Community Graph Provider — JDBC/Bolt based graph engine.
 *
 * <h2>Memory Model</h2>
 * <p>Uses {@code MemoryAllocator} → {@code LoanedBuffer} (Arena per request)
 * from {@code KernelProviders.MEMORY_ALLOCATOR}. No preallocated slab pools,
 * no raw pointers. Arenas are created and closed per operation lifecycle.
 *
 * <h2>Backend Support</h2>
 * <ul>
 *   <li><b>PostgreSQL:</b> SQL:2023 PGQ via JDBC + HikariCP (from PersistenceEngine)</li>
 *   <li><b>Neo4j:</b> Cypher via standard Bolt Java driver</li>
 * </ul>
 *
 * <h2>ServiceLoader Registration</h2>
 * <p>Registered via
 * {@code META-INF/services/eu.exeris.kernel.spi.graph.GraphProvider}.
 *
 * @since 0.5.0
 */
public final class CommunityGraphProvider implements GraphProvider {

    private static final String PROVIDER_ID = "graph-community";
    private static final String PROVIDER_NAME = "ExerisCommunity/JdbcGraph";

    @Override
    public GraphEngine createEngine(GraphConfig config) {
        return new CommunityGraphEngine(config);
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public int priority() {
        return 0;
    }
}

