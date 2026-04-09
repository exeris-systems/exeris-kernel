/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.events.jfr;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted when the Outbox Orchestrator flushes a batch to the broker port.
 *
 * @since 0.5.0
 */
@Label("Outbox Batch Flushed")
@Category({"Exeris", "Events", "Outbox"})
@StackTrace(false)
public final class OutboxBatchFlushedEvent extends Event {

    @Label("Batch Size")
    public int batchSize;

    @Label("Flush Duration Nanos")
    public long flushDurationNanos;

    @Label("Failed Count")
    public int failedCount;
}
