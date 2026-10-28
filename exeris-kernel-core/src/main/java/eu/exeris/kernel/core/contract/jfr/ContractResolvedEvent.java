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
import jdk.jfr.Timespan;

/**
 * JFR record of one contract-gate decision: which contract the kernel was bound to, or which error code
 * refused the boot, and how long the gate took. Emitted once per {@code KernelBootstrap.boot()}, single-phase,
 * on the booting thread.
 *
 * @since 0.13
 */
@Name("eu.exeris.kernel.contract.Resolved")
@Label("Contract Resolved")
@Category({"Exeris Kernel", "Contract"})
@Description("Outcome and duration of the contract gate that runs before any subsystem is initialized")
@StackTrace(false)
public class ContractResolvedEvent extends Event {

    /** Declared execution environment, or empty when the declaration was rejected. */
    @Label("Environment")
    @Description("Declared execution environment, or empty when the declaration was rejected")
    public String environment;

    /** File path or classpath resource the manifest was read from, or 'none'. */
    @Label("Manifest Source")
    @Description("File path or classpath resource the manifest was read from, or 'none'")
    public String source;

    /** Identifier of the bound contract, or empty when the boot was refused. */
    @Label("Contract ID")
    @Description("Identifier of the bound contract, or empty when the boot was refused")
    public String contractId;

    /** Edition of the bound contract, or empty when the boot was refused. */
    @Label("Edition")
    @Description("Edition of the bound contract, or empty when the boot was refused")
    public String edition;

    /** Identifier of the issuer key that signed the manifest, or empty when none was verified. */
    @Label("Issuer Key ID")
    @Description("Identifier of the issuer key that signed the manifest, or empty when none was verified")
    public String keyId;

    /** Whether the kernel runs under a developer licence, its workload envelope held at HARD. */
    @Label("Developer Licence")
    @Description("Whether the kernel runs under a developer licence, its workload envelope held at HARD")
    public boolean developerLicence;

    /** 'bound', or the error code that refused the boot. */
    @Label("Outcome")
    @Description("'bound', or the error code that refused the boot")
    public String outcome;

    /** Time the gate took, from configuration read to decision. */
    @Label("Gate Duration")
    @Description("Time the gate took, from configuration read to decision")
    @Timespan(Timespan.NANOSECONDS)
    public long gateDuration;

    /**
     * Creates an unrecorded event.
     *
     * <p>{@link #emit} assigns the public fields and calls {@link Event#commit()}. An instance that is never
     * committed contributes nothing to a recording.
     */
    public ContractResolvedEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * What the gate decided, as the event records it.
     *
     * @param environment      declared environment id, or empty
     * @param source           manifest source, or {@code none}
     * @param contractId       bound contract id, or empty
     * @param edition          bound edition, or empty
     * @param keyId            issuer key that signed the manifest, or empty when none was verified
     * @param developerLicence whether the kernel runs under a developer licence
     * @param outcome          {@code bound}, or the refusing error code
     */
    public value record Resolution(String environment, String source, String contractId, String edition,
                             String keyId, boolean developerLicence, String outcome) {
    }

    /**
     * Emits the event if Flight Recorder is initialized and the event is enabled.
     *
     * @param resolution    what the gate decided
     * @param durationNanos time the gate took
     */
    public static void emit(Resolution resolution, long durationNanos) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        ContractResolvedEvent event = new ContractResolvedEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.environment = resolution.environment();
        event.source = resolution.source();
        event.contractId = resolution.contractId();
        event.edition = resolution.edition();
        event.keyId = resolution.keyId();
        event.developerLicence = resolution.developerLicence();
        event.outcome = resolution.outcome();
        event.gateDuration = durationNanos;
        event.commit();
    }
}
