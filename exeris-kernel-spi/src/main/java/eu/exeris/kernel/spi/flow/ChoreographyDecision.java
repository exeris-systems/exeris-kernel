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
     * <p>Starting an id the engine already holds is a no-op: a redelivered event does not start a
     * second flow. A running instance is left running, a parked instance is left parked, and a
     * completed or failed instance is not run again. The supplied plan is ignored for an existing
     * instance, so a changed definition reaches a parked instance only through a version migration
     * (ADR-064). For an instance id the engine does not hold in memory:
     * <ul>
     *   <li>A completed or failed instance is recognised as finished from memory for the life of
     *       one engine, and across a restart only from the terminal row a bound snapshot store
     *       holds. With a store bound, in either terminal-catalog mode, the engine leaves a row
     *       for every finished instance and never deletes it. A redelivered Start is a no-op
     *       until the binder's retention deletes that row, and after that the id runs again.
     *       With persistence off, a Start redelivered after a restart runs the flow again.</li>
     *   <li>When the id exists only as a non-terminal durable row, Start resumes that row instead of
     *       treating it as a duplicate. Two engines that own the same instance are not fenced
     *       against each other.</li>
     * </ul>
     * The engine's absorbed-Start diagnostic does not report a resumed durable row: it starts a
     * run, so no Start is absorbed and the event does not fire.
     *
     * @param plan            the compiled execution plan to run; must not be {@code null}
     * @param instanceIdMost  most-significant bits of the new instance UUID
     * @param instanceIdLeast least-significant bits of the new instance UUID
     */
    record Start(FlowExecutionPlan plan, long instanceIdMost, long instanceIdLeast)
            implements ChoreographyDecision {}
}
