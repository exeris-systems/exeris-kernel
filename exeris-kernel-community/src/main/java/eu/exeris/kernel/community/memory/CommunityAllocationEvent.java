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
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted on every Community-tier buffer allocation.
 *
 * <h2>JFR-First Contract</h2>
 * <p>Every allocation lifecycle event MUST be observable via Java Flight Recorder
 * without any external agent. This event is emitted on the hot path only when
 * {@code MemoryProviderConfig#jfrEnabled()} is {@code true}.
 *
 * @since 0.5.0
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
        CommunityAllocationEvent evt = new CommunityAllocationEvent();
        if (evt.isEnabled()) {
            evt.allocationBytes = bytes;
            evt.totalCount      = count;
            evt.commit();
        }
    }
}

