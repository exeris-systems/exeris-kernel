/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The scope every {@code Abstract*Tck} fixture forks through, tested in its own right.
 *
 * <p>It had none. That is worse here than in an ordinary helper: this module has no {@code src/main},
 * so the test-jar containing this class is a <em>published artifact</em>, and a provider author
 * proving conformance runs their contract assertions through it. A scope that silently fails to
 * cancel, or reports a clean teardown over live threads, does not fail their build — it changes what
 * their build proves.
 */
@DisplayName("TckScope — fan-out, cancellation, and failure reporting")
class TckScopeTest {

    private static final long AWAIT_SECONDS = 10L;

    @Nested
    @DisplayName("Cancellation")
    class Cancellation {

        @Test
        @Timeout(value = 20, unit = TimeUnit.SECONDS)
        @DisplayName("fail-fast cancels the siblings instead of waiting them out")
        void failFastCancelsSiblings() throws InterruptedException {
            AtomicBoolean sawInterrupt = new AtomicBoolean();
            CountDownLatch siblingParked = new CountDownLatch(1);

            TckScope scope = TckScope.openFailFast();
            scope.fork(() -> {
                siblingParked.countDown();
                try {
                    // Longer than any plausible suite: if cancellation does not arrive, the fixture
                    // that failed is reported as a timeout rather than as its assertion error.
                    Thread.sleep(TimeUnit.MINUTES.toMillis(5));
                } catch (InterruptedException e) {
                    sawInterrupt.set(true);
                    Thread.currentThread().interrupt();
                }
                return null;
            });
            assertThat(siblingParked.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            scope.fork(() -> {
                throw new IllegalStateException("the assertion that actually failed");
            });

            assertThatThrownBy(scope::join)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("the assertion that actually failed");
            assertThat(sawInterrupt)
                    .as("waiting all tasks out before rethrowing is what turns a failed assertion "
                        + "into an opaque suite timeout")
                    .isTrue();
        }

        @Test
        @Timeout(value = 20, unit = TimeUnit.SECONDS)
        @DisplayName("a task forked after cancellation never runs")
        void forkAfterCancellationIsANoOp() throws InterruptedException {
            AtomicBoolean lateTaskRan = new AtomicBoolean();

            TckScope scope = TckScope.openCancelOnFailure();
            scope.fork(() -> {
                throw new IllegalStateException("trigger");
            });
            // join() first, so cancellation is a completed fact rather than a race: cancelSiblings
            // runs inside the failing task, and join returns only once that task has finished.
            // Polling some proxy for "has it cancelled yet" is what made the first version of this
            // test vacuous — the predicate was a stub that always answered yes.
            scope.join();

            scope.fork(() -> {
                lateTaskRan.set(true);
                return null;
            });
            scope.join();

            assertThat(lateTaskRan)
                    .as("a scope that keeps accepting work after deciding to tear down leaves the "
                        + "fixture asserting against a moving target")
                    .isFalse();
        }

        // NOT covered: that the cascade runs once rather than once per failure. An already
        // interrupted-and-exited thread swallows further interrupts, so a counting probe reads the
        // same whether cancelSiblings is one-shot or not. The CAS is still there and still correct;
        // it simply has no assertion that would fail without it, and a test that cannot fail is
        // worse than an acknowledged gap.
    }

    @Nested
    @DisplayName("Failure reporting")
    class FailureReporting {

        @Test
        @Timeout(value = 20, unit = TimeUnit.SECONDS)
        @DisplayName("aggregating reports every failure, not just the first")
        void aggregateKeepsAllFailures() {
            TckScope scope = TckScope.open();
            scope.fork(() -> {
                throw new IllegalStateException("first");
            });
            scope.fork(() -> {
                throw new IllegalArgumentException("second");
            });

            assertThatThrownBy(scope::join)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("2 forked task(s) failed")
                    .satisfies(thrown -> assertThat(thrown.getSuppressed()).hasSize(1));
        }

        @Test
        @Timeout(value = 20, unit = TimeUnit.SECONDS)
        @DisplayName("an Error is rethrown as an Error, not wrapped")
        void errorIsRethrownUnwrapped() {
            TckScope scope = TckScope.openFailFast();
            scope.fork(() -> {
                throw new AssertionError("an assertion, not an exception");
            });

            assertThatThrownBy(scope::join)
                    .as("a fixture's AssertionError must stay an AssertionError, or the failure "
                        + "reads as infrastructure rather than as the contract being violated")
                    .isInstanceOf(AssertionError.class)
                    .hasMessage("an assertion, not an exception");
        }

        @Test
        @Timeout(value = 20, unit = TimeUnit.SECONDS)
        @DisplayName("cancel-on-failure reports nothing — the failure is the stimulus")
        void cancelOnFailureReportsNothing() throws InterruptedException {
            TckScope scope = TckScope.openCancelOnFailure();
            CountDownLatch siblingStarted = new CountDownLatch(1);
            AtomicBoolean siblingFinished = new AtomicBoolean();

            scope.fork(() -> {
                siblingStarted.await();
                // Long enough that a scope which did NOT cancel would still be in here when join()
                // returns, so the assertion below distinguishes "cancelled" from "raced ahead".
                Thread.sleep(Duration.ofSeconds(5));
                siblingFinished.set(true);
                return null;
            });
            scope.fork(() -> {
                siblingStarted.countDown();
                throw new IllegalStateException("deliberate trigger");
            });

            // Two claims, and the test asserted neither. It had no assertion at all: it passed
            // because join() happened not to throw, which is also what it would do if
            // cancel-on-failure had silently stopped cancelling.
            assertThatCode(scope::join)
                    .as("cancel-on-failure swallows the failure — it is the stimulus for cancelling "
                        + "the siblings, not something the caller is asked to handle")
                    .doesNotThrowAnyException();
            assertThat(siblingFinished)
                    .as("and the sibling was actually cancelled, which is the half that makes the "
                        + "silence worth having")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        @Timeout(value = 20, unit = TimeUnit.SECONDS)
        @DisplayName("close() joins when join() was not called")
        void closeJoinsUnjoinedTasks() {
            AtomicInteger completed = new AtomicInteger();

            try (TckScope scope = TckScope.open()) {
                for (int i = 0; i < 4; i++) {
                    scope.fork(() -> {
                        Thread.sleep(20L);
                        completed.incrementAndGet();
                        return null;
                    });
                }
            }

            assertThat(completed.get())
                    .as("try-with-resources must not return over running tasks")
                    .isEqualTo(4);
        }

        @Test
        @Timeout(value = 20, unit = TimeUnit.SECONDS)
        @DisplayName("close() after an explicit join does not join twice")
        void closeAfterJoinIsIdempotent() throws InterruptedException {
            AtomicInteger runs = new AtomicInteger();

            TckScope scope = TckScope.open();
            scope.fork(() -> {
                runs.incrementAndGet();
                return null;
            });
            scope.join();
            scope.close();

            assertThat(runs.get()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Forked result")
    class ForkedResult {

        @Test
        @DisplayName("get() returns what the task returned")
        void getReturnsTheResult() throws InterruptedException {
            TckScope.Forked<String> handle;
            try (TckScope scope = TckScope.openFailFast()) {
                handle = scope.fork(() -> "value");
                scope.join();
            }

            assertThat(handle.get()).isEqualTo("value");
            assertThat(handle.failed()).isFalse();
        }

        @Test
        @DisplayName("get() before the task finishes refuses, and yields the value once it does")
        void getBeforeCompletionRefusesThenYields() throws InterruptedException {
            CountDownLatch release = new CountDownLatch(1);
            try (TckScope scope = TckScope.openFailFast()) {
                // Blocked on a latch the test holds, so "not finished yet" is a fact rather than a
                // race: the assertion below cannot run after the task completed.
                TckScope.Forked<String> handle = scope.fork(() -> {
                    release.await();
                    return "late";
                });

                assertThatThrownBy(handle::get)
                        .as("an unfinished task has no result, and null would look like one")
                        .isInstanceOf(IllegalStateException.class);

                release.countDown();
                scope.join();
                assertThat(handle.get())
                        .as("and the same handle yields the value once the task is done")
                        .isEqualTo("late");
            }
        }

        /**
         * The direction the handle exists for: a fixture reading a result must not receive
         * {@code null} because the task threw. Without it, a failure reaches the assertion as a
         * missing value rather than as the exception that caused it.
         */
        @Test
        @DisplayName("get() on a failed task throws, and carries the failure as the cause")
        void getOnFailureCarriesTheCause() {
            try (TckScope scope = TckScope.open()) {
                TckScope.Forked<String> handle = scope.fork(() -> {
                    throw new IllegalArgumentException("boom");
                });
                try {
                    scope.join();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                } catch (Throwable reported) { //NOPMD AvoidCatchingThrowable — join() reports by throwing
                    // join() aggregates ("1 forked task(s) failed"); what this case is about is the
                    // handle, which must carry the original cause rather than a null result.
                    assertThat(reported).isNotNull();
                }

                assertThat(handle.failed()).isTrue();
                assertThatThrownBy(handle::get)
                        .isInstanceOf(IllegalStateException.class)
                        .hasRootCauseInstanceOf(IllegalArgumentException.class);
            }
        }
    }
}
