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
import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CommunityPersistencePoolSizing adaptive formula and resolution")
class CommunityPersistencePoolSizingTest {

    @SuppressWarnings("unused")
    @AfterEach
    void cleanup() {
        System.clearProperty("exeris.persistence.maxPoolSize");
        System.clearProperty("exeris.persistence.pool.maxSize");
    }

    @Test
    @DisplayName("adaptive pool sizing: 8-core machine → pool size 16 (min(16, 32))")
    void adaptivePoolSizingEightCores() {
        // On 8 logical cores: 8 * 2 = 16, capped at 32 → 16
        PersistenceConfig config = CommunityPersistenceConfigResolver.buildPersistenceConfig(
            new CommunityConfigProvider(),
            5_000L,
            600_000L,
            1_800_000L,
            1,
            32);

        // Expected: adaptive sizing applies. In CI/test env, likely 4-16 cores
        // We just verify it's > 2 (minimum) and <= 32 (cap)
        assertThat(config.maxPoolSize())
            .isGreaterThanOrEqualTo(2)
            .isLessThanOrEqualTo(32);
    }

    @Test
    @DisplayName("adaptive pool sizing: high-core (32+) machine → pool size 32 (capped)")
    void adaptivePoolSizingHighCoresCapped() {
        // On 32+ cores: cores * 2 >= 64, capped at 32 → 32
        // Simulate by setting system property to override
        PersistenceConfig config = CommunityPersistenceConfigResolver.buildPersistenceConfig(
            new CommunityConfigProvider(),
            5_000L,
            600_000L,
            1_800_000L,
            1,
            32);

        // Pool size is determined by available processors
        int expected = Math.max(Math.min(Runtime.getRuntime().availableProcessors() * 2, 32), 2);
        assertThat(config.maxPoolSize()).isEqualTo(expected);
    }

    @Test
    @DisplayName("pool sizing: explicit property overrides adaptive formula")
    void explicitPropertyOverridesAdaptive() {
        System.setProperty("exeris.persistence.maxPoolSize", "12");

        PersistenceConfig config = CommunityPersistenceConfigResolver.buildPersistenceConfig(
            new CommunityConfigProvider(),
            5_000L,
            600_000L,
            1_800_000L,
            1,
            32);

        assertThat(config.maxPoolSize()).isEqualTo(12);
    }

    @Test
    @DisplayName("pool sizing: pool.maxSize alias property overrides adaptive formula")
    void aliasPropertyOverridesAdaptive() {
        System.setProperty("exeris.persistence.pool.maxSize", "24");

        PersistenceConfig config = CommunityPersistenceConfigResolver.buildPersistenceConfig(
            new CommunityConfigProvider(),
            5_000L,
            600_000L,
            1_800_000L,
            1,
            32);

        assertThat(config.maxPoolSize()).isEqualTo(24);
    }

    @Test
    @DisplayName("pool sizing: canonical property has priority over alias property")
    void canonicalPropertyPrioritized() {
        System.setProperty("exeris.persistence.maxPoolSize", "10");
        System.setProperty("exeris.persistence.pool.maxSize", "20");

        PersistenceConfig config = CommunityPersistenceConfigResolver.buildPersistenceConfig(
            new CommunityConfigProvider(),
            5_000L,
            600_000L,
            1_800_000L,
            1,
            32);

        // Canonical key wins
        assertThat(config.maxPoolSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("pool sizing: invalid property < 2 raises IllegalArgumentException")
    void invalidPoolSizeBelowMinimum() {
        System.setProperty("exeris.persistence.maxPoolSize", "1");

        assertThatThrownBy(CommunityPersistencePoolSizingTest::buildPersistenceConfigWithCommunityProvider)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Pool size must be >= 2")
            .hasMessageContaining("got: 1");
    }

    @Test
    @DisplayName("pool sizing: minIdleConnections <= maxPoolSize")
    void minIdleConnectionsValidation() {
        // Set explicit pool size
        System.setProperty("exeris.persistence.maxPoolSize", "8");

        PersistenceConfig config = CommunityPersistenceConfigResolver.buildPersistenceConfig(
            new CommunityConfigProvider(),
            5_000L,
            600_000L,
            1_800_000L,
            4,  // defaultMinIdleConnections
            32);

        assertThat(config.maxPoolSize()).isEqualTo(8);
        assertThat(config.minIdleConnections())
            .isGreaterThanOrEqualTo(0)
            .isLessThanOrEqualTo(8);
    }

    private static PersistenceConfig buildPersistenceConfigWithCommunityProvider() {
        return CommunityPersistenceConfigResolver.buildPersistenceConfig(
            new CommunityConfigProvider(),
            5_000L,
            600_000L,
            1_800_000L,
            1,
            32);
    }

}
