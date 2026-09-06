/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.events.jfr;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted once per flush cycle by {@code OutboxBatchFlusher.flush(List)}, in a
 * {@code finally} block that runs whether the broker's batch publish succeeded, partially
 * failed, or threw.
 *
 * @since 0.5
 */
@Label("Outbox Batch Flushed")
@Category({"Exeris", "Events", "Outbox"})
@StackTrace(false)
public final class OutboxBatchFlushedEvent extends Event {

    /**
     * Count of entries delivered by the initial batch {@code OutboxBrokerPort.publish(List)}
     * call in this flush cycle — the batch size minus {@link #failedCount}, not the number of
     * entries the flush cycle started with. An entry that fails this initial call but is later
     * recovered by the per-event retry loop is not added back into this count.
     */
    @Label("Batch Size")
    public int batchSize;

    /**
     * Nanoseconds from the start of the flush cycle to this event's emission, including any
     * per-event retries and their backoff delays for entries the initial publish call did not
     * deliver.
     */
    @Label("Flush Duration Nanos")
    public long flushDurationNanos;

    /**
     * Count of entries the initial batch publish call did not deliver in this flush cycle.
     * Every one of these is routed to the per-event retry loop; this count is not reduced when
     * a retry later succeeds, and does not equal the number that end up in the DLQ — see
     * {@code OutboxDlqEvent} for the terminal per-entry outcome.
     */
    @Label("Failed Count")
    public int failedCount;
}
