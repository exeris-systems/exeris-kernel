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
 * JFR event emitted when a wake falls back to the durable
 * {@link eu.exeris.kernel.spi.flow.model.FlowSnapshotStore} because the saga
 * was not present in the in-memory {@code parkedInstances} or {@code liveInstances}
 * indices on this engine instance.
 *
 * <p>Since 0.12 the emit sits at the store read rather than at one caller, so every
 * path that consults the store reports it - {@code lookupParked}, the key-addressed
 * {@code wake(long, long)}, and {@code wake(FlowContext)}.
 *
 * <p>This is the observability primitive operators use to monitor distributed
 * choreography wake patterns: a non-zero rate of fallback events indicates that
 * wake events are arriving on engines other than the one that originated the
 * saga. ADR-013 §8 names this as a required telemetry signal alongside
 * optimistic-lock conflicts and parked-enumeration latency.
 *
 * <p>Always emitted (regardless of whether the snapshot was found or not) so
 * that the {@code restored} flag distinguishes "saga genuinely cross-engine"
 * from "stale wake event for an unknown instance".
 *
 * @since 0.7.0
 */
@Name("eu.exeris.kernel.flow.WakeOnLoadFallback")
@Label("Distributed Saga — Wake-on-Load Fallback")
@Category({"Exeris Kernel", "Flow"})
@Description("Emitted when a wake event triggers a snapshot-store load because the saga "
        + "was not present in the engine's in-memory parked-instance index — a cross-engine "
        + "or post-restart wake. The restored flag indicates whether the snapshot store "
        + "had the row.")
@StackTrace(false)
final class WakeOnLoadFallbackEvent extends Event {

    @Label("Engine Name")
    @Description("Human-readable engine name from FlowEngineConfig.engineName()")
    /* default */ String engineName;

    @Label("Instance Id (most)")
    @Description("Most-significant 64 bits of the flow instance UUID")
    /* default */ long instanceIdMost;

    @Label("Instance Id (least)")
    @Description("Least-significant 64 bits of the flow instance UUID")
    /* default */ long instanceIdLeast;

    @Label("Restored")
    @Description("True if the snapshot store had a PARKED row and the saga was restored "
            + "into this engine; false if the wake event referenced an unknown / non-parked instance")
    /* default */ boolean restored;

    @Label("Load Duration (ns)")
    @Description("Wall-clock duration of the snapshot fetch + plan resolution path, in nanoseconds; "
            + "operators can set a JFR threshold to surface slow durable-store reads")
    /* default */ long loadDurationNanos;

    /* default */ static void emit(
            String engineName,
            long instanceIdMost,
            long instanceIdLeast,
            boolean restored,
            long loadDurationNanos) {
        WakeOnLoadFallbackEvent event = new WakeOnLoadFallbackEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.begin();
        event.engineName = engineName;
        event.instanceIdMost = instanceIdMost;
        event.instanceIdLeast = instanceIdLeast;
        event.restored = restored;
        event.loadDurationNanos = loadDurationNanos;
        event.commit();
    }
}
