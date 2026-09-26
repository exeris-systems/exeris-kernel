/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.memory;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event recording one sampled Community-tier off-heap buffer allocation.
 *
 * <h2>JFR-First Contract</h2>
 * <p>Every allocation lifecycle event MUST be observable via Java Flight Recorder
 * without any external agent. {@link CommunityAllocatorSupport#trackAllocation}
 * calls {@link #emit(long, long)} only when the allocator's {@code jfrEnabled} flag
 * is {@code true} and {@link CommunityMemoryJfrSampling#shouldEmit(long)} also
 * returns {@code true} for the allocation's running count; {@link #emit(long, long)}
 * itself commits only when the Flight Recorder is initialized and this event type
 * is enabled.
 *
 * @since 0.5
 */
@Name("eu.exeris.kernel.memory.CommunityAllocation")
@Label("Community Buffer Allocation")
@Description("Emitted when CommunityMemoryAllocator acquires an off-heap buffer")
@Category({"Exeris Kernel", "Memory"})
@StackTrace(false) // Disabled for hot-path — too expensive
final class CommunityAllocationEvent extends Event {

    @Label("Allocation Size (bytes)")
    /* default */ long allocationBytes;

    @Label("Total Allocation Count")
    /* default */ long totalCount;

    /* default */ static void emit(long bytes, long count) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        CommunityAllocationEvent evt = new CommunityAllocationEvent();
        if (evt.isEnabled()) {
            evt.allocationBytes = bytes;
            evt.totalCount      = count;
            evt.commit();
        }
    }
}
