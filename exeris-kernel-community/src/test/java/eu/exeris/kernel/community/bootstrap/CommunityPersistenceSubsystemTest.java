/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.community.config.CommunityConfigProvider;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CommunityPersistenceSubsystem persistence config mapping")
class CommunityPersistenceSubsystemTest {

    @SuppressWarnings("unused")
    @AfterEach
    void cleanup() {
        System.clearProperty("exeris.persistence.properties.applicationName");
        System.clearProperty("exeris.persistence.properties.sslmode");
        System.clearProperty("exeris.persistence.pool.maxSize");
        System.clearProperty("exeris.persistence.pool.minSize");
        System.clearProperty("exeris.persistence.maxPoolSize");
        System.clearProperty("exeris.persistence.minIdleConnections");
    }

    @Test
    @DisplayName("buildPersistenceConfig propagates exeris.persistence.properties.* into PersistenceConfig")
    void buildPersistenceConfigPropagatesPropertiesMap() {
        System.setProperty("exeris.persistence.properties.applicationName", "exeris-community-tests");
        System.setProperty("exeris.persistence.properties.sslmode", "verify-full");

        PersistenceConfig config = CommunityPersistenceConfigResolver.buildPersistenceConfig(
            new StubConfigProvider(),
            5_000L,
            600_000L,
            1_800_000L,
            1,
            32);

        assertThat(config.properties())
                .containsEntry("applicationName", "exeris-community-tests")
                .containsEntry("sslmode", "verify-full");
    }

    @Test
    @DisplayName("buildPersistenceConfig resolves benchmark-style persistence.pool aliases")
    void buildPersistenceConfigResolvesPoolAliasesWithRealProvider() {
        System.setProperty("exeris.persistence.pool.maxSize", "19");
        System.setProperty("exeris.persistence.pool.minSize", "7");

        PersistenceConfig config = CommunityPersistenceConfigResolver.buildPersistenceConfig(
                new CommunityConfigProvider(),
                5_000L,
                600_000L,
                1_800_000L,
                1,
                32);

        assertThat(config.maxPoolSize()).isEqualTo(19);
        assertThat(config.minIdleConnections()).isEqualTo(7);
    }

    private static final class StubConfigProvider implements ConfigProvider {

        @Override
        public Supplier<KernelSettings> kernelSettings() {
            return KernelSettings::defaults;
        }

        @Override
        public Optional<String> getString(String key) {
            return switch (key) {
                case "persistence.jdbcUrl" -> Optional.of(
                        "jdbc:h2:mem:community_subsystem_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
                case "persistence.username" -> Optional.of("sa");
                case "persistence.password" -> Optional.of("");
                default -> Optional.empty();
            };
        }

        @Override
        public Optional<Integer> getInt(String key) {
            if ("persistence.maxPoolSize".equals(key)) {
                return Optional.of(4);
            }
            if ("persistence.minIdleConnections".equals(key)) {
                return Optional.of(1);
            }
            if ("persistence.maxTenantPools".equals(key)) {
                return Optional.of(8);
            }
            return Optional.empty();
        }

        @Override
        public Optional<Long> getLong(String key) {
            return Optional.empty();
        }

        @Override
        public Optional<Boolean> getBoolean(String key) {
            if ("persistence.useTls".equals(key)) {
                return Optional.of(true);
            }
            return Optional.empty();
        }

        @Override
        public <T> Optional<T> get(String key, Class<T> type) {
            return Optional.empty();
        }

        @Override
        public void watch(String file, String key, Consumer<Object> callback) {
            // no-op for tests
        }
    }
}
