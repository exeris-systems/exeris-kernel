/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CommunityPersistenceEngine prewarm: simultaneous-hold semantics")
class CommunityPersistenceEnginePrewarmTest {

    @Test
    @DisplayName("simultaneous-hold prewarm leaves N connections idle after construction")
    void prewarmHoldsConnectionsSimultaneouslyAndReleasesThemIdle() {
        PersistenceConfig config = new PersistenceConfig(
                "jdbc:h2:mem:prewarm_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "",
                4,    // maxPoolSize — must be >= warmupConnections
                3,    // minIdleConnections — keep 3 idle so HikariCP does not prune them
                250L, // connectionTimeoutMs — at the Hikari floor
                60_000L,
                600_000L,
                false,
                false,
                false,
                0,
                Map.of(
                        "pool.warmup.enabled", "true",
                        "pool.warmup.connections", "3"
                )
        );

        try (CommunityPersistenceEngine engine = new CommunityPersistenceEngine(config)) {
            assertThat(engine.stats().idleConnections())
                    .as("prewarm must leave >= 3 idle connections in the shared pool")
                    .isGreaterThanOrEqualTo(3);
        }
    }

    @Test
    @DisplayName("prewarm disabled — pool starts with minIdle connections only")
    void prewarmDisabled_poolStartsWithMinIdle() {
        PersistenceConfig config = new PersistenceConfig(
                "jdbc:h2:mem:prewarm_disabled_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "",
                4,
                1,    // minIdleConnections
                250L,
                60_000L,
                600_000L,
                false,
                false,
                false,
                0,
                Map.of("pool.warmup.enabled", "false")
        );

        try (CommunityPersistenceEngine engine = new CommunityPersistenceEngine(config)) {
            // Warmup path is entirely skipped; pool behaviour governed by HikariCP minIdle only.
            // We do not assert a specific count because HikariCP initialises minIdle connections
            // asynchronously — we only assert the engine is operational.
            assertThat(engine.stats()).isNotNull();
        }
    }
}
