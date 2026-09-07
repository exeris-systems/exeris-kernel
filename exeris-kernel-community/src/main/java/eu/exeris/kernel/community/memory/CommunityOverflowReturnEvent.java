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
 * JFR event recording one overflow-sized segment returned to
 * {@link CommunityArenaShardPool}.
 *
 * <h2>Safety Net</h2>
 * <p>Segments larger than the pool's largest size class are never added to a free-list
 * bucket — {@link CommunityArenaShardPool#returnSegment} only accounts for them via this
 * event; the segment itself is reclaimed only when the pool's shard {@code Arena} closes.
 *
 * @since 0.5
 */
@Name("eu.exeris.kernel.memory.CommunityOverflowReturn")
@Label("Community Overflow Return")
@Description("Emitted when CommunityArenaShardPool receives a non-poolable returned segment")
@Category({"Exeris Kernel", "Memory"})
@StackTrace(false)
final class CommunityOverflowReturnEvent extends Event {

    @Label("Returned Bytes")
    /* default */ long returnedBytes;

    @Label("Total Overflow Returns")
    /* default */ long totalOverflowReturns;

    @Label("Total Overflow Returned Bytes")
    /* default */ long totalOverflowReturnedBytes;

    /* default */ static void emit(long returned,
                                   long totalReturns,
                                   long totalReturnedBytes) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        CommunityOverflowReturnEvent evt = new CommunityOverflowReturnEvent();
        if (evt.isEnabled()) {
            evt.returnedBytes = returned;
            evt.totalOverflowReturns = totalReturns;
            evt.totalOverflowReturnedBytes = totalReturnedBytes;
            evt.commit();
        }
    }
}
