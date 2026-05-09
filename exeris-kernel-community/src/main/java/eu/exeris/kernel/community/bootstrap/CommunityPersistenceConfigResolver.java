/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class CommunityPersistenceConfigResolver {

    private static final String SYS_PROP_PREFIX = "exeris.persistence.properties.";
    private static final String ENV_PROP_PREFIX = "EXERIS_PERSISTENCE_PROPERTIES_";
    private static final String MAX_POOL_SIZE_KEY = "persistence.maxPoolSize";
    private static final String MAX_POOL_SIZE_ALIAS_KEY = "persistence.pool.maxSize";
    private static final String MIN_IDLE_CONNECTIONS_KEY = "persistence.minIdleConnections";
    private static final String MIN_IDLE_CONNECTIONS_ALIAS_KEY = "persistence.pool.minSize";
    private static final int MIN_POOL_SIZE = 2;

    private CommunityPersistenceConfigResolver() {
    }

    /**
     * Compute adaptive pool size based on CPU count.
     * Formula: max(min(cores × 2, 32), 2)
     * Rationale: PostgreSQL best practice (2× cores), cap at 32 to prevent memory bloat,
     * floor at 2 for minimum contention resilience.
     *
     * @return computed adaptive pool size
     */
    private static int computeAdaptivePoolSize() {
        int cores = Runtime.getRuntime().availableProcessors();
        // cores * 2 capped at 32, minimum 2 — widen one operand so the multiplication
        // is performed in long arithmetic (java:S2184); the clamped result fits int.
        return (int) Math.clamp((long) cores * 2L, 2L, 32L);
    }

    /**
     * Resolve final pool size: property override > computed adaptive > fallback default.
     * Validation ensures pool size >= 2 for minimum contention resilience.
     *
     * @param configProvider configuration source
     * @return final maximumPoolSize for HikariCP
     */
    private static int resolveMaxPoolSize(ConfigProvider configProvider) {
        // Try explicit property first (highest priority)
        int resolved = configProvider.getInt(MAX_POOL_SIZE_KEY)
            .or(() -> configProvider.getInt(MAX_POOL_SIZE_ALIAS_KEY))
            .orElseGet(CommunityPersistenceConfigResolver::computeAdaptivePoolSize);
        
        // Validate floor
        if (resolved < MIN_POOL_SIZE) {
            throw new IllegalArgumentException(
                "Pool size must be >= " + MIN_POOL_SIZE + "; got: " + resolved
            );
        }
        return resolved;
    }

    /* default */ static PersistenceConfig buildPersistenceConfig(
            ConfigProvider configProvider,
            long defaultConnectionTimeoutMs,
            long defaultIdleTimeoutMs,
            long defaultMaxLifetimeMs,
            int defaultMinIdleConnections,
            int defaultMaxTenantPools) {
        ConfigProvider.PersistenceSettings defaults = configProvider.kernelSettings().get().persistence();
        int maxPoolSize = resolveMaxPoolSize(configProvider);
        int minIdleConnections = resolveIntSetting(configProvider,
            MIN_IDLE_CONNECTIONS_KEY,
            MIN_IDLE_CONNECTIONS_ALIAS_KEY,
            Math.min(defaultMinIdleConnections, maxPoolSize));
        Map<String, String> properties = new LinkedHashMap<>(resolvePersistenceProperties());

        // Resolve warm-up flags from config/system properties
        boolean poolWarmupEnabled = configProvider.getBoolean("persistence.pool.warmup.enabled")
            .orElse(true);  // default enabled
        int poolWarmupConnections = configProvider.getInt("persistence.pool.warmup.connections")
            .orElse(2);  // default 2, clamp to 1-8
        if (poolWarmupConnections < 1 || poolWarmupConnections > 8) {
            poolWarmupConnections = 2;  // fallback
        }
        boolean runMigrations = configProvider.getBoolean("persistence.runMigrations")
            .orElse(defaults.runMigrations());
        properties.put("pool.warmup.enabled", Boolean.toString(poolWarmupEnabled));
        properties.put("pool.warmup.connections", Integer.toString(poolWarmupConnections));
        properties.put("run.migrations", Boolean.toString(runMigrations));

        return new PersistenceConfig(
                configProvider.getString("persistence.jdbcUrl").orElse(defaults.jdbcUrl()),
                configProvider.getString("persistence.username").orElse(defaults.username()),
                configProvider.getString("persistence.password").orElse(defaults.password()),
                maxPoolSize,
                minIdleConnections,
                configProvider.getLong("persistence.connectionTimeoutMs").orElse(defaultConnectionTimeoutMs),
                configProvider.getLong("persistence.idleTimeoutMs").orElse(defaultIdleTimeoutMs),
                configProvider.getLong("persistence.maxLifetimeMs").orElse(defaultMaxLifetimeMs),
                configProvider.getBoolean("persistence.rlsEnabled").orElse(false),
                configProvider.getBoolean("persistence.perTenantPooling").orElse(false),
                configProvider.getBoolean("persistence.useTls").orElse(false),
                configProvider.getInt("persistence.maxTenantPools").orElse(defaultMaxTenantPools),
                properties
        );
    }

    private static int resolveIntSetting(ConfigProvider configProvider,
                                         String canonicalKey,
                                         String aliasKey,
                                         int fallbackValue) {
        return configProvider.getInt(canonicalKey)
                .or(() -> configProvider.getInt(aliasKey))
                .orElse(fallbackValue);
    }

    /**
     * Resolves persistence properties from system properties and environment variables.
     *
     * <p>System properties must use the {@code exeris.persistence.properties.} prefix;
     * environment variables must use the {@code EXERIS_PERSISTENCE_PROPERTIES_} prefix.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code System.setProperty("exeris.persistence.properties.maxPoolSize", "8")}</li>
     *   <li>{@code EXERIS_PERSISTENCE_PROPERTIES_MAXPOOLSIZE=8}</li>
     *   <li>{@code EXERIS_PERSISTENCE_PROPERTIES_MAX_POOL_SIZE=8}</li>
     * </ul>
     */
    /* default */ static Map<String, String> resolvePersistenceProperties() {
        Map<String, String> properties = new LinkedHashMap<>();

        for (String propertyName : System.getProperties().stringPropertyNames()) {
            addSystemProperty(properties, propertyName);
        }

        for (Map.Entry<String, String> env : System.getenv().entrySet()) {
            addEnvironmentProperty(properties, env);
        }

        return Map.copyOf(properties);
    }

    private static void addSystemProperty(Map<String, String> properties, String propertyName) {
        if (!propertyName.startsWith(SYS_PROP_PREFIX)) {
            return;
        }
        String key = propertyName.substring(SYS_PROP_PREFIX.length());
        if (key.isBlank()) {
            return;
        }
        String value = System.getProperty(propertyName);
        if (value != null && !value.isBlank()) {
            properties.put(key, value);
        }
    }

    private static void addEnvironmentProperty(Map<String, String> properties, Map.Entry<String, String> env) {
        String key = env.getKey();
        if (!key.startsWith(ENV_PROP_PREFIX)) {
            return;
        }
        String suffix = key.substring(ENV_PROP_PREFIX.length());
        if (suffix.isBlank()) {
            return;
        }
        String value = env.getValue();
        if (value == null || value.isBlank()) {
            return;
        }
        String normalized = suffix.toLowerCase(Locale.ROOT);
        properties.putIfAbsent(normalized, value);
        properties.putIfAbsent(normalized.replace('_', '.'), value);
    }
}