/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.events;

import eu.exeris.kernel.core.events.outbox.NoOpBrokerPort;
import eu.exeris.kernel.core.events.outbox.OutboxBrokerPort;
import eu.exeris.kernel.core.events.outbox.OutboxEventStore;
import eu.exeris.kernel.core.events.outbox.OutboxOrchestrator;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OutboxOrchestrator — integration lifecycle")
class OutboxOrchestratorIntegrationTest {

    @Test
    @DisplayName("stop() leaves stable STOPPED state across scheduler delays")
    @Timeout(value = 8, unit = TimeUnit.SECONDS)
    void stopLeavesStableStoppedState() {
        try (OutboxOrchestrator orchestrator = OutboxOrchestrator.builder()
                .eventStore(emptyStore())
                .brokerPort(NoOpBrokerPort.INSTANCE)
                .pollIntervalNanos(2_000_000L)
                .build()) {
            orchestrator.start();

            long deadline = System.nanoTime() + 2_000_000_000L;
            while (!"POLLING".equals(orchestrator.currentState())
                    && System.nanoTime() < deadline) {
                LockSupport.parkNanos(2_000_000L);
            }

            orchestrator.stop();

            LockSupport.parkNanos(15_000_000L);
            assertThat(orchestrator.currentState()).isEqualTo("STOPPED");
        }
    }

    private static OutboxEventStore emptyStore() {
        return new OutboxEventStore() {
            @Override public List<OutboxBrokerPort.OutboxEntry> pollPending(int maxItems) { return List.of(); }
            @Override public void markDelivered(List<EventDescriptor> delivered) { /* test stub — delivery tracking not needed */ }
            @Override public void moveToDlq(EventDescriptor descriptor, EventPayload payload, String reason) { /* test stub — DLQ not exercised */ }
        };
    }
}