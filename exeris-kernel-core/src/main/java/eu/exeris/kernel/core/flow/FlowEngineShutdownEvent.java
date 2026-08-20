/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.flow.FlowEngineConfig;
import eu.exeris.kernel.spi.flow.FlowEngineStats;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name("eu.exeris.kernel.flow.Shutdown")
@Label("Flow Engine Shutdown")
@Category({"Exeris Kernel", "Flow"})
@Description("Emitted when the flow engine shuts down, with a final operational counter snapshot.")
@StackTrace(false)
final class FlowEngineShutdownEvent extends Event {

    @Label("Engine Name")
    @Description("Human-readable engine name from FlowEngineConfig.engineName()")
    /* default */ String engineName;

    @Label("Active Flows")
    @Description("Number of concurrently executing flow instances at shutdown")
    /* default */ long activeFlows;

    @Label("Parked Flows")
    @Description("Number of parked flow instances at shutdown")
    /* default */ long parkedFlows;

    @Label("Completed Flows")
    @Description("Total number of completed flows since engine start")
    /* default */ long completedFlows;

    @Label("Failed Flows")
    @Description("Total number of failed and compensated flows since engine start")
    /* default */ long failedFlows;

    @Label("Persistence Enabled")
    @Description("Whether snapshot persistence was enabled for this engine")
    /* default */ boolean persistenceEnabled;

    @Label("Compensation Enabled")
    @Description("Whether compensation support was enabled for this engine")
    /* default */ boolean compensationEnabled;

    @Label("Non-Durable Parked Flows")
    @Description("Parked instances whose PARK checkpoint the store refused, so they are wakeable "
            + "in this JVM but will not survive the restart that is about to happen. The number an "
            + "operator needs before restarting; a non-zero value means sagas are about to be lost.")
    /* default */ long nonDurableParkedFlows;

    @Label("Shutdown Duration (ns)")
    @Description("Wall-clock time elapsed inside FlowEngine.close()")
    /* default */ long shutdownDurationNs;

    /* default */ static void emit(FlowEngineConfig config, FlowEngineStats stats,
                                   long nonDurableParkedFlows, long shutdownDurationNs) {
        FlowEngineShutdownEvent event = new FlowEngineShutdownEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.begin();
        event.engineName = config.engineName();
        event.activeFlows = stats.activeFlows();
        event.parkedFlows = stats.parkedFlows();
        event.nonDurableParkedFlows = nonDurableParkedFlows;
        event.completedFlows = stats.completedFlows();
        event.failedFlows = stats.failedFlows();
        event.persistenceEnabled = config.persistenceEnabled();
        event.compensationEnabled = config.compensationEnabled();
        event.shutdownDurationNs = shutdownDurationNs;
        event.commit();
    }
}
