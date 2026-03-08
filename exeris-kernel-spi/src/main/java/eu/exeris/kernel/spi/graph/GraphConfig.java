/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.graph;

import java.util.Map;
import java.util.Objects;

/**
 * Valhalla-Ready: Immutable configuration for the Graph subsystem.
 *
 * <p>Will be migrated to {@code value record} once JEP 401 is mainline.
 * Avoid identity operations.
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>This config is <strong>implementation-blind</strong>: it does not reference JDBC,
 * Neo4j Bolt, Redis, or any backend-specific concept. Backend-specific properties
 * are passed via the opaque {@link #properties()} map, resolved by the provider.
 *
 * @param backendType     logical backend type (e.g. "postgresql", "neo4j", "memgraph")
 * @param graphName       the property graph name (e.g. "exeris_graph")
 * @param cachingEnabled  whether query result caching is enabled
 * @param indexingEnabled whether automatic index management is enabled
 * @param syncEnabled     whether dual-write sync is enabled
 * @param pathFinderEnabled whether shortest-path algorithms are enabled
 * @param properties      opaque backend-specific configuration map
 *
 * @since 0.5.0
 */
public record GraphConfig(
        String backendType,
        String graphName,
        boolean cachingEnabled,
        boolean indexingEnabled,
        boolean syncEnabled,
        boolean pathFinderEnabled,
        Map<String, String> properties
) {
    /**
     * Compact constructor with defaults.
     */
    public GraphConfig {
        Objects.requireNonNull(backendType, "backendType");
        graphName = graphName != null ? graphName : "exeris_graph";
        properties = properties != null ? Map.copyOf(properties) : Map.of();
    }

    /**
     * Gets a backend-specific property.
     *
     * @param key property key; must not be {@code null}
     * @return property value or {@code null} if absent
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public String property(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return properties.get(key);
    }

    /**
     * Gets a backend-specific property with default.
     *
     * @param key          property key; must not be {@code null}
     * @param defaultValue value if key is absent
     * @return property value or default
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public String property(String key, String defaultValue) {
        Objects.requireNonNull(key, "key must not be null");
        return properties.getOrDefault(key, defaultValue);
    }

    /**
     * Quick factory for minimal config.
     *
     * @param backendType backend type
     * @return config with all features disabled
     */
    public static GraphConfig create(String backendType) {
        return new GraphConfig(backendType, null, false, false, false, false, null);
    }
}


