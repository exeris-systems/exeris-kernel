/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Self-test of the instrument, not of its predicate: a real class-initialisation pin, produced on a
 * real virtual thread, must travel through {@link JfrPinningMonitor#measure} and come out on the
 * side that does not fail a fence.
 *
 * <p>{@link CarrierPinClassification} is tested on strings. That leaves the wiring untested — a
 * classifier that is never consulted, or consulted with the wrong argument, would pass every test
 * there and fail every fence here. So this provokes the pin rather than describing one: a class
 * whose static initialiser sleeps is touched by two virtual threads, which pins the first for the
 * whole of the {@code <clinit>} and the second for the rest of its wait.
 *
 * @see JfrPinningMonitor
 */
@DisplayName("JfrPinningMonitor: a real class-initialisation pin is reported, and does not fail the fence")
class JfrPinningMonitorClassInitSelfTest {

    /** Long enough that the pin is far past the fence, short enough to stay a unit test. */
    private static final long CLINIT_MILLIS = 200L;

    private static final AtomicBoolean INITIALISED = new AtomicBoolean();

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("both virtual threads pin on the initialisation, and hasPinning() stays false")
    void classInitialisationPinIsSetAside() throws Exception {
        JfrPinningMonitor.Result result = JfrPinningMonitor.measure(
                JfrPinningMonitor.Config.defaults("self-test-class-init"),
                JfrPinningMonitorClassInitSelfTest::touchSlowClassFromTwoVirtualThreads);

        try {
            assertThat(INITIALISED)
                    .withFailMessage("the workload did not run the static initialiser it exists to provoke")
                    .isTrue();
            assertThat(result.classInitEvents())
                    .withFailMessage("no class-initialisation pin was captured — the provocation stopped working, "
                            + "and this test would then prove nothing about the classification")
                    .isNotEmpty();
            assertThat(result.classInitEvents()).allSatisfy(pin -> {
                assertThat(pin.classInit()).isTrue();
                assertThat(pin.pinnedReason()).isNotBlank();
            });
            assertThat(result.pinnedEvents())
                    .withFailMessage("a class-initialisation pin was counted against the fence: %s",
                            result.pinnedEvents())
                    .isEmpty();
            assertThat(result.hasPinning()).isFalse();
        } finally {
            Files.deleteIfExists(result.jfrPath());
        }
    }

    private static void touchSlowClassFromTwoVirtualThreads() throws InterruptedException {
        Thread first = Thread.ofVirtual().name("self-test-clinit-owner").start(SlowInit::touch);
        // Long enough for the first thread to be inside the static initialiser, so the second one
        // waits on it rather than racing to run it.
        Thread.sleep(CLINIT_MILLIS / 4);
        Thread second = Thread.ofVirtual().name("self-test-clinit-waiter").start(SlowInit::touch);
        first.join();
        second.join();
    }

    /** A class whose initialisation is slow on purpose; touching it is what produces the pin. */
    private static final class SlowInit {

        static {
            try {
                Thread.sleep(CLINIT_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            INITIALISED.set(true);
        }

        private SlowInit() {
            // Never instantiated — the static initialiser is the whole subject.
        }

        static void touch() {
            // Referencing the class is the point; the body is deliberately empty.
        }
    }
}
