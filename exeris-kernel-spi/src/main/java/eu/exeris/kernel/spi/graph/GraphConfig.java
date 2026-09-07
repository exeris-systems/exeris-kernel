/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.graph;

import java.util.Map;
import java.util.Objects;

/**
 * SPI: Immutable, implementation-blind configuration consumed by
 * {@link GraphProvider#createEngine(GraphConfig)} to build a {@link GraphEngine}.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>This is a {@code record} structured so it can be migrated to a {@code value record}
 * (JEP 401) once Valhalla is available in the target toolchain. No identity is required;
 * fields are primitives, {@code String}s, or the immutable {@code properties} map.
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
 * @since 0.5
 * @see GraphProvider
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
     * Rejects a {@code null} {@code backendType} and fills in {@code graphName} and
     * {@code properties} defaults when the caller passes {@code null} for either.
     *
     * @throws NullPointerException if {@code backendType} is {@code null}
     */
    public GraphConfig {
        Objects.requireNonNull(backendType, "backendType");
        graphName = graphName != null ? graphName : "exeris_graph";
        properties = properties != null ? Map.copyOf(properties) : Map.of();
    }

    /**
     * Returns the raw value of a backend-specific property, resolved from
     * {@link #properties()}.
     *
     * @param key property key; must not be {@code null}
     * @return the property value, or {@code null} if {@code key} is absent
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public String property(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return properties.get(key);
    }

    /**
     * Returns the value of a backend-specific property, or {@code defaultValue} if absent.
     *
     * @param key          property key; must not be {@code null}
     * @param defaultValue value to return if {@code key} is absent
     * @return the property value, or {@code defaultValue} if {@code key} is absent
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public String property(String key, String defaultValue) {
        Objects.requireNonNull(key, "key must not be null");
        return properties.getOrDefault(key, defaultValue);
    }

    /**
     * Returns a minimal config for {@code backendType} with caching, indexing, dual-write
     * sync and path-finding all disabled, and an empty properties map.
     *
     * @param backendType logical backend type (e.g. "postgresql", "neo4j")
     * @return a config with every feature flag disabled
     */
    public static GraphConfig create(String backendType) {
        return new GraphConfig(backendType, null, false, false, false, false, null);
    }
}


