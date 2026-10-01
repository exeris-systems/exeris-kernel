/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.tools.jfr;

import java.util.Locale;

/**
 * What was allocated, by the class of the object. A secondary dimension next to {@link Owner}: a
 * {@code VirtualThread} allocated by {@code InMemoryEventBus.publish} is {@link Owner#PRODUCTION}
 * of kind {@link #LOOM}.
 */
public enum ObjectKind {
    /** An {@code eu.exeris.*} object — the only kind the TCK's own contract count sees. */
    EXERIS,
    /** Virtual-thread machinery: the thread, its continuation, stack chunks, the fork-join adapters. */
    LOOM,
    /** Foreign-memory machinery: segments, arenas, sessions. */
    PANAMA,
    /** A primitive or reference array. */
    ARRAY,
    /** Any other JDK class. */
    JDK,
    /** Anything else — third-party library objects. */
    OTHER;

    /**
     * The JSON spelling: lower-case.
     *
     * @return the lower-cased enum name
     */
    public String json() {
        return name().toLowerCase(Locale.ROOT);
    }
}
