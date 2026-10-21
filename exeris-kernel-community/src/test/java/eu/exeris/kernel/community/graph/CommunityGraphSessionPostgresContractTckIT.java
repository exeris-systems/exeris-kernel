/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.community.persistence.CommunityPersistenceProvider;
import eu.exeris.kernel.community.persistence.RlsConnectionInterceptor;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.graph.GraphConfig;
import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.security.StorageContext;
import eu.exeris.kernel.tck.contract.graph.AbstractGraphSessionLiveContractTck;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Community binding of {@link AbstractGraphSessionLiveContractTck} against a live PostgreSQL
 * instance through the SQL/PGQ backend, via Testcontainers.
 *
 * <h2>Execution</h2>
 * <p>Tagged {@code integration} and excluded by default. Runs in the
 * {@code persistence-rls-gate} CI job, not in {@code build-and-verify}.
 *
 * @since 0.13
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Community: GraphSession contract — live PostgreSQL (SQL/PGQ backend)")
class CommunityGraphSessionPostgresContractTckIT extends AbstractGraphSessionLiveContractTck {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private static MemoryAllocator allocator;
    private static PersistenceEngine persistence;
    private static CommunityGraphEngine graph;

    private static PersistenceConfig cfg(String url) {
        return new PersistenceConfig(
                url, POSTGRES.getUsername(), POSTGRES.getPassword(),
                4, 1, 5_000L, 60_000L, 600_000L, false, false, false, 0, Map.of(), Map.of());
    }

    private static void bootstrapDatabasesAndSchemas() {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE SCHEMA IF NOT EXISTS tck_graph_s1");
                stmt.execute("CREATE SCHEMA IF NOT EXISTS tck_graph_s2");
            }
            createDatabaseIfNotExists(conn, "tck_graph_d1");
            createDatabaseIfNotExists(conn, "tck_graph_d2");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to bootstrap PostgreSQL schemas and databases", e);
        }
    }

    private static void createDatabaseIfNotExists(Connection conn, String dbName) throws SQLException {
        boolean exists;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + dbName + "'")) {
            exists = rs.next();
        }
        if (!exists) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE DATABASE " + dbName);
            }
        }
    }

    @BeforeAll
    static void startEngines() {
        bootstrapDatabasesAndSchemas();
        allocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
        String host = POSTGRES.getHost();
        int port = POSTGRES.getMappedPort(5432);
        Map<String, PersistenceConfig> dedicated = Map.of(
                "tck-graph-ds1", cfg("jdbc:postgresql://" + host + ":" + port + "/tck_graph_d1"),
                "tck-graph-ds2", cfg("jdbc:postgresql://" + host + ":" + port + "/tck_graph_d2")
        );
        persistence = new CommunityPersistenceProvider().createEngine(new PersistenceConfig(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
                4, 1, 5_000L, 60_000L, 600_000L, false, false, false, 0, Map.of(), dedicated));
        persistence.registerInterceptor(RlsConnectionInterceptor.INSTANCE);
        graph = new CommunityGraphEngine(GraphConfig.create("postgresql"));
        ScopedValue.where(KernelProviders.PERSISTENCE_ENGINE, persistence)
                .where(KernelProviders.MEMORY_ALLOCATOR, allocator)
                .run(() -> {
                    graph.registerNodes(nodeTypes());
                    graph.registerEdges(edgeTypes());
                });

        CommunityGraphDialect dialect = (CommunityGraphDialect) graph.dialect();
        for (StorageContext ctx : List.of(
                ImmutableStorageContext.separatedSchema("tck-graph-s1", "tck_graph_s1"),
                ImmutableStorageContext.separatedSchema("tck-graph-s2", "tck_graph_s2"),
                ImmutableStorageContext.dedicated("tck-graph-d1", "tck-graph-ds1"),
                ImmutableStorageContext.dedicated("tck-graph-d2", "tck-graph-ds2"))) {
            ScopedValue.where(KernelProviders.PERSISTENCE_ENGINE, persistence)
                    .where(KernelProviders.STORAGE_CONTEXT, ctx)
                    .where(KernelProviders.MEMORY_ALLOCATOR, allocator)
                    .run(() -> {
                        try (PersistenceConnection c = persistence.openConnection(ctx)) {
                            c.executeUpdate(dialect.buildCreateNodeTable());
                            for (GraphEdgeDescriptor e : edgeTypes()) {
                                c.executeUpdate(dialect.buildCreateEdgeTable(e));
                            }
                        }
                    });
        }
    }

    @AfterAll
    static void stopEngines() {
        if (graph != null) {
            graph.close();
        }
        if (persistence != null) {
            persistence.close();
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
        return ScopedValue.where(KernelProviders.PERSISTENCE_ENGINE, persistence)
                .where(KernelProviders.STORAGE_CONTEXT, ctx)
                .where(KernelProviders.MEMORY_ALLOCATOR, allocator)
                .call(action::get);
    }

    @Override
    protected Set<StorageContext.IsolationStrategy> scopableStrategies() {
        return EnumSet.of(StorageContext.IsolationStrategy.SEPARATED_SCHEMA, StorageContext.IsolationStrategy.DEDICATED);
    }

    @Override
    protected List<StorageContext> tenantContexts(StorageContext.IsolationStrategy strategy) {
        return switch (strategy) {
            case SEPARATED_SCHEMA -> List.of(
                    ImmutableStorageContext.separatedSchema("tck-graph-s1", "tck_graph_s1"),
                    ImmutableStorageContext.separatedSchema("tck-graph-s2", "tck_graph_s2")
            );
            case DEDICATED -> List.of(
                    ImmutableStorageContext.dedicated("tck-graph-d1", "tck-graph-ds1"),
                    ImmutableStorageContext.dedicated("tck-graph-d2", "tck-graph-ds2")
            );
            default -> throw new IllegalArgumentException("Unsupported strategy: " + strategy);
        };
    }
}
