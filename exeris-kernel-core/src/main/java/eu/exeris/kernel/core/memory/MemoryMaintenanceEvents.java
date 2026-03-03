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
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * Package-private JFR event definitions for {@link MemoryMaintenanceTask}.
 *
 * <p>Extracted to a separate file to keep {@link MemoryMaintenanceTask} under the
 * PMD {@code TooManyMethods} threshold.
 *
 * @since 0.5.0
 */
@SuppressWarnings("PMD.MissingStaticMethodInNonInstantiatableClass")
final class MemoryMaintenanceEvents {
    private MemoryMaintenanceEvents() {
        /* This utility class should not be instantiated */
    }


    // =========================================================================
    // Cycle event
    // =========================================================================

    @Name("eu.exeris.kernel.core.MemoryMaintenanceCycle")
    @Label("Memory Maintenance Cycle")
    @Category({"Exeris Kernel", "Memory"})
    @Description("Emitted on each full maintenance cycle: performMaintenance() + watermark refresh.")
    @StackTrace(false)
    /* default */ static final class CycleEvent extends Event {

        @Label("Watermark Level")
        /* default */ String watermarkLevel;

        @Label("Cycle Duration (µs)")
        /* default */ long durationUs;

        @Label("Allocated Bytes")
        /* default */ long allocatedBytes;

        @Label("Total Budget Bytes")
        /* default */ long totalBytes;

        /* default */
        static void emit(WatermarkLevel level, long durationUs,
                         long allocatedBytes, long totalBytes) {
            CycleEvent evt = new CycleEvent();
            evt.watermarkLevel = level.name();
            evt.durationUs = durationUs;
            evt.allocatedBytes = allocatedBytes;
            evt.totalBytes = totalBytes;
            evt.commit();
        }
    }

    // =========================================================================
    // Failure event
    // =========================================================================

    @Name("eu.exeris.kernel.core.MemoryMaintenanceFailure")
    @Label("Memory Maintenance Failure")
    @Category({"Exeris Kernel", "Memory"})
    @Description("Emitted when a maintenance task throws an unexpected exception.")
    @StackTrace(true)
    /* default */ static final class FailureEvent extends Event {

        @Label("Exception Class")
        /* default */ String exceptionClass;

        @Label("Exception Message")
        /* default */ String exceptionMessage;

        /* default */
        static void emit(RuntimeException runtimeEx) {
            FailureEvent evt = new FailureEvent();
            evt.exceptionClass = runtimeEx.getClass().getName();
            evt.exceptionMessage = runtimeEx.getMessage();
            evt.commit();
        }
    }
}
