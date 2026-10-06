/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.contract.jfr;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR record of a {@code SOFT} or {@code AUDIT} contract violation, emitted by {@link ContractViolationReporter}
 * single-phase on the reporting thread.
 *
 * @since 0.13
 */
@Name("eu.exeris.kernel.contract.Enforcement")
@Label("Contract Enforcement")
@Category({"Exeris Kernel", "Contract"})
@Description("Emitted when a contract enforcement action is triggered (SOFT warning or AUDIT)")
@StackTrace(false)
public class ContractEnforcementEvent extends Event {

    /** Identifier of the execution contract. */
    @Label("Contract ID")
    @Description("Identifier of the execution contract")
    public String contractId;

    /** Name of the constraint being enforced. */
    @Label("Constraint Key")
    @Description("Name of the constraint being enforced")
    public String constraintKey;

    /** Enforcement tier applied: HARD, SOFT, or AUDIT. */
    @Label("Enforcement Level")
    @Description("Enforcement tier applied: HARD, SOFT, or AUDIT")
    public String enforcementLevel;

    /** Contextual details of the enforcement action. */
    @Label("Details")
    @Description("Contextual details of the enforcement action")
    public String details;

    /**
     * Creates an unrecorded event.
     *
     * <p>{@link #emit} assigns the public fields and calls {@link Event#commit()}. An instance that is never
     * committed contributes nothing to a recording.
     */
    public ContractEnforcementEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Emits a contract enforcement event if FlightRecorder is initialized and this event is enabled.
     *
     * @param contractId       contract ID
     * @param constraintKey    constraint key
     * @param enforcementLevel enforcement level
     * @param details          details
     */
    public static void emit(String contractId, String constraintKey, String enforcementLevel, String details) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        ContractEnforcementEvent event = new ContractEnforcementEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.contractId = contractId;
        event.constraintKey = constraintKey;
        event.enforcementLevel = enforcementLevel;
        event.details = details;
        event.commit();
    }
}
