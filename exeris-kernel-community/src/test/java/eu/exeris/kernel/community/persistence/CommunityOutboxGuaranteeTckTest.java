/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.community.persistence.jdbc.CommunityJdbcEventStore;
import eu.exeris.kernel.spi.persistence.EventStore;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.tck.contract.persistence.AbstractOutboxGuaranteeTck;
import org.junit.jupiter.api.DisplayName;

import java.util.List;
import java.util.Map;

@DisplayName("Community: Outbox Guarantee TCK")
class CommunityOutboxGuaranteeTckTest extends AbstractOutboxGuaranteeTck {

    private final String jdbcUrl =
            "jdbc:h2:mem:community_outbox_guarantee_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";

    private PersistenceEngine currentEngine;
    private int delivered;

    @Override
    protected int brokerEventCount() {
        return 2_000;
    }

    @Override
    protected PersistenceEngine createEngine() {
        currentEngine = new CommunityPersistenceProvider().createEngine(testConfig());
        return currentEngine;
    }

    @Override
    protected PersistenceEngine rebuildEngine() {
        currentEngine = new CommunityPersistenceProvider().createEngine(testConfig());
        return currentEngine;
    }

    @Override
    protected EventStore createEventStore(PersistenceConnection connection) {
        return new CommunityJdbcEventStore(connection);
    }

    @Override
    protected void simulateBrokerDown() {
        // Broker is modeled as paused; no auto-drain thread consumes from outbox.
    }

    @Override
    protected void simulateBrokerUp() {
        delivered += drainAllPendingToBroker(currentEngine);
    }

    @Override
    protected int deliveredToBroker() {
        return delivered;
    }

    private static int drainAllPendingToBroker(PersistenceEngine engine) {
        int deliveredCount = 0;
        try (PersistenceConnection connection = engine.openConnection()) {
            EventStore store = new CommunityJdbcEventStore(connection);
            while (true) {
                connection.beginTransaction();
                List<EventStore.OutboxEvent> batch = store.pollPending(512);
                if (batch.isEmpty()) {
                    connection.rollback();
                    break;
                }
                for (EventStore.OutboxEvent event : batch) {
                    store.markPublished(event.eventId());
                    deliveredCount++;
                }
                connection.commit();
            }
        }
        return deliveredCount;
    }

    private PersistenceConfig testConfig() {
        return new PersistenceConfig(
                jdbcUrl,
                "sa",
                "",
                16,
                4,
                5_000L,
                60_000L,
                600_000L,
                false,
                false,
                false,
                0,
                Map.of("run.migrations", "true")
        );
    }
}
