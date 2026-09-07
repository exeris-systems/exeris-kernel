/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.flow;

import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;

/**
 * SPI: Sealed decision type returned by {@link FlowChoreographyMapper}.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>All permitted types are {@code record}s whose fields are either primitives or
 * {@link FlowExecutionPlan} (an SPI type). No broker types, no collections, no
 * {@link String} identity operations.
 *
 * @since 0.5
 * @see FlowChoreographyMapper
 */
public sealed interface ChoreographyDecision
        permits ChoreographyDecision.Ignore,
                ChoreographyDecision.Wake,
                ChoreographyDecision.Start {

    /**
     * The engine does nothing with this event — it is irrelevant to any flow instance.
     */
    record Ignore() implements ChoreographyDecision {}

    /**
     * The engine resumes the parked instance under this key, from the step it parked on.
     *
     * @param instanceIdMost  most-significant bits of the flow instance UUID
     * @param instanceIdLeast least-significant bits of the flow instance UUID
     */
    record Wake(long instanceIdMost, long instanceIdLeast) implements ChoreographyDecision {}

    /**
     * The engine schedules a new instance of the supplied plan under this key.
     *
     * @param plan            the compiled execution plan to run; must not be {@code null}
     * @param instanceIdMost  most-significant bits of the new instance UUID
     * @param instanceIdLeast least-significant bits of the new instance UUID
     */
    record Start(FlowExecutionPlan plan, long instanceIdMost, long instanceIdLeast)
            implements ChoreographyDecision {}
}
