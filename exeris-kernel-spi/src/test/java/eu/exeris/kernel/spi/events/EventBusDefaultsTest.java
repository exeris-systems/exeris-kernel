/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for the {@link EventBus} default methods, exercised through a minimal
 * implementation so only the interface defaults are under test.
 */
@DisplayName("EventBus — default methods")
class EventBusDefaultsTest {

    /** Minimal SPI-only bus: supplies the abstract operations, inherits every default. */
    private static final class InheritingBus implements EventBus {

        @Override
        public void publish(EventDescriptor descriptor, EventPayload payload) {
            payload.close();
        }

        @Override
        public SubscriptionToken subscribe(String eventType, EventHandler handler) {
            return SubscriptionToken.INVALID;
        }

        @Override
        public void unsubscribe(SubscriptionToken token) {
            // Nothing is ever subscribed, so there is nothing to revoke.
        }

        @Override
        public void publishAndAwait(EventDescriptor descriptor, EventPayload payload) {
            payload.close();
        }
    }

    @Test
    @DisplayName("isBrokered() defaults to false: a bus dispatches to its handlers itself unless it says otherwise")
    void isBrokeredDefaultsToFalse() {
        assertThat(new InheritingBus().isBrokered()).isFalse();
    }
}
