/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.flow;

import eu.exeris.kernel.spi.events.EventDescriptor;

/**
 * SPI: Maps an {@link EventDescriptor} to a {@link ChoreographyDecision} for event-driven flow orchestration.
 *
 * <h2>Design Intent</h2>
 * <p>Receives only the routing metadata ({@link EventDescriptor}) — no payload, no scheduler
 * reference. This keeps the SPI implementation-blind: implementations depend only on
 * primitive fields (ordinals, UUIDs as {@code long} pairs, flags) and not on any
 * broker or infrastructure type.
 *
 * <p><b>Thread confinement:</b> any thread — one mapper serves every arrival of the event types it
 * was registered for, so it must be safe for concurrent invocation from multiple virtual threads.
 *
 * @implSpec A mapper must be safe for concurrent invocation from multiple virtual threads.
 * @since 0.5
 * @see ChoreographyDecision
 * @see FlowEngine#registerChoreographyMapper
 */
@FunctionalInterface
public interface FlowChoreographyMapper {

    /**
     * Decides what this event means for the flow subsystem: nothing, a wake of a named parked
     * instance, or the start of a new one.
     *
     * @param descriptor routing metadata for the arriving event; never {@code null}
     * @return the decision the engine acts on; never {@code null} — an event this mapper has no
     *         interest in is {@link ChoreographyDecision.Ignore}, not {@code null}
     */
    ChoreographyDecision map(EventDescriptor descriptor);
}
