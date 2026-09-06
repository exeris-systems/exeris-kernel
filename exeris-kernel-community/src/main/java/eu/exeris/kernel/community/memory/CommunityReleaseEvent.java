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
 * JFR event recording one sampled Community-tier off-heap buffer release.
 *
 * <h2>JFR-First Contract</h2>
 * <p>Every allocation lifecycle event MUST be observable via Java Flight Recorder
 * without any external agent. {@link CommunityReleaseAccounting#release(long)} calls
 * {@link #emit(long, long)} only when the allocator's {@code jfrEnabled} flag is
 * {@code true} and {@link CommunityMemoryJfrSampling#shouldEmit(long)} also returns
 * {@code true} for the release's running count; {@link #emit(long, long)} itself
 * commits only when the Flight Recorder is initialized and this event type is enabled.
 * This is the release-side counterpart of {@link CommunityAllocationEvent}, reached on the
 * 1-to-0 reference-count transition (see {@link CommunityLoanedBuffer#onRelease()}).
 *
 * @since 0.5
 * @see CommunityAllocationEvent
 */
@Name("eu.exeris.kernel.memory.CommunityRelease")
@Label("Community Buffer Release")
@Description("Emitted when CommunityMemoryAllocator releases an off-heap buffer")
@Category({"Exeris Kernel", "Memory"})
@StackTrace(false)
final class CommunityReleaseEvent extends Event {

    @Label("Released Size (bytes)")
    /* default */ long releasedBytes;

    @Label("Total Release Count")
    /* default */ long totalCount;

    /* default */ static void emit(long bytes, long count) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        CommunityReleaseEvent evt = new CommunityReleaseEvent();
        if (evt.isEnabled()) {
            evt.releasedBytes = bytes;
            evt.totalCount    = count;
            evt.commit();
        }
    }
}
