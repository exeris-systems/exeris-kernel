/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.events;

import eu.exeris.kernel.core.events.outbox.NoOpBrokerPort;
import eu.exeris.kernel.core.events.outbox.OutboxBrokerPort;
import eu.exeris.kernel.core.events.outbox.OutboxEventStore;
import eu.exeris.kernel.core.events.outbox.OutboxOrchestrator;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventPayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit: {@link OutboxOrchestrator} — state machine correctness.
 *
 * @since 0.5.0
 */
@DisplayName("OutboxOrchestrator — state machine & at-least-once delivery")
class OutboxOrchestratorTest {

    private OutboxOrchestrator orchestrator;

    @AfterEach
    void tearDown() {
        if (orchestrator != null) {
            orchestrator.close();
        }
    }

    @Test
    @DisplayName("Initial state is IDLE before start()")
    void initialStateIsIdle() {
        orchestrator = OutboxOrchestrator.builder()
                .eventStore(emptyStore())
                .brokerPort(NoOpBrokerPort.INSTANCE)
                .build();

        assertThat(orchestrator.currentState()).isEqualTo("IDLE");
    }

    @Test
    @DisplayName("start() transitions to POLLING; stop() transitions to STOPPED")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void startAndStopLifecycle() throws InterruptedException {
        orchestrator = OutboxOrchestrator.builder()
                .eventStore(emptyStore())
                .brokerPort(NoOpBrokerPort.INSTANCE)
                .pollIntervalNanos(10_000_000L)
                .build();

        orchestrator.start();
                long deadline = System.nanoTime() + 3_000_000_000L;
        while (!"POLLING".equals(orchestrator.currentState())
                && System.nanoTime() < deadline) {
                    LockSupport.parkNanos(5_000_000L);
        }
        orchestrator.stop();

        assertThat(orchestrator.currentState()).isEqualTo("STOPPED");
    }

    @Test
    @DisplayName("Rapid start/stop cycles keep terminal state STOPPED")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void rapidStartStopKeepsStoppedState() throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            orchestrator = OutboxOrchestrator.builder()
                    .eventStore(emptyStore())
                    .brokerPort(NoOpBrokerPort.INSTANCE)
                    .pollIntervalNanos(1_000_000L)
                    .build();

            orchestrator.start();
            orchestrator.stop();

            LockSupport.parkNanos(2_000_000L);
            assertThat(orchestrator.currentState()).isEqualTo("STOPPED");
        }
    }

    @Test
    @DisplayName("Broker port receives exactly the polled events")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void polledEventsDeliveredToBroker() throws InterruptedException {
        EventDescriptor descriptor = new EventDescriptor(1L, 2L, 3L, 4L, 10,
                EventDescriptor.FLAG_PERSISTENT, System.currentTimeMillis());

        AtomicInteger publishedCount = new AtomicInteger(0);
        CountDownLatch flushed = new CountDownLatch(1);

        OutboxEventStore store = new OutboxEventStore() {
            private boolean consumed = false;

            @Override
            public List<OutboxBrokerPort.OutboxEntry> pollPending(int maxItems) {
                if (!consumed) {
                    consumed = true;
                    return List.of(new OutboxBrokerPort.OutboxEntry(descriptor, EventPayload.empty()));
                }
                return List.of();
            }

            @Override
            public void markDelivered(List<EventDescriptor> delivered) {
                publishedCount.addAndGet(delivered.size());
                flushed.countDown();
            }

            @Override
            public void moveToDlq(EventDescriptor d, EventPayload p, String reason) { /* test stub — DLQ not exercised in this test path */ }
        };

        OutboxBrokerPort port = new OutboxBrokerPort() {
            @Override public int publish(List<OutboxBrokerPort.OutboxEntry> batch) { return batch.size(); }
            @Override public String brokerId() { return "test-stub"; }
        };

        orchestrator = OutboxOrchestrator.builder()
                .eventStore(store)
                .brokerPort(port)
                .batchSize(10)
                .pollIntervalNanos(10_000_000L)
                .build();

        orchestrator.start();
        assertThat(flushed.await(4, TimeUnit.SECONDS)).isTrue();
        orchestrator.stop();

        assertThat(publishedCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Failed events after all retries are moved to DLQ")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void failedEventMovedToDlq() throws InterruptedException {
        EventDescriptor descriptor = new EventDescriptor(9L, 8L, 7L, 6L, 99,
                EventDescriptor.FLAG_PERSISTENT, System.currentTimeMillis());

        CountDownLatch dlqLatch = new CountDownLatch(1);
        List<EventDescriptor> dlqEntries = new ArrayList<>();

        OutboxEventStore store = new OutboxEventStore() {
            private boolean consumed = false;

            @Override
            public List<OutboxBrokerPort.OutboxEntry> pollPending(int maxItems) {
                if (!consumed) {
                    consumed = true;
                    return List.of(new OutboxBrokerPort.OutboxEntry(descriptor, EventPayload.empty()));
                }
                return List.of();
            }

            @Override
            public void markDelivered(List<EventDescriptor> delivered) { /* test stub — delivery not asserted here */ }

            @Override
            public void moveToDlq(EventDescriptor d, EventPayload p, String reason) {
                dlqEntries.add(d);
                dlqLatch.countDown();
            }
        };

        // Always fails
        OutboxBrokerPort port = new OutboxBrokerPort() {
            @Override public int publish(List<OutboxBrokerPort.OutboxEntry> batch) { return 0; }
            @Override public String brokerId() { return "always-fail-stub"; }
        };

        orchestrator = OutboxOrchestrator.builder()
                .eventStore(store)
                .brokerPort(port)
                .maxRetries(1)
                .pollIntervalNanos(5_000_000L)
                .build();

        orchestrator.start();
        assertThat(dlqLatch.await(4, TimeUnit.SECONDS)).isTrue();
        orchestrator.stop();

        assertThat(dlqEntries).hasSize(1);
        assertThat(dlqEntries.get(0)).isEqualTo(descriptor);
    }

    @Test
    @DisplayName("NoOpBrokerPort.brokerId() is 'noop'")
    void noOpBrokerPortId() {
        assertThat(NoOpBrokerPort.INSTANCE.brokerId()).isEqualTo("noop");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static OutboxEventStore emptyStore() {
        return new OutboxEventStore() {
            @Override public List<OutboxBrokerPort.OutboxEntry> pollPending(int maxItems) { return List.of(); }
            @Override public void markDelivered(List<EventDescriptor> d) { /* empty store — nothing delivered */ }
            @Override public void moveToDlq(EventDescriptor d, EventPayload p, String r) { /* empty store — no DLQ */ }
        };
    }
}
