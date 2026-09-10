/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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

/**
 * Emitted once, at the end of {@link CoreFlowEngine#close()}, carrying the engine's final
 * operational counters as a stable, point-in-time snapshot.
 *
 * <p>The snapshot is taken after the runtime's bounded shutdown join, so {@code parkedFlows}
 * reads {@code 0} — the in-memory parked index has already been cleared by the time this event
 * fires. {@code nonDurableParkedFlows} is sampled just before that clear, which is the last
 * moment the count can still be read, and is the one figure an operator needs before restarting:
 * a non-zero value means some parked sagas will not survive the restart that is about to happen.
 * A worker still finalising a snapshot after being interrupted during the join does not change
 * either counter.
 */
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

    /**
     * Emits the {@code Shutdown} event carrying the engine's final counters, or does nothing if
     * the event type is disabled.
     *
     * @param config                the engine configuration to read {@code engineName} and the
     *                              persistence/compensation flags from
     * @param stats                 the engine's final operational counters
     * @param nonDurableParkedFlows count of parked instances whose PARK checkpoint the store
     *                              refused
     * @param shutdownDurationNs    wall-clock time elapsed inside {@code FlowEngine.close()}
     */
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
