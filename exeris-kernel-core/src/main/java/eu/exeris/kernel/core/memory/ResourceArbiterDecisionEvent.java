/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.memory;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted by {@link ResourceArbiter} when a load-shedding decision is made.
 *
 * <h2>JFR-First Principle</h2>
 * <p>Load-shedding decisions are emitted as JFR events, not logs. This ensures:
 * <ul>
 *   <li>Zero-overhead when JFR recording is disabled.</li>
 *   <li>Sub-microsecond event emission on the decision path.</li>
 *   <li>Full correlation with {@link MemoryPressureEvent} via JFR timeline.</li>
 * </ul>
 *
 * <h2>rawArgs Binary Layout (Glass-Box Telemetry)</h2>
 * <pre>
 *   action         — String: ALLOW | THROTTLE | REJECT | SHED_LOAD
 *   contextName    — String: TRANSPORT_IO | KERNEL_LOGIC
 *   utilizationPct — int:   memory utilization percentage [0..100]
 *   decisionNs     — long:  VarHandle CAS decision cache timestamp (nanos)
 * </pre>
 *
 * @see ResourceArbiter
 * @see ResourceArbiter.Action
 * @since 0.5.0
 */
@Name("eu.exeris.kernel.core.ResourceArbiterDecision")
@Label("Resource Arbiter Decision")
@Category({"Exeris Kernel", "Memory"})
@Description("Emitted by the ResourceArbiter when a load-shedding decision is made.")
@StackTrace(false)
/* default */ final class ResourceArbiterDecisionEvent extends Event {

    @Label("Action")
    /* default */ String action;

    @Label("Context Name")
    /* default */ String contextName;

    @Label("Memory Utilization (%)")
    @Description("Memory utilization in percent [0..100], " +
            "or -1 if the exact utilization is currently unknown/uncached.")
    /* default */ int utilizationPct;

    @Label("Decision Cache Timestamp (ns)")
    /* default */ long decisionNs;

    /**
     * Factory: emits a decision event.
     *
     * @param action         the arbitration action taken
     * @param contextName    the context name (e.g. TRANSPORT_IO)
     * @param utilizationPct memory utilization in percent [0..100], or {@code -1} if unknown/uncached
     * @param decisionNs     nanos timestamp of the cached decision
     */
    /* default */
    static void emit(ResourceArbiter.Action action,
                     String contextName,
                     int utilizationPct,
                     long decisionNs) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        ResourceArbiterDecisionEvent evt = new ResourceArbiterDecisionEvent();
        if (evt.isEnabled()) {
            evt.action = action.name();
            evt.contextName = contextName;
            evt.utilizationPct = utilizationPct;
            evt.decisionNs = decisionNs;
            evt.commit();
        }
    }
}
