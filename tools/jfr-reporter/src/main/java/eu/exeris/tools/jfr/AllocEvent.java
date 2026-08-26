/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.tools.jfr;

import java.util.List;

public record AllocEvent(
        long tEpochMillis,
        String eventType,
        String className,
        String threadName,
        long sizeBytes,
        List<String> stackFrames,
        Category category
) {}
