/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.events.jfr;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * Emitted when {@code JdbcEventStreamAppender.append} fails for a non-conflict reason (driver
 * timeout, connection lost, retry budget exhausted under contention, etc.) before the appender wraps
 * the cause as an {@code EventEngineException} (ADR-049). Gives operators a post-mortem trail the
 * per-call exception does not — the sibling of {@code FlowSnapshotSaveFailedEvent} (JFR-091).
 *
 * <p><b>Secret-safe.</b> Carries only the stream type qualifier, the SQLSTATE, and the failing
 * exception's class name — never the payload bytes or the failure message (which could echo payload
 * content). {@code @StackTrace(false)}, guarded by {@link Event#isEnabled()}.
 *
 * @since 0.10.0
 */
@Name("eu.exeris.kernel.events.EventLogAppendFailed")
@Label("Event-Log Append Failed")
@Category({"Exeris Kernel", "Events"})
@Description("Emitted when JdbcEventStreamAppender.append fails for a non-conflict reason (driver "
        + "error, retry budget exhausted). Carries stream type, SQLSTATE, and the failing exception "
        + "class — never payload bytes or the failure message (secret-safe).")
@StackTrace(false)
public final class EventLogAppendFailedEvent extends Event {

    @Label("Engine Name")
    @Description("Human-readable engine name from EventEngineConfig.engineName()")
    /* default */ String engineName;

    @Label("Stream Type")
    @Description("StreamId.streamType() of the target stream — a type qualifier, never payload content")
    /* default */ String streamType;

    @Label("SQL State")
    @Description("First SQLSTATE found on the cause chain, or UNKNOWN when none carries one")
    /* default */ String sqlState;

    @Label("Failure Class")
    @Description("Class name of the failing exception; no message is recorded (secret-safe)")
    /* default */ String failureClass;

    public static void emit(String engineName, String streamType, String sqlState, Throwable failure) {
        EventLogAppendFailedEvent event = new EventLogAppendFailedEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.begin();
        event.engineName = engineName;
        event.streamType = streamType;
        event.sqlState = sqlState == null ? "" : sqlState;
        // Class name only — never failure.getMessage(), which may echo payload field values.
        event.failureClass = failure == null ? "" : failure.getClass().getName();
        event.commit();
    }
}
