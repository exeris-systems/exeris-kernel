/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 * @since 0.6.0
 * @see FlowChoreographyMapper
 */
public sealed interface ChoreographyDecision
        permits ChoreographyDecision.Ignore,
                ChoreographyDecision.Wake,
                ChoreographyDecision.Start {

    /**
     * No action — the arriving event is irrelevant to any flow instance.
     */
    record Ignore() implements ChoreographyDecision {}

    /**
     * Wake the identified parked flow instance.
     *
     * @param instanceIdMost  most-significant bits of the flow instance UUID
     * @param instanceIdLeast least-significant bits of the flow instance UUID
     */
    record Wake(long instanceIdMost, long instanceIdLeast) implements ChoreographyDecision {}

    /**
     * Start a new flow instance from the supplied plan.
     *
     * @param plan            the compiled execution plan to run; must not be {@code null}
     * @param instanceIdMost  most-significant bits of the new instance UUID
     * @param instanceIdLeast least-significant bits of the new instance UUID
     */
    record Start(FlowExecutionPlan plan, long instanceIdMost, long instanceIdLeast)
            implements ChoreographyDecision {}
}
