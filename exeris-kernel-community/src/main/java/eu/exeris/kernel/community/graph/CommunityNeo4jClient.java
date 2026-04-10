/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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

    /* default */ Session openSession() {
        return driver.session(sessionConfig);
    }

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