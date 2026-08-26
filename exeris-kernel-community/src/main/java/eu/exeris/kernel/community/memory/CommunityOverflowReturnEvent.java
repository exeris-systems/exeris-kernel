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
 * JFR event emitted when returning an overflow-sized segment to the shard pool.
 *
 * <h2>Safety Net</h2>
 * <p>Segments larger than the largest size class are not pooled. This event
 * provides visibility into these overflow returns without changing pooling
 * behavior.
 *
 * @since 0.5.0
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
