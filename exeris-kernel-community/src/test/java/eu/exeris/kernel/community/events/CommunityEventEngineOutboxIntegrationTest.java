/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.events;

import eu.exeris.kernel.community.persistence.CommunityPersistenceProvider;
import eu.exeris.kernel.community.persistence.jdbc.CommunityJdbcEventStore;
import eu.exeris.kernel.core.events.outbox.OutboxBrokerPort;
import eu.exeris.kernel.core.events.outbox.OutboxEventStore;
import eu.exeris.kernel.core.events.outbox.OutboxOrchestrator;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventEngineConfig;
import eu.exeris.kernel.spi.events.EventTypeSpec;
import eu.exeris.kernel.spi.persistence.EventStore;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Community EventEngine: outbox orchestrator integration")
class CommunityEventEngineOutboxIntegrationTest {

    @Test
    @DisplayName("Persistent outbox rows are republished through EventBus and marked delivered")
    void outboxRowsAreRepublishedAndMarkedDelivered() {
        String jdbcUrl = "jdbc:h2:mem:community_event_outbox_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        PersistenceConfig persistenceConfig = new PersistenceConfig(
                jdbcUrl,
                "sa",
                "",
                16,
                2,
                5_000L,
                60_000L,
                60_000L,
                false,
                false,
                false,
                0,
                Map.of("run.migrations", "true")
        );

        try (PersistenceEngine persistenceEngine = new CommunityPersistenceProvider().createEngine(persistenceConfig)) {
            ScopedValue.where(KernelProviders.PERSISTENCE_ENGINE, persistenceEngine).run(() -> {
                EventEngineConfig config = new EventEngineConfig(
                        "community-events-outbox-it",
                        1_024,
                        64,
                        "events",
                        0L,
                        0,
                        0,
                        0,
                        0,
                        true,
                        32
                );

                EventEngine eventEngine = new CommunityEventProvider().createEngine(config);
                String eventType = "OrderPersistedFromOutbox";
                int eventOrdinal = 901;
                eventEngine.registry().register(EventTypeSpec.ofPersistent(eventType, eventOrdinal));

                CountDownLatch deliveredLatch = new CountDownLatch(1);
                AtomicReference<byte[]> receivedPayload = new AtomicReference<>();

                eventEngine.bus().subscribe(eventType, (descriptor, payload) -> {
                    try (payload) {
                        receivedPayload.set(payload.segment().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
                    }
                    deliveredLatch.countDown();
                });

                appendOutboxRow(persistenceEngine, eventType, "hello-outbox".getBytes(StandardCharsets.UTF_8));

                eventEngine.start();
                try {
                    boolean deliveredInTime;
                    try {
                        deliveredInTime = deliveredLatch.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                        deliveredInTime = false;
                    }

                    assertThat(deliveredInTime)
                            .as("Outbox orchestrator should republish the pending outbox row")
                            .isTrue();
                    assertThat(receivedPayload.get())
                            .as("EventBus subscriber should receive original outbox payload bytes")
                            .isEqualTo("hello-outbox".getBytes(StandardCharsets.UTF_8));
                    assertThat(awaitCondition(() -> countPublishedRows(persistenceEngine) == 1L, 5_000L))
                            .as("Outbox row should be marked as published after successful dispatch")
                            .isTrue();
                } finally {
                    eventEngine.close();
                }
            });
        }
    }

    @Test
    @DisplayName("Failed outbox publish is moved to DLQ after max retries")
    void failedOutboxPublishMovesToDlqAfterMaxRetries() {
        String jdbcUrl = "jdbc:h2:mem:community_event_outbox_dlq_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        PersistenceConfig persistenceConfig = new PersistenceConfig(
                jdbcUrl,
                "sa",
                "",
                16,
                2,
                5_000L,
                60_000L,
                60_000L,
                false,
                false,
                false,
                0,
                Map.of("run.migrations", "true")
        );

        try (PersistenceEngine persistenceEngine = new CommunityPersistenceProvider().createEngine(persistenceConfig)) {
            CommunityEventRegistry registry = new CommunityEventRegistry();
            String eventType = "OrderPersistedDlq";
            registry.register(EventTypeSpec.ofPersistent(eventType, 902));

            appendOutboxRow(persistenceEngine, eventType, "dlq-payload".getBytes(StandardCharsets.UTF_8));

            OutboxEventStore eventStore = new CommunityJdbcOutboxEventStoreAdapter(persistenceEngine, registry);
            OutboxOrchestrator orchestrator = OutboxOrchestrator.builder()
                    .eventStore(eventStore)
                    .brokerPort(new AlwaysFailingBrokerPort())
                    .batchSize(8)
                    .pollIntervalNanos(TimeUnit.MILLISECONDS.toNanos(10))
                    .maxRetries(1)
                    .build();

            orchestrator.start();
            try {
                boolean movedToDlq = awaitCondition(() -> countDlqRows(persistenceEngine) == 1L, 3_000L);
                assertThat(movedToDlq)
                        .as("Outbox entry should be moved to DLQ when broker fails and retries are exhausted")
                        .isTrue();
                assertThat(countPublishedRows(persistenceEngine))
                        .as("Failed entry should not be marked published")
                    .isZero();
                assertThat(fetchDlqFailureReason(persistenceEngine))
                        .as("DLQ failure reason should describe retry exhaustion")
                        .contains("max retries exhausted");
            } finally {
                orchestrator.stop();
            }
        }
    }

    private static void appendOutboxRow(PersistenceEngine persistenceEngine, String eventType, byte[] payload) {
        try (PersistenceConnection connection = persistenceEngine.openConnection()) {
            EventStore store = new CommunityJdbcEventStore(connection);
            connection.beginTransaction();
            store.append(new EventStore.OutboxEvent(
                    UUID.randomUUID(),
                    "agg-1",
                    "Order",
                    eventType,
                    payload,
                    System.currentTimeMillis()
            ));
            connection.commit();
        }
    }

    private static long countPublishedRows(PersistenceEngine persistenceEngine) {
        try (PersistenceConnection connection = persistenceEngine.openConnection();
             var result = connection.executeQuery("SELECT COUNT(*) FROM exeris_outbox WHERE published_at IS NOT NULL")) {
            if (!result.next()) {
                return 0L;
            }
            return result.row().getLong(0);
        }
    }

    private static long countDlqRows(PersistenceEngine persistenceEngine) {
        try (PersistenceConnection connection = persistenceEngine.openConnection();
             var result = connection.executeQuery("SELECT COUNT(*) FROM exeris_outbox_dlq")) {
            if (!result.next()) {
                return 0L;
            }
            return result.row().getLong(0);
        }
    }

    private static String fetchDlqFailureReason(PersistenceEngine persistenceEngine) {
        try (PersistenceConnection connection = persistenceEngine.openConnection();
             var result = connection.executeQuery("SELECT failure_reason FROM exeris_outbox_dlq ORDER BY moved_at DESC LIMIT 1")) {
            if (!result.next()) {
                return "";
            }
            return result.row().getString(0);
        }
    }

    private static boolean awaitCondition(java.util.function.BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(20_000_000L); // 20 ms — deterministic poll, S2925-clean
            if (Thread.currentThread().isInterrupted()) {
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    private static final class AlwaysFailingBrokerPort implements OutboxBrokerPort {

        @Override
        public int publish(java.util.List<OutboxEntry> batch) {
            return 0;
        }

        @Override
        public String brokerId() {
            return "always-failing-broker";
        }
    }
}
