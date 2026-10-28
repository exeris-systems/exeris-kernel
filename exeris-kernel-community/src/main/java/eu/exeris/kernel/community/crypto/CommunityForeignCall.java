/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.crypto;

import java.lang.foreign.FunctionDescriptor;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Descriptor table for Panama foreign downcalls and upcalls used by the Community crypto driver.
 *
 * <p>Centralises descriptor creation in static initialization to prevent ad-hoc allocation and
 * allow deterministic reachability metadata extraction for GraalVM native image compilation.
 *
 * @since 0.13
 */
enum CommunityForeignCall {

    SSL_SET_FD(
            "SSL_set_fd",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT),
            Resolution.REQUIRED,
            null,
            null
    ),
    ALPN_SELECT_CALLBACK(
            null,
            FunctionDescriptor.of(
                    JAVA_INT,
                    JAVA_LONG, JAVA_LONG, JAVA_LONG,
                    JAVA_LONG, JAVA_INT, JAVA_LONG
            ),
            null,
            CommunityAlpnSelector.class,
            "selectCallback"
    );

    /** Whether a missing downcall symbol fails the provider's construction. */
    /* default */ enum Resolution {
        /** A missing symbol fails construction with {@code EX-NET-2002}. */
        REQUIRED,
        /** A missing symbol is tolerated by the caller. */
        OPTIONAL
    }

    private final String symbol;
    private final FunctionDescriptor descriptor;
    private final Resolution resolution;
    private final Class<?> targetClass;
    private final String targetMethod;

    CommunityForeignCall(
            String symbol,
            FunctionDescriptor descriptor,
            Resolution resolution,
            Class<?> targetClass,
            String targetMethod
    ) {
        this.symbol = symbol;
        this.descriptor = descriptor;
        this.resolution = resolution;
        this.targetClass = targetClass;
        this.targetMethod = targetMethod;
    }

    /* default */ String symbol() {
        return symbol;
    }

    /* default */ FunctionDescriptor descriptor() {
        return descriptor;
    }

    /** The resolution of a downcall row; {@code null} for an upcall, which has no symbol to resolve. */
    /* default */ Resolution resolution() {
        return resolution;
    }

    /* default */ Class<?> targetClass() {
        return targetClass;
    }

    /* default */ String targetMethod() {
        return targetMethod;
    }

    /* default */ boolean isUpcall() {
        return targetClass != null;
    }
}
