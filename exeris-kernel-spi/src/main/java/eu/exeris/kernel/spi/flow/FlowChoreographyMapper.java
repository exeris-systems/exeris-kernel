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
 * <h2>Thread Safety</h2>
 * <p>Implementations MUST be safe for concurrent invocation from multiple virtual threads.
 *
 * @since 0.5
 * @see ChoreographyDecision
 * @see FlowEngine#registerChoreographyMapper
 */
@FunctionalInterface
public interface FlowChoreographyMapper {

    /**
     * Determines what action the flow engine should take in response to the given event.
     *
     * @param descriptor routing metadata for the arriving event; never {@code null}
     * @return the choreography decision; never {@code null}
     */
    ChoreographyDecision map(EventDescriptor descriptor);
}
