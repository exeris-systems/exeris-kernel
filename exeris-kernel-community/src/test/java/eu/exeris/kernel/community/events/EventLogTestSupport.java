/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.events;

import eu.exeris.kernel.community.persistence.CommunityPersistenceProvider;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;

/**
 * Test-only helpers shared by the JDBC event-log TCK bindings (ADR-049): builds a migration-enabled
 * Community persistence engine over a Testcontainers Postgres and truncates {@code exeris_event_log}
 * between tests.
 */
final class EventLogTestSupport {

    private EventLogTestSupport() {
        // utility — no instances
    }

    static PersistenceEngine createEngine(PostgreSQLContainer<?> postgres) {
        PersistenceConfig cfg = new PersistenceConfig(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword(),
                8,          // maxPoolSize
                1,          // minIdleConnections
                5_000L,     // connectionTimeoutMs
                60_000L,    // idleTimeoutMs
                600_000L,   // maxLifetimeMs
                false,      // useTls
                false,      // rlsEnabled
                false,      // perTenantPooling
                0,          // maxTenantPools
                Map.of("run.migrations", "true"));
        return new CommunityPersistenceProvider().createEngine(cfg);
    }

    static void truncateEventLog(PersistenceEngine engine) {
        try (PersistenceConnection conn = engine.openConnection()) {
            conn.executeUpdate("TRUNCATE TABLE exeris_event_log");
        }
    }
}
