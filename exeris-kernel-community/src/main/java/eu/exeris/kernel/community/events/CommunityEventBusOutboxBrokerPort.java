/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.events;

import eu.exeris.kernel.core.events.outbox.OutboxBrokerPort;
import eu.exeris.kernel.spi.events.EventBus;
import eu.exeris.kernel.spi.events.EventPayload;

import java.util.List;
import java.util.Objects;

/**
 * Community binding of {@link OutboxBrokerPort} that republishes outbox entries onto the
 * given, in-process {@link EventBus} — the default delivery target the Outbox Orchestrator
 * writes to when no cross-node broker (e.g. the Kafka driver) is on the classpath.
 *
 * <p><b>Local fan-out only.</b> Delivery never leaves the JVM: a subscriber on another node
 * does not observe events this port publishes. Durability is a property of the outbox row,
 * not of this port.
 */
final class CommunityEventBusOutboxBrokerPort implements OutboxBrokerPort {

    private final EventBus eventBus;

    /* default */ CommunityEventBusOutboxBrokerPort(EventBus eventBus) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
    }

    /**
     * Publishes {@code batch} to the wrapped {@link EventBus} in encounter order, stopping at
     * the first failure, per the {@link OutboxBrokerPort#publish} prefix-success contract.
     *
     * <p>Each entry's payload bytes are copied into a fresh, independently-owned
     * {@link CommunityHeapEventPayload} before publish — the bus takes ownership of that copy,
     * never of {@code entry.payload()} itself, which stays owned by the caller (the outbox
     * poll cycle) for the whole batch regardless of how many entries this method reaches.
     *
     * @param batch ordered outbox entries to deliver (non-null, non-empty)
     * @return the number of leading entries successfully published; less than
     *         {@code batch.size()} means the bus rejected the entry at that index
     *         (its copied payload is closed) and no later entry was attempted
     */
    @SuppressWarnings({
        "PMD.CloseResource",
        "PMD.AvoidCatchingGenericException"
    })
    @Override
    public int publish(List<OutboxEntry> batch) {
        int published = 0;
        for (OutboxEntry entry : batch) {
            EventPayload brokerPayload = CommunityHeapEventPayload.wrap(
                    CommunityHeapEventPayload.copyBytes(entry.payload()));
            try {
                eventBus.publish(entry.descriptor(), brokerPayload);
                published++;
            } catch (RuntimeException _) {
                brokerPayload.close();
                break;
            }
        }
        return published;
    }

    /**
     * Returns the fixed broker identifier this port reports to JFR telemetry.
     *
     * @return the constant {@code "community-event-bus"}
     */
    @Override
    public String brokerId() {
        return "community-event-bus";
    }
}
