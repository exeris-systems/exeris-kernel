/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.events;

import eu.exeris.kernel.core.events.outbox.OutboxBrokerPort;
import eu.exeris.kernel.spi.events.EventBus;
import eu.exeris.kernel.spi.events.EventPayload;

import java.util.List;
import java.util.Objects;

/**
 * Community bridge from OutboxOrchestrator to the in-memory EventBus.
 */
final class CommunityEventBusOutboxBrokerPort implements OutboxBrokerPort {

    private final EventBus eventBus;

    /* default */ CommunityEventBusOutboxBrokerPort(EventBus eventBus) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
    }

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

    @Override
    public String brokerId() {
        return "community-event-bus";
    }
}
