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
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies CommunityPersistenceConfigResolver reads poolWarmupEnabled and poolWarmupConnections from properties.
 */
class CommunityPersistenceConfigResolverWarmupTest {


    @Test
    void readsWarmupConfigFromProperties() {
        ConfigProvider provider = stubProvider(Optional.of(false), Optional.of(5));
        PersistenceConfig config = CommunityPersistenceConfigResolver.buildPersistenceConfig(
                provider, 1000, 1000, 1000, 1, 1);
        assertFalse(config.poolWarmupEnabled());
        assertEquals(5, config.poolWarmupConnections());
        assertTrue(config.properties().containsKey("pool.warmup.enabled"));
        assertTrue(config.properties().containsKey("pool.warmup.connections"));
        assertTrue(config.properties().containsKey("run.migrations"));
        assertEquals("false", config.properties().get("pool.warmup.enabled"));
        assertEquals("5", config.properties().get("pool.warmup.connections"));
        assertEquals("false", config.properties().get("run.migrations"));
    }


    @Test
    void usesDefaultsWhenPropertiesAbsent() {
        ConfigProvider provider = stubProvider(Optional.empty(), Optional.empty());
        PersistenceConfig config = CommunityPersistenceConfigResolver.buildPersistenceConfig(
                provider, 1000, 1000, 1000, 1, 1);
        assertTrue(config.poolWarmupEnabled());
        assertEquals(2, config.poolWarmupConnections());
        assertTrue(config.properties().containsKey("pool.warmup.enabled"));
        assertTrue(config.properties().containsKey("pool.warmup.connections"));
        assertTrue(config.properties().containsKey("run.migrations"));
        assertEquals("true", config.properties().get("pool.warmup.enabled"));
        assertEquals("2", config.properties().get("pool.warmup.connections"));
        assertEquals("false", config.properties().get("run.migrations"));
    }

    @Test
    void defaultConnectionTimeoutIsAppliedWhenNotConfigured() {
        // Pins the DEFAULT_CONNECTION_TIMEOUT_MS = 250L regression:
        // CommunityPersistenceSubsystem passes 250L as defaultConnectionTimeoutMs.
        // When persistence.connectionTimeoutMs is absent from config, the default must be used.
        ConfigProvider provider = stubProvider(Optional.empty(), Optional.empty());
        PersistenceConfig config = CommunityPersistenceConfigResolver.buildPersistenceConfig(
                provider, 250L, 1000L, 1000L, 1, 1);
        assertEquals(250L, config.connectionTimeoutMs(),
                "connectionTimeoutMs must equal the 250ms fail-fast default when not configured");
    }

    private static ConfigProvider stubProvider(Optional<Boolean> warmupEnabled, Optional<Integer> warmupConnections) {
        return new ConfigProvider() {
            @Override public Optional<String> getString(String key) { return Optional.empty(); }
            @Override public Optional<Integer> getInt(String key) {
                if (key.equals("persistence.pool.warmup.connections")) {
                    return warmupConnections;
                }
                return Optional.empty();
            }
            @Override public Optional<Boolean> getBoolean(String key) {
                if (key.equals("persistence.pool.warmup.enabled")) {
                    return warmupEnabled;
                }
                if (key.equals("persistence.runMigrations")) {
                    return Optional.of(false);
                }
                return Optional.empty();
            }
            @Override public Optional<Long> getLong(String key) { return Optional.empty(); }
            @Override public <T> Optional<T> get(String key, Class<T> type) { return Optional.empty(); }
            @Override public Supplier<KernelSettings> kernelSettings() {
                return ConfigProvider.KernelSettings::defaults;
            }
            @Override public int priority() { return 0; }
            @Override public void watch(String file, String key, Consumer<Object> callback) {
                throw new UnsupportedOperationException("watch is not used in this test stub");
            }
            @Override public String providerName() { return "stub"; }
        };
    }
}