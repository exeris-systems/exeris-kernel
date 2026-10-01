/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.events;

/**
 * Opaque unsubscription handle returned by {@link EventBus#subscribe}.
 *
 * <h2>Design</h2>
 * <p>The token encodes the bus instance identity ({@code busId}) and the subscription
 * slot within that bus ({@code subscriptionOrdinal}). This enables O(1) unsubscription
 * without requiring a reverse lookup of the handler object.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>This record uses only primitive fields and is suitable for future migration to
 * {@code value record}. No identity operations must be performed on it.
 *
 * @param busId               identifier of the {@link EventBus} instance that issued this token
 * @param subscriptionOrdinal the slot ordinal within the bus's subscriber table
 *
 * @since 0.5
 * @see EventBus#unsubscribe(SubscriptionToken)
 */
public record SubscriptionToken(int busId, long subscriptionOrdinal) {

    /**
     * A sentinel token representing an invalid / already-unsubscribed subscription.
     * Passing this token to {@link EventBus#unsubscribe} is a safe no-op.
     */
    public static final SubscriptionToken INVALID = new SubscriptionToken(-1, -1L);

    /**
     * Distinguishes a token that names a subscription slot from the {@link #INVALID} sentinel.
     *
     * @return {@code true} when both components are non-negative, meaning the token addresses a
     *         real slot. It does not follow that the subscription is still live — an
     *         already-unsubscribed token stays structurally valid
     */
    public boolean isValid() {
        return busId >= 0 && subscriptionOrdinal >= 0L;
    }
}

