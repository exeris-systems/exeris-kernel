/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.events;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.events.EventBusException;
import eu.exeris.kernel.spi.exceptions.events.EventEngineException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    @DisplayName("subscribeScoped() defaults to refusal with EX-EVENT-6011 carrying [eventType]")
    void subscribeScopedDefaultRefuses() {
        assertThatThrownBy(() -> new InheritingBus().subscribeScoped("UserCreated", (descriptor, payload) -> { }))
                .isInstanceOfSatisfying(EventBusException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_EVENT_6011);
                    assertThat(ex.rawArgs()).containsExactly("UserCreated");
                });
    }

    @Test
    @DisplayName("subscribe() is not refused by the scoped default: it stays the unscoped path")
    void subscribeIsNotAffectedByScopedDefault() {
        assertThat(new InheritingBus().subscribe("UserCreated", (descriptor, payload) -> { }))
                .isEqualTo(SubscriptionToken.INVALID);
    }

    @Test
    @DisplayName("EventEngine.isolationRegistry() defaults to refusal with EX-EVENT-6001")
    void isolationRegistryDefaultRefuses() {
        EventEngine engine = new EventEngine() {
            @Override
            public EventBus bus() {
                return new InheritingBus();
            }

            @Override
            public EventQueue queue() {
                return null;
            }

            @Override
            public EventLoop loop() {
                return null;
            }

            @Override
            public EventRegistry registry() {
                return null;
            }

            @Override
            public void start() {
                // No lifecycle: the default isolationRegistry() is what is under test.
            }

            @Override
            public void close() {
                // No lifecycle: nothing was started.
            }

            @Override
            public EventEngineStats stats() {
                return null;
            }
        };
        assertThatThrownBy(engine::isolationRegistry)
                .isInstanceOfSatisfying(EventEngineException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_EVENT_6001));
    }
}
