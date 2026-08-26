/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.events;

import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventTypeSpec;
import eu.exeris.kernel.tck.contract.AbstractSubsystemCarrierPinningTck;
import org.junit.jupiter.api.DisplayName;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * TCK: Carrier pinning verifier for the EventBus publish hot path.
 *
 * <h2>Hot Path Under Test</h2>
 * <p>{@code bus.publish(pre-built descriptor, EventPayload.empty())} — the event routing
 * and dispatch path must never pin a carrier thread.
 *
 * @since 0.5.0
 * @see AbstractSubsystemCarrierPinningTck
 * @see EventBusZeroAllocTck
 */
@DisplayName("EventBus carrier pinning TCK")
public abstract class EventBusCarrierPinningTck extends AbstractSubsystemCarrierPinningTck {

    private static final String EVENT_TYPE    = "CarrierPinTestEvent";
    private static final int    EVENT_ORDINAL = 98;

    protected abstract EventEngine createEngine();

    private EventEngine     engine;
    private EventDescriptor hotDescriptor;

    @Override protected String subsystemName()      { return "EventBus"; }
    @Override protected String hotPathDescription() { return "EventBus.publish(pre-built descriptor, EventPayload.empty())"; }

    @Override
    protected void bootstrapSubsystem() {
        engine = createEngine();
        engine.registry().register(EventTypeSpec.of(EVENT_TYPE, EVENT_ORDINAL));
        engine.bus().subscribe(EVENT_TYPE, (_, payload) -> payload.close());
        engine.start();

        UUID id = UUID.nameUUIDFromBytes("carrier-pin-event".getBytes(StandardCharsets.UTF_8));
        hotDescriptor = new EventDescriptor(
                id.getMostSignificantBits(), id.getLeastSignificantBits(),
                0L, 0L,
                EVENT_ORDINAL, EventDescriptor.FLAG_ASYNC, System.currentTimeMillis());
    }

    @Override
    protected void runSingleIteration() {
        engine.bus().publish(hotDescriptor, EventPayload.empty());
    }

    @Override
    protected void tearDownSubsystem() {
        if (engine != null) engine.close();
    }
}

