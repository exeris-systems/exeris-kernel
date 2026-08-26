/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.testkit.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Direct coverage for the pump's failure and interrupt branches.
 *
 * <p>The consumer integration test only exercises the happy path. What actually matters here is the
 * opposite: work that fails inside the kernel scope must surface on the thread that submitted it,
 * because otherwise a failing assertion inside {@code runInKernelScope} disappears onto the fixture
 * thread and the test passes green while proving nothing.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
@DisplayName("KernelScopePump")
class KernelScopePumpTest {

    private static final long TIMEOUT_SECONDS = 5L;

    @Nested
    @DisplayName("Failures reach the submitting thread")
    class FailurePropagation {

        @Test
        @DisplayName("a RuntimeException is rethrown unwrapped")
        void runtimeExceptionRethrownUnwrapped() throws InterruptedException {
            RuntimeException thrown = new IllegalArgumentException("boom");

            assertThatThrownBy(() -> runOnPump(() -> {
                throw thrown;
            })).isSameAs(thrown);
        }

        @Test
        @DisplayName("an Error is rethrown unwrapped — an AssertionError must stay an AssertionError")
        void errorRethrownUnwrapped() throws InterruptedException {
            AssertionError thrown = new AssertionError("assertion inside the scope");

            assertThatThrownBy(() -> runOnPump(() -> {
                throw thrown;
            }))
                    .as("JUnit reports a failed assertion only if it arrives as AssertionError; wrapping "
                            + "it would downgrade a failure into an error with a misleading message")
                    .isSameAs(thrown);
        }

        @Test
        @DisplayName("a checked Throwable is wrapped, keeping the original as cause")
        void checkedThrowableWrapped() throws InterruptedException {
            Throwable thrown = new Exception("checked");

            assertThatThrownBy(() -> runOnPump(() -> sneakyThrow(thrown)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Work inside the kernel scope failed")
                    .hasCause(thrown);
        }

        @Test
        @DisplayName("work that succeeds throws nothing and actually ran")
        void successfulWorkRuns() throws InterruptedException {
            AtomicBoolean ran = new AtomicBoolean(false);

            runOnPump(() -> ran.set(true));

            assertThat(ran)
                    .as("without this control the three cases above would also pass against a pump that "
                            + "never runs anything and always throws")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("requestStop releases the pumping thread")
        void requestStopReleasesPump() throws InterruptedException {
            KernelScopePump pump = new KernelScopePump();
            Thread pumpThread = startPump(pump);

            pump.requestStop();
            pumpThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));

            assertThat(pumpThread.isAlive()).isFalse();
        }

        @Test
        @DisplayName("an interrupt ends the pump and leaves the interrupt flag set")
        void interruptEndsPumpAndPreservesFlag() throws InterruptedException {
            KernelScopePump pump = new KernelScopePump();
            AtomicBoolean interruptFlagAtExit = new AtomicBoolean(false);
            CountDownLatch finished = new CountDownLatch(1);

            Thread pumpThread = Thread.ofPlatform().start(() -> {
                pump.pumpUntilStopped();
                interruptFlagAtExit.set(Thread.currentThread().isInterrupted());
                finished.countDown();
            });

            // Give the pump time to park in take() so the interrupt lands on the blocking call.
            Thread.onSpinWait();
            pumpThread.interrupt();

            assertThat(finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(interruptFlagAtExit)
                    .as("swallowing the interrupt would strand a caller that relies on it to unwind")
                    .isTrue();
        }

        @Test
        @DisplayName("a timed-out submission is withdrawn, not left for the pump to run later")
        void timedOutWorkIsWithdrawn() throws InterruptedException {
            KernelScopePump pump = new KernelScopePump();
            AtomicBoolean abandonedBodyRan = new AtomicBoolean(false);

            // No pump running yet, so the submission cannot be taken and the wait must expire.
            assertThatThrownBy(() ->
                    pump.submitAndWait(() -> abandonedBodyRan.set(true), 1L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Timed out");

            Thread pumpThread = startPump(pump);
            try {
                // A round trip the pump must complete: it proves the pump drained everything queued
                // before this point, which is what makes the assertion below a fact rather than a race.
                pump.submitAndWait(() -> { }, TIMEOUT_SECONDS);
            } finally {
                pump.requestStop();
                pumpThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            }

            assertThat(abandonedBodyRan)
                    .as("left queued, an abandoned body runs against fixture state the caller has "
                        + "already given up on — and, since the pump is single-consumer, it runs "
                        + "ahead of the next submission, whose own wait then starts behind work "
                        + "nobody is waiting for. One timeout becomes a cascade.")
                    .isFalse();
        }
    }

    private static void runOnPump(Runnable body) throws InterruptedException {
        KernelScopePump pump = new KernelScopePump();
        Thread pumpThread = startPump(pump);
        try {
            pump.submitAndWait(body, TIMEOUT_SECONDS);
        } finally {
            pump.requestStop();
            pumpThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        }
    }

    private static Thread startPump(KernelScopePump pump) {
        return Thread.ofPlatform().start(pump::pumpUntilStopped);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable thrown) throws T {
        throw (T) thrown;
    }
}
