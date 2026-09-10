/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.spi.exceptions.graph.GraphQueryException;
import eu.exeris.kernel.spi.graph.GraphConfig;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;

import java.util.Objects;

/**
 * Wraps the Neo4j Bolt driver connection used by the Cypher graph backend: resolves
 * connection settings from {@link GraphConfig} properties or environment variables at
 * construction, and opens one driver {@link Session} per {@link #openSession()} call.
 *
 * <p>Settings are looked up under {@code neo4j.uri}/{@code EXERIS_GRAPH_NEO4J_URI},
 * {@code neo4j.user}/{@code EXERIS_GRAPH_NEO4J_USER},
 * {@code neo4j.password}/{@code EXERIS_GRAPH_NEO4J_PASSWORD}, and the optional
 * {@code neo4j.database}/{@code EXERIS_GRAPH_NEO4J_DATABASE}; a {@link GraphConfig} property
 * takes precedence over its environment counterpart.
 *
 * <p><b>Allocation:</b> constructs one Neo4j {@code Driver} at construction time;
 * {@link #openSession()} asks that driver for one new {@code Session} per call.
 * <p><b>Thread confinement:</b> none enforced by this wrapper — {@link #openSession()} and
 * {@link #close()} call directly into the underlying driver without additional
 * synchronization.
 * <p><b>Ownership:</b> the constructing {@link CommunityGraphEngine} owns this client and
 * releases it via {@link #close()}, which closes the underlying driver.
 */
final class CommunityNeo4jClient implements AutoCloseable {

    private static final String QUERY_TYPE_CONFIGURATION = "CYPHER_CONFIGURATION";
    private static final String MISSING_SETTING_DETAIL =
            "Missing mandatory Neo4j setting '%s' (config key: %s, env: %s)";

    private final Driver driver;
    private final SessionConfig sessionConfig;

    /* default */ CommunityNeo4jClient(GraphConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        String uri = requireProperty(config, "neo4j.uri", "EXERIS_GRAPH_NEO4J_URI");
        String user = requireProperty(config, "neo4j.user", "EXERIS_GRAPH_NEO4J_USER");
        String password = requireProperty(config, "neo4j.password", "EXERIS_GRAPH_NEO4J_PASSWORD");
        String database = optionalProperty(config, "neo4j.database", "EXERIS_GRAPH_NEO4J_DATABASE");
        this.driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
        this.sessionConfig = buildSessionConfig(database);
    }

    /**
     * Opens a new driver session bound to the configured database (or the driver's default
     * database when {@code neo4j.database} was not set).
     *
     * @return a new Neo4j driver session; the caller closes it
     */
    /* default */ Session openSession() {
        return driver.session(sessionConfig);
    }

    /**
     * Closes the underlying Neo4j driver, releasing its connection pool.
     */
    @Override
    public void close() {
        driver.close();
    }

    private static SessionConfig buildSessionConfig(String database) {
        if (database == null || database.isBlank()) {
            return SessionConfig.defaultConfig();
        }
        return SessionConfig.builder().withDatabase(database).build();
    }

    /**
     * Checks whether {@code config} carries a Neo4j URI, either as the {@code neo4j.uri}
     * property or the {@code EXERIS_GRAPH_NEO4J_URI} environment variable.
     *
     * @param config graph subsystem configuration
     * @return {@code true} if a URI is present; {@code false} otherwise. Does not validate
     *         the other mandatory settings ({@code neo4j.user}, {@code neo4j.password}) —
     *         those are only checked when the constructor runs
     */
    /* default */ static boolean isConfigured(GraphConfig config) {
        return optionalProperty(config, "neo4j.uri", "EXERIS_GRAPH_NEO4J_URI") != null;
    }

    private static String requireProperty(GraphConfig config, String propertyKey, String envKey) {
        String value = optionalProperty(config, propertyKey, envKey);
        if (value == null || value.isBlank()) {
            throw new GraphQueryException(
                    QUERY_TYPE_CONFIGURATION,
                    MISSING_SETTING_DETAIL.formatted(propertyKey, propertyKey, envKey)
            );
        }
        return value;
    }

    private static String optionalProperty(GraphConfig config, String propertyKey, String envKey) {
        String value = config.properties().get(propertyKey);
        if (value != null && !value.isBlank()) {
            return value;
        }
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }
        return null;
    }
}