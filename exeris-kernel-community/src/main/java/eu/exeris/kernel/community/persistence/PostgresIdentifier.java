/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.persistence;

/**
 * Community: validation of PostgreSQL identifiers that must be interpolated into SQL text.
 *
 * <p>Exists because a schema name cannot be passed as a bind parameter — {@code SET search_path} takes an
 * identifier, not a value — so the only defence against injection is refusing anything that is not a plain
 * identifier. Deliberately stricter than PostgreSQL itself: no quoting, no uppercase, no dollar signs and
 * no Unicode, because the kernel never needs them and every character allowed is one an attacker can try.
 *
 * @since 0.11.0
 */
final class PostgresIdentifier {

    private static final int MAX_LENGTH = 63;

    private PostgresIdentifier() {
        // Utility class — not instantiable.
    }

    /**
     * Whether {@code identifier} is safe to interpolate directly into SQL text.
     *
     * @param identifier candidate identifier; may be {@code null}
     * @return {@code true} only for {@code [a-z][a-z0-9_]{0,62}}
     */
    /* default */ static boolean isSafe(String identifier) {
        if (identifier == null) {
            return false;
        }
        int length = identifier.length();
        if (length < 1 || length > MAX_LENGTH) {
            return false;
        }
        if (!isLowerAlpha(identifier.charAt(0))) {
            return false;
        }
        for (int i = 1; i < length; i++) {
            if (!isIdentifierPart(identifier.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLowerAlpha(char candidate) {
        return candidate >= 'a' && candidate <= 'z';
    }

    private static boolean isIdentifierPart(char candidate) {
        return isLowerAlpha(candidate)
                || (candidate >= '0' && candidate <= '9')
                || candidate == '_';
    }
}
