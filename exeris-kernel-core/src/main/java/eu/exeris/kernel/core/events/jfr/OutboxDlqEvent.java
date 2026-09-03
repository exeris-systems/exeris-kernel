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
 * JFR event emitted when an outbox entry is moved to the dead-letter queue after exhausting
 * all delivery retries or receiving an unrecoverable broker rejection.
 */
@Label("Outbox DLQ Transition")
@Category({"Exeris", "Events", "Outbox"})
@StackTrace(false)
public final class OutboxDlqEvent extends Event {

    @Label("Event Type")
    public String eventType;

    @Label("Stream ID High")
    public long streamIdHigh;

    @Label("Stream ID Low")
    public long streamIdLow;

    @Label("Failure Reason")
    public String reason;

    @Label("Retry Count")
    public int retryCount;
}
