/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
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
 * JFR event emitted by {@link WatermarkManager} when the memory pressure level
 * transitions to a new {@link WatermarkLevel} (e.g., NORMAL → WARNING → CRITICAL).
 *
 * <h2>JFR-First Principle</h2>
 * <p>This event is the sole mechanism by which the {@code WatermarkManager} makes
 * pressure transitions observable. It is zero-cost when JFR recording is not active.
 * {@code @StackTrace(false)} removes per-event stack-capture overhead from the hot path.
 *
 * <h2>rawArgs Binary Layout (Black-Box Telemetry)</h2>
 * <pre>
 *   allocatedBytes  — long: bytes currently in use
 *   totalBytes      — long: total off-heap budget
 *   utilizationPct  — int:  utilization as integer percentage [0..100]
 *   fromLevel       — String: previous WatermarkLevel name
 *   toLevel         — String: new WatermarkLevel name
 * </pre>
 *
 * @see WatermarkManager
 * @see WatermarkLevel
 * @since 0.5.0
 */
@Name("eu.exeris.kernel.core.MemoryPressure")
@Label("Memory Pressure Transition")
@Category({"Exeris Kernel", "Memory"})
@Description("Emitted when the WatermarkManager detects a memory-pressure level transition.")
@StackTrace(false)
/* default */ final class MemoryPressureEvent extends Event {

    @Label("Allocated Bytes")
    /* default */ long allocatedBytes;

    @Label("Total Budget Bytes")
    /* default */ long totalBytes;

    @Label("Utilization (%)")
    /* default */ int utilizationPct;

    @Label("Previous Level")
    /* default */ String fromLevel;

    @Label("New Level")
    /* default */ String toLevel;

    /**
     * Factory: emits a memory-pressure transition event.
     *
     * @param allocatedBytes current allocated bytes
     * @param totalBytes     total off-heap budget
     * @param fromLevel      previous {@link WatermarkLevel}
     * @param toLevel        new {@link WatermarkLevel}
     */
    /* default */
    static void emit(long allocatedBytes,
                     long totalBytes,
                     WatermarkLevel fromLevel,
                     WatermarkLevel toLevel) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        MemoryPressureEvent evt = new MemoryPressureEvent();
        if (evt.isEnabled()) {
            evt.allocatedBytes = allocatedBytes;
            evt.totalBytes = totalBytes;
            evt.utilizationPct = totalBytes > 0
                    ? (int) (allocatedBytes * 100L / totalBytes)
                    : 0;
            evt.fromLevel = fromLevel.name();
            evt.toLevel = toLevel.name();
            evt.commit();
        }
    }
}
