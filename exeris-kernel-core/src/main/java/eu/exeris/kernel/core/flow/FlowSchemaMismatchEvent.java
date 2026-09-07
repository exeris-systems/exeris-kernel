/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.flow;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * Emitted when a parked saga's resume is rejected fail-closed because its persisted resume step no
 * longer indexes a step in the active (redeployed) flow definition — the definition changed under a
 * parked saga ({@code EX-FLOW-7002 / phase=SCHEMA_MISMATCH}). Lets operators detect a
 * deployment-gone-wrong (data-corruption-class) scenario from a JFR recording without log-scraping.
 *
 * <p>Secret-safe: carries only the engine/definition names and step counters — no payload, no opaque
 * saga state. Emitted single-phase on the synchronous resume path (not on a virtual thread mid-block),
 * immediately before the guard throws.
 */
@Name("eu.exeris.kernel.flow.SchemaMismatch")
@Label("Flow Saga Schema Mismatch")
@Category({"Exeris Kernel", "Flow"})
@Description("Emitted when a parked saga resume is rejected fail-closed because the persisted step no"
    + " longer exists in the active definition (definition changed under a parked saga).")
@StackTrace(false)
final class FlowSchemaMismatchEvent extends Event {

    @Label("Engine Name")
    @Description("Human-readable engine name from FlowEngineConfig.engineName()")
    /* default */ String engineName;

    @Label("Definition Name")
    @Description("FlowDefinition name of the saga whose resume was rejected")
    /* default */ String definitionName;

    @Label("Instance Id (most)")
    @Description("Most-significant 64 bits of the flow instance UUID")
    /* default */ long instanceIdMost;

    @Label("Instance Id (least)")
    @Description("Least-significant 64 bits of the flow instance UUID")
    /* default */ long instanceIdLeast;

    @Label("Persisted Step")
    @Description("Persisted resume step index that the active definition no longer has")
    /* default */ int persistedStep;

    @Label("Reason")
    /* default */ String reason;

    @Label("Persisted Step Name")
    /* default */ String persistedStepName;

    @Label("Plan Step Name")
    /* default */ String planStepName;

    @Label("Plan Step Count")
    @Description("Step count of the active (resolved) definition the resume was attempted against")
    /* default */ int planStepCount;

    /**
     * Emits the {@code SchemaMismatch} event recording a fail-closed resume refusal, or does
     * nothing if the event type is disabled.
     *
     * @param engineName        the emitting engine's name
     * @param definitionName    flow definition name of the saga whose resume was rejected
     * @param instanceIdMost    most-significant 64 bits of the flow instance UUID
     * @param instanceIdLeast   least-significant 64 bits of the flow instance UUID
     * @param persistedStep     persisted resume step index the active definition no longer has
     * @param planStepCount     step count of the active definition the resume was attempted
     *                          against
     * @param reason            the refusal reason
     * @param persistedStepName name of the step at the persisted index, if known
     * @param planStepName      name of the step the active plan resolves at that index, if known
     */
    /* default */ static void emit(
            String engineName,
            String definitionName,
            long instanceIdMost,
            long instanceIdLeast,
            int persistedStep,
            int planStepCount,
            String reason,
            String persistedStepName,
            String planStepName) {
        FlowSchemaMismatchEvent event = new FlowSchemaMismatchEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.begin();
        event.engineName = engineName;
        event.definitionName = definitionName;
        event.instanceIdMost = instanceIdMost;
        event.instanceIdLeast = instanceIdLeast;
        event.persistedStep = persistedStep;
        event.planStepCount = planStepCount;
        event.reason = reason;
        // Step names are definition metadata written by the application's own code — not user data,
        // not credentials, not principal identity — so they are safe under the JFR payload rules, and
        // without them the event reports that a mismatch happened while withholding what mismatched.
        event.persistedStepName = persistedStepName;
        event.planStepName = planStepName;
        event.commit();
    }
}
