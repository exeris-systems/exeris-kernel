/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.security;

/**
 * Community-local checked exception signalling that a {@link KeySetSource} refresh failed.
 *
 * <p>This is a <em>cold-path</em> signal raised only during a key-set refresh attempt
 * (rotation), never on the hot verification path. The reason string is a short, opaque
 * label and must never carry key material or token bytes (secret-safe).
 *
 * @since 0.9
 */
/* default */ class KeySetRefreshException extends Exception {

    private static final long serialVersionUID = 1L;

    /* default */ KeySetRefreshException(String message) {
        super(message);
    }

    /* default */ KeySetRefreshException(String message, Throwable cause) {
        super(message, cause);
    }
}
