/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.tools.jfr;

import java.time.Instant;
import java.util.List;

/**
 * One allocation event with both attributions applied.
 *
 * @param t             event time, at JFR's own resolution
 * @param eventType     the JFR event type name
 * @param className     the allocated class
 * @param threadName    the allocating thread's Java name, or {@code unknown}
 * @param threadId      the allocating thread's Java thread id, or -1
 * @param sizeBytes     the byte figure {@link SizePolicy} chose for this event type
 * @param sizeKind      what that figure means
 * @param stackFrames   the recorded stack, top-most first
 * @param owner         the owner frame, or {@code null}
 * @param ownerCategory who allocated
 * @param objectKind    what was allocated
 */
public record AllocEvent(
        Instant t,
        String eventType,
        String className,
        String threadName,
        long threadId,
        long sizeBytes,
        SizePolicy.SizeKind sizeKind,
        List<Frame> stackFrames,
        Frame owner,
        Owner ownerCategory,
        ObjectKind objectKind
) {}
