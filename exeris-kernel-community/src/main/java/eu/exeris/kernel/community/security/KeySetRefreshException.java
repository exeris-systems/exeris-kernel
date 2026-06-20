/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.security;

/**
 * Community-local checked exception signalling that a {@link KeySetSource} refresh failed.
 *
 * <p>This is a <em>cold-path</em> signal raised only during a key-set refresh attempt
 * (rotation), never on the hot verification path. The reason string is a short, opaque
 * label and must never carry key material or token bytes (secret-safe).
 *
 * @since 0.9.0
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
