/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.tools.jfr;

import java.util.List;

/**
 * One allocation event with both attributions applied.
 *
 * @param tEpochMillis  event time
 * @param eventType     the JFR event type name
 * @param className     the allocated class
 * @param threadName    the allocating thread's Java name, or {@code unknown}
 * @param threadId      the allocating thread's Java thread id, or -1
 * @param sizeBytes     the byte figure {@link SizePolicy} chose for this event type
 * @param sizeKind      what that figure means
 * @param objectSize    the {@code allocationSize} field where the event has one, else 0
 * @param stackFrames   the recorded stack, top-most first
 * @param owner         the owner frame, or {@code null}
 * @param ownerCategory who allocated
 * @param objectKind    what was allocated
 */
public record AllocEvent(
        long tEpochMillis,
        String eventType,
        String className,
        String threadName,
        long threadId,
        long sizeBytes,
        SizePolicy.SizeKind sizeKind,
        long objectSize,
        List<Frame> stackFrames,
        Frame owner,
        Owner ownerCategory,
        ObjectKind objectKind
) {}
