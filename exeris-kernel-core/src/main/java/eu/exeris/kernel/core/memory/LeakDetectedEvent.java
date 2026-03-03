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
 * JFR event emitted by {@link LeakTracker} when a {@link eu.exeris.kernel.spi.memory.LoanedBuffer}
 * is garbage-collected without having been closed (unclosed off-heap segment detected).
 *
 * <h2>EX-MEM-1001 Protocol</h2>
 * <p>Per {@code docs/subsystems/memory.md}, error code {@code EX-MEM-1001} is associated with
 * off-heap memory leaks. This JFR event is the sole observability mechanism for PARANOID mode
 * leak detection — it carries the allocation stack trace captured at buffer creation time.
 *
 * <h2>JFR-First Principle</h2>
 * <p>{@code @StackTrace(true)} is intentional here: the kernel-created allocation stack trace
 * is stored as the {@code allocationStack} field, <em>not</em> the JFR stack-capture mechanism.
 * The JFR stack is enabled to capture the Cleaner callback context for correlation.
 *
 * <h2>rawArgs Binary Layout (Black-Box Telemetry)</h2>
 * <pre>
 *   bufferLabel       — String: opaque label assigned at creation (hex address or class name)
 *   allocationStack   — String: stack trace captured at allocation time (PARANOID mode only)
 *   capacityBytes     — long:   size of the leaked segment
 * </pre>
 *
 * @see LeakTracker
 * @since 0.5.0
 */
@Name("eu.exeris.kernel.core.BufferLeak")
@Label("LoanedBuffer Leak Detected")
@Category({"Exeris Kernel", "Memory"})
@Description("EX-MEM-1001: A LoanedBuffer was garbage-collected without being closed (off-heap segment leaked).")
@StackTrace(true)
/* default */ final class LeakDetectedEvent extends Event {

    @Label("Buffer Label")
    /* default */ String bufferLabel;

    @Label("Allocation Stack Trace")
    /* default */ String allocationStack;

    @Label("Capacity (bytes)")
    /* default */ long capacityBytes;

    /**
     * Factory: emits a leak-detected event for a finalized buffer.
     *
     * @param bufferLabel     label assigned at buffer creation
     * @param allocationStack stack trace string (from PARANOID mode Cleaner registration)
     * @param capacityBytes   byte size of the leaked segment
     */
    /* default */
    static void emit(String bufferLabel, String allocationStack, long capacityBytes) {
        LeakDetectedEvent evt = new LeakDetectedEvent();
        evt.bufferLabel = bufferLabel;
        evt.allocationStack = allocationStack;
        evt.capacityBytes = capacityBytes;
        evt.commit();
    }
}
