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
 * Emitted when {@code JdbcEventStreamAppender.append} is rejected with an optimistic-concurrency
 * conflict ({@code EX-EVENT-6008}, ADR-049): the caller's {@code expectedVersion} did not match the
 * stream head ({@link #PHASE_HEAD_MISMATCH}) or a concurrent writer won the INSERT race under
 * {@code READ_COMMITTED} ({@link #PHASE_INSERT_TOCTOU}). Operators watch the rate of this event to
 * detect per-stream contention hotspots.
 *
 * <p><b>Secret-safe.</b> Carries only the stream type qualifier and the version numbers — never the
 * payload bytes or any event field values. Follows the events-package telemetry pattern
 * ({@code CommunityEventPayloadEncodeFailedEvent}): {@code @StackTrace(false)}, guarded by
 * {@link Event#isEnabled()} so a disabled recording costs ~zero.
 *
 * @since 0.10.0
 */
@Name("eu.exeris.kernel.events.EventLogAppendConflict")
@Label("Event-Log Append Conflict")
@Category({"Exeris Kernel", "Events"})
@Description("Emitted when JdbcEventStreamAppender.append is rejected (EX-EVENT-6008) because the "
        + "caller's expectedVersion did not match the stream head (HEAD_MISMATCH) or a concurrent "
        + "writer won the INSERT race (INSERT_TOCTOU). Carries stream type + versions, never payload "
        + "bytes (secret-safe).")
@StackTrace(false)
public final class EventLogAppendConflictEvent extends Event {

    /** Conflict flavor: caller's {@code expectedVersion} did not match the current stream head. */
    public static final String PHASE_HEAD_MISMATCH = "HEAD_MISMATCH";

    /** Conflict flavor: a concurrent writer won the INSERT race on the next sequence (SQLSTATE 23). */
    public static final String PHASE_INSERT_TOCTOU = "INSERT_TOCTOU";

    @Label("Engine Name")
    @Description("Human-readable engine name from EventEngineConfig.engineName()")
    /* default */ String engineName;

    @Label("Conflict Phase")
    @Description("HEAD_MISMATCH for a stale expectedVersion; INSERT_TOCTOU for a lost INSERT race (ADR-049)")
    /* default */ String phase;

    @Label("Stream Type")
    @Description("StreamId.streamType() of the target stream — a type qualifier, never payload content")
    /* default */ String streamType;

    @Label("Expected Version")
    @Description("The stream head the losing writer expected")
    /* default */ long expectedVersion;

    @Label("Actual Version")
    @Description("The stream's actual head at conflict time (the sequence a concurrent writer holds)")
    /* default */ long actualVersion;

    public static void emit(String engineName, String phase, String streamType,
                            long expectedVersion, long actualVersion) {
        EventLogAppendConflictEvent event = new EventLogAppendConflictEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.begin();
        event.engineName = engineName;
        event.phase = phase;
        event.streamType = streamType;
        event.expectedVersion = expectedVersion;
        event.actualVersion = actualVersion;
        event.commit();
    }
}
