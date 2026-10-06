/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.spi.graph.GraphConfig;
import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.spi.graph.GraphProvider;

/**
 * Community-tier {@link GraphProvider}: creates a JDBC/SQL-PGQ- or Neo4j-Bolt-backed
 * {@link CommunityGraphEngine}, selected via {@link GraphConfig#backendType()}, and
 * registers at the fixed {@link #priority()} {@code 0} so an Enterprise provider on the
 * classpath is always chosen instead.
 *
 * <h2>Memory Model</h2>
 * <p>Engines created by this provider obtain buffers via
 * {@code KernelProviders.MEMORY_ALLOCATOR}, which returns a {@code LoanedBuffer}; this
 * provider holds no allocator or buffer reference of its own and creates no engine-level
 * pool.
 *
 * <h2>Backend Support</h2>
 * <ul>
 *   <li><b>PostgreSQL:</b> SQL:2023 PGQ via JDBC, through the Community
 *       {@code PersistenceEngine}'s HikariCP-backed pool</li>
 *   <li><b>Neo4j:</b> Cypher via the standard Bolt Java driver</li>
 * </ul>
 *
 * <h2>ServiceLoader Registration</h2>
 * <p>Registered via
 * {@code META-INF/services/eu.exeris.kernel.spi.graph.GraphProvider}.
 *
 * @since 0.5
 */
public final class CommunityGraphProvider implements GraphProvider {

    private static final String PROVIDER_ID = "graph-community";
    private static final String PROVIDER_NAME = "ExerisCommunity/JdbcGraph";

    /**
     * Constructs the provider that {@link java.util.ServiceLoader} instantiates to resolve the
     * Community-tier {@link GraphProvider}, per this module's registration under
     * {@code META-INF/services/eu.exeris.kernel.spi.graph.GraphProvider}.
     */
    public CommunityGraphProvider() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Creates a {@link CommunityGraphEngine} for {@code config}.
     *
     * @param config graph subsystem configuration
     * @return a new engine instance; never {@code null}
     * @throws eu.exeris.kernel.spi.exceptions.graph.GraphQueryException ({@code EX-GRPH-5002})
     *         if {@code config} selects a Cypher-mode backend whose {@code neo4j.user} or
     *         {@code neo4j.password} setting is missing
     */
    @Override
    public GraphEngine createEngine(GraphConfig config) {
        return new CommunityGraphEngine(config);
    }

    /**
     * Returns {@code "graph-community"}.
     *
     * @return this provider's stable identifier
     */
    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    /**
     * Returns {@code "ExerisCommunity/JdbcGraph"}.
     *
     * @return this provider's display name
     */
    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    /**
     * Returns {@code 0}, the Community priority — an Enterprise provider (priority
     * {@code 100}) always wins when both are on the classpath.
     *
     * @return {@code 0}
     */
    @Override
    public int priority() {
        return 0;
    }
}

