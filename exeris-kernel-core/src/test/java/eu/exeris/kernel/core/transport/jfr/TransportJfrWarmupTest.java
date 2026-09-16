/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.jfr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract of {@link TransportJfrWarmup}: it must actually run a class's static initialiser, and
 * say so when it cannot.
 *
 * <p>The point of the class is that the initialisation happens <em>here</em> — on the thread that
 * starts an engine — instead of later, on a stream's virtual thread, where a {@code <clinit>} pins
 * the carrier. A warm-up that quietly did nothing would leave that pin exactly where it was, so
 * "it ran the initialiser" is the assertion, not "it did not throw".
 */
@DisplayName("TransportJfrWarmup: initialises event classes at start-up, or fails loudly")
class TransportJfrWarmupTest {

    private static final AtomicBoolean PROBE_INITIALISED = new AtomicBoolean();

    @Test
    @DisplayName("runs the static initialiser of a class that has not run one yet")
    void initialisesAClassThatHasNotBeenInitialised() {
        // A class literal does not initialise the class — this is what makes the assertion real.
        Class<?> probe = Probe.class;
        assertThat(PROBE_INITIALISED)
                .withFailMessage("Probe initialised before the warm-up ran; the assertion below would be vacuous")
                .isFalse();

        TransportJfrWarmup.ensureRegistered(MethodHandles.lookup(), probe);

        assertThat(PROBE_INITIALISED).isTrue();
    }

    @Test
    @DisplayName("a lookup that cannot see the class fails at start-up rather than silently skipping")
    void reportsAnInaccessibleEventClass() {
        assertThatThrownBy(() ->
                TransportJfrWarmup.ensureRegistered(MethodHandles.publicLookup(), Probe.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(Probe.class.getName());
    }

    @Test
    @DisplayName("every Core transport event class in the set initialises, and a second call is a no-op")
    void warmsTheCoreTransportEventClasses() {
        // What this can fail on: an event class whose static initialiser throws, or one the set
        // names but the lookup here cannot reach. Idempotence is asserted by calling it twice.
        assertThatCode(TransportJfrWarmup::ensureRegistered).doesNotThrowAnyException();
        assertThatCode(TransportJfrWarmup::ensureRegistered).doesNotThrowAnyException();
    }

    /** Package-private on purpose: {@link MethodHandles#publicLookup()} must not be able to see it. */
    static final class Probe {

        static {
            PROBE_INITIALISED.set(true);
        }

        private Probe() {
            // Never instantiated — the static initialiser is the whole subject.
        }
    }
}
