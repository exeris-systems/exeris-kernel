/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.events;

import java.util.Objects;

/**
 * Immutable registration record for one tenant isolation key (ADR-101).
 *
 * <h2>Why an ordinal</h2>
 * <p>{@link EventDescriptor#isolationOrdinal()} is a primitive, so the bus can test a subscriber's
 * scope with an integer comparison. The string key is resolved to that ordinal once, at
 * registration, and the ordinal is what travels on the descriptor, in the outbox and on the wire.
 *
 * @param isolationKey the tenant isolation key, as carried by
 *                     {@link eu.exeris.kernel.spi.security.StorageContext#isolationKey()}; never
 *                     {@code null} or blank
 * @param ordinal      the positive ordinal the application assigns to this key; {@code 0} is the
 *                     reserved unscoped value and is refused
 *
 * @implSpec The compact constructor rejects a {@code null} key with {@link NullPointerException}
 *           and a blank key or a non-positive ordinal with {@link IllegalArgumentException}.
 * @since 0.13
 * @see IsolationKeyRegistry
 */
public value record IsolationKeySpec(String isolationKey, int ordinal) {

    /**
     * Compact constructor — validates both components so a spec in the registry is always usable
     * as a routing key.
     *
     * @throws NullPointerException     if {@code isolationKey} is {@code null}
     * @throws IllegalArgumentException if {@code isolationKey} is blank or {@code ordinal} is not
     *                                  positive
     */
    public IsolationKeySpec {
        Objects.requireNonNull(isolationKey, "isolationKey");
        if (isolationKey.isBlank()) {
            throw new IllegalArgumentException("isolationKey must not be blank");
        }
        if (ordinal <= 0) {
            throw new IllegalArgumentException("ordinal must be positive, was " + ordinal);
        }
    }

    /**
     * Creates a registration record for an isolation key.
     *
     * @param isolationKey the tenant isolation key (non-null, non-blank)
     * @param ordinal      the positive ordinal assigned to the key
     * @return the validated spec
     */
    @SuppressWarnings("PMD.ShortMethodName") // 'of' is a standard Java factory idiom (cf. List.of, Map.of)
    public static IsolationKeySpec of(String isolationKey, int ordinal) {
        return new IsolationKeySpec(isolationKey, ordinal);
    }
}
