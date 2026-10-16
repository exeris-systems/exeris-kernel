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
            CommunityAlpnSelector.class,
            "selectCallback"
    );

    private final String symbol;
    private final FunctionDescriptor descriptor;
    private final Class<?> targetClass;
    private final String targetMethod;

    CommunityForeignCall(
            String symbol,
            FunctionDescriptor descriptor,
            Class<?> targetClass,
            String targetMethod
    ) {
        this.symbol = symbol;
        this.descriptor = descriptor;
        this.targetClass = targetClass;
        this.targetMethod = targetMethod;
    }

    /* default */ String symbol() {
        return symbol;
    }

    /* default */ FunctionDescriptor descriptor() {
        return descriptor;
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
