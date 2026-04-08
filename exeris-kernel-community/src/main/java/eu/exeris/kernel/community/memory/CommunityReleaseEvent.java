/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 * JFR event emitted for sampled Community-tier buffer release.
 *
 * <h2>JFR-First Contract</h2>
 * <p>Every allocation lifecycle event MUST be observable via Java Flight Recorder
 * without any external agent. This event is the release-side counterpart of
 * {@link CommunityAllocationEvent} and is emitted when a buffer's reference count
 * drops to zero and the buffer is released.
 *
 * @since 0.5.0
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
