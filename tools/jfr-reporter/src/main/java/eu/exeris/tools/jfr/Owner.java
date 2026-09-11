/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.tools.jfr;

import java.util.Locale;

/**
 * Who made an allocation: the category of its <em>owner frame</em>, the first frame outside the
 * JDK and the coverage agent. The type of the object allocated plays no part — that is
 * {@link ObjectKind}, an orthogonal dimension.
 */
public enum Owner {
    /** The owner frame is Exeris runtime code (SPI, Core, Community). */
    PRODUCTION,
    /** The owner frame is a TCK contract, a test binding, a fixture or a test framework. */
    TEST_HARNESS,
    /** The owner frame is a library that is neither Exeris nor the JDK (a driver, a client, a codec). */
    THIRD_PARTY,
    /** Every frame is JDK-internal, or the stack is empty (compiler threads, GC, JFR itself). */
    NO_OWNER;

    /**
     * The JSON spelling: lower-case, as the landing page's type union has always read it.
     *
     * @return the lower-cased enum name
     */
    public String json() {
        return name().toLowerCase(Locale.ROOT);
    }
}
