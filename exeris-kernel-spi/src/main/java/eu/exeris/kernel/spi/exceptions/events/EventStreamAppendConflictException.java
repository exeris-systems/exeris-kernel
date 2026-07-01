/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.exceptions.events;

import eu.exeris.kernel.spi.events.EventStreamAppender;
import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Thrown when an optimistic-concurrency {@link EventStreamAppender#append} is rejected because the
 * caller's {@code expectedVersion} did not match the stream's current head sequence (ADR-049).
 *
 * <p>Fail-closed: the event is <b>not</b> appended and the stream head is unchanged. The caller
 * typically re-reads the stream (via {@code EventStreamReader.replayFromVersion}) and retries with
 * the observed head. Appends made with {@link EventStreamAppender#ANY_VERSION} never raise this —
 * they skip the concurrency check.
 *
 * <h2>Hierarchy &amp; java:S110</h2>
 * <p>Extends {@link ExerisKernelException} directly — one level below {@code RuntimeException} in
 * the Kernel tree — to stay within the {@code java:S110} inheritance-depth limit of 5:
 * {@code Object → Throwable → Exception → RuntimeException → ExerisKernelException →
 * EventStreamAppendConflictException}.
 *
 * <h2>rawArgs Binary Layout (Enterprise Glass-Box)</h2>
 * <p>For {@value KernelErrorCodes#EX_EVENT_6008} (version conflict):
 * <ul>
 *   <li>index 0 – {@code String} streamType       (the stream's type qualifier)</li>
 *   <li>index 1 – {@code long}   expectedVersion  (version the caller expected)</li>
 *   <li>index 2 – {@code long}   actualVersion    (the stream's actual head)</li>
 * </ul>
 *
 * @since 0.10.0
 */
public class EventStreamAppendConflictException extends ExerisKernelException {

    private static final String MSG_VERSION_CONFLICT = "Event-log append version conflict";

    /**
     * General-purpose constructor — use {@link #versionConflict(String, long, long)} when possible.
     *
     * @param message static message template
     */
    public EventStreamAppendConflictException(String message) {
        super(KernelErrorCodes.EX_EVENT_6008, message, (Throwable) null);
    }

    /**
     * General-purpose constructor with cause.
     *
     * @param message static message template
     * @param cause   upstream throwable; may be {@code null}
     */
    public EventStreamAppendConflictException(String message, Throwable cause) {
        super(KernelErrorCodes.EX_EVENT_6008, message, cause);
    }

    // Full-args constructor for factory methods — must precede static factory methods per DeclarationOrder
    private EventStreamAppendConflictException(String errorCode, String message, Throwable cause, Object... rawArgs) {
        super(errorCode, message, cause, rawArgs);
    }

    /**
     * Creates an {@code EventStreamAppendConflictException} for a version mismatch on append.
     *
     * <p>Sets error code {@value KernelErrorCodes#EX_EVENT_6008}.
     * rawArgs layout: {@code [String streamType, long expectedVersion, long actualVersion]}.
     *
     * @param streamType      the target stream's type qualifier
     * @param expectedVersion the version the caller passed as {@code expectedVersion}
     * @param actualVersion   the stream's actual current head sequence
     * @return a fully initialised {@link EventStreamAppendConflictException}
     */
    public static EventStreamAppendConflictException versionConflict(
            String streamType, long expectedVersion, long actualVersion) {
        return versionConflict(streamType, expectedVersion, actualVersion, null);
    }

    /**
     * Creates an {@code EventStreamAppendConflictException} for a version mismatch on append,
     * preserving the driver-level cause (e.g. the SQLSTATE-23 integrity violation that revealed a
     * lost INSERT race).
     *
     * <p>Sets error code {@value KernelErrorCodes#EX_EVENT_6008}.
     * rawArgs layout: {@code [String streamType, long expectedVersion, long actualVersion]}.
     *
     * @param streamType      the target stream's type qualifier
     * @param expectedVersion the version the caller passed as {@code expectedVersion}
     * @param actualVersion   the stream's actual current head sequence
     * @param cause           the underlying driver failure that detected the conflict; may be {@code null}
     * @return a fully initialised {@link EventStreamAppendConflictException}
     */
    public static EventStreamAppendConflictException versionConflict(
            String streamType, long expectedVersion, long actualVersion, Throwable cause) {
        return new EventStreamAppendConflictException(KernelErrorCodes.EX_EVENT_6008, MSG_VERSION_CONFLICT, cause,
                streamType, expectedVersion, actualVersion);
    }
}
