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

    @Label("Environment")
    @Description("Declared execution environment, or empty when the declaration was rejected")
    public String environment;

    @Label("Manifest Source")
    @Description("File path or classpath resource the manifest was read from, or 'none'")
    public String source;

    @Label("Contract ID")
    @Description("Identifier of the bound contract, or empty when the boot was refused")
    public String contractId;

    @Label("Edition")
    @Description("Edition of the bound contract, or empty when the boot was refused")
    public String edition;

    @Label("Outcome")
    @Description("'bound', or the error code that refused the boot")
    public String outcome;

    @Label("Gate Duration")
    @Description("Time the gate took, from configuration read to decision")
    @Timespan(Timespan.NANOSECONDS)
    public long gateDuration;

    /**
     * Emits the event if Flight Recorder is initialized and the event is enabled.
     *
     * @param environment   declared environment id, or empty
     * @param source        manifest source, or {@code none}
     * @param contractId    bound contract id, or empty
     * @param edition       bound edition, or empty
     * @param outcome       {@code bound}, or the refusing error code
     * @param durationNanos time the gate took
     */
    public static void emit(String environment, String source, String contractId, String edition,
                            String outcome, long durationNanos) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        ContractResolvedEvent event = new ContractResolvedEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.environment = environment;
        event.source = source;
        event.contractId = contractId;
        event.edition = edition;
        event.outcome = outcome;
        event.gateDuration = durationNanos;
        event.commit();
    }
}
