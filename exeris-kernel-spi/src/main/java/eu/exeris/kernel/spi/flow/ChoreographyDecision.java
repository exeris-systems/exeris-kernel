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
     * (ADR-064). Two limits apply:
     * <ul>
     *   <li>With {@code FlowEngineConfig#terminalCatalogMaxSize()} at {@code 0} (the default) a
     *       completed instance's durable row is deleted, so after an engine restart a redelivered
     *       Start runs the flow again. The no-op holds across restarts only in bounded-catalog mode,
     *       with a snapshot store configured, and while the retained row is still within the
     *       distributed saga TTL retention.</li>
     *   <li>When the id exists only as a non-terminal durable row, Start resumes that row instead of
     *       treating it as a duplicate. Two engines that own the same instance are not fenced
     *       against each other.</li>
     * </ul>
     * Neither limit is reported by the engine's absorbed-Start diagnostic: a resumed durable row
     * starts a run, so no Start is absorbed and the event does not fire.
     *
     * @param plan            the compiled execution plan to run; must not be {@code null}
     * @param instanceIdMost  most-significant bits of the new instance UUID
     * @param instanceIdLeast least-significant bits of the new instance UUID
     */
    record Start(FlowExecutionPlan plan, long instanceIdMost, long instanceIdLeast)
            implements ChoreographyDecision {}
}
