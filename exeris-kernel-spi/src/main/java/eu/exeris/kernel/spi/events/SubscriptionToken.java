/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 * @since 0.5.0
 * @see EventBus#unsubscribe(SubscriptionToken)
 */
public record SubscriptionToken(int busId, long subscriptionOrdinal) {

    /**
     * A sentinel token representing an invalid / already-unsubscribed subscription.
     * Passing this token to {@link EventBus#unsubscribe} is a safe no-op.
     */
    public static final SubscriptionToken INVALID = new SubscriptionToken(-1, -1L);

    /**
     * Returns {@code true} if this token is valid (i.e. not the {@link #INVALID} sentinel).
     *
     * @return {@code true} if the subscription is potentially active
     */
    public boolean isValid() {
        return busId >= 0 && subscriptionOrdinal >= 0L;
    }
}

