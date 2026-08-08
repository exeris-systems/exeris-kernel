/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract for the GA structured-concurrency layer that replaces {@code StructuredTaskScope} on the
 * default distribution line (ADR-066).
 *
 * <p>The binding cases are the reason this class exists. {@code StructuredTaskScope} forks inherit
 * {@link ScopedValue} bindings and plain virtual threads do not, so a substitution that did not
 * re-establish them would compile, pass a naive smoke test, and fail later at an unbound read far
 * from the fork.
 */
@DisplayName("StructuredScope — GA structured concurrency (ADR-066)")
class StructuredScopeTest {

    private static final ScopedValue<String> TENANT = ScopedValue.newInstance();
    private static final ScopedValue<Integer> DEPTH = ScopedValue.newInstance();

    @Nested
    @DisplayName("ScopedValue propagation")
    class BindingPropagation {

        @Test
        @DisplayName("open(carrier) re-establishes every binding inside each forked task")
        void carrierBindingsReachForkedTasks() throws Exception {
            ScopedValue.Carrier carrier = ScopedValue.where(TENANT, "acme").where(DEPTH, 7);

            List<StructuredScope.ForkedTask<String>> tasks = new ArrayList<>();
            try (StructuredScope scope = StructuredScope.open(carrier)) {
                for (int i = 0; i < 4; i++) {
                    tasks.add(scope.fork(() -> TENANT.get() + ":" + DEPTH.get()));
                }
                scope.join();
            }

            assertThat(tasks)
                    .as("every forked task observes the carrier's bindings")
                    .allSatisfy(task -> {
                        assertThat(task.state()).isEqualTo(StructuredScope.State.SUCCESS);
                        assertThat(task.result()).isEqualTo("acme:7");
                    });
        }

        @Test
        @DisplayName("openWithoutBindings() leaves forked tasks unbound — the API does not pretend")
        void withoutBindingsLeavesTasksUnbound() throws Exception {
            AtomicBoolean boundInChild = new AtomicBoolean(true);

            ScopedValue.where(TENANT, "acme").run(() -> {
                assertThat(TENANT.isBound())
                        .as("precondition: the owner thread IS bound")
                        .isTrue();
                try (StructuredScope scope = StructuredScope.openWithoutBindings()) {
                    scope.fork(() -> {
                        boundInChild.set(TENANT.isBound());
                        return null;
                    });
                    scope.join();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
            });

            assertThat(boundInChild)
                    .as("a plain virtual thread does not inherit bindings — this is the documented gap")
                    .isFalse();
        }

        @Test
        @DisplayName("the carrier wins over whatever the owner thread has bound at fork time")
        void carrierOverridesOwnerThreadBindings() throws Exception {
            ScopedValue.Carrier carrier = ScopedValue.where(TENANT, "from-carrier");
            AtomicReference<String> seen = new AtomicReference<>();

            ScopedValue.where(TENANT, "from-owner").run(() -> {
                try (StructuredScope scope = StructuredScope.open(carrier)) {
                    scope.fork(() -> {
                        seen.set(TENANT.get());
                        return null;
                    });
                    scope.join();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
            });

            assertThat(seen).hasValue("from-carrier");
        }
    }

    @Nested
    @DisplayName("Await-all semantics")
    class AwaitAll {

        @Test
        @DisplayName("join() returns normally when a task fails, and the failure is data")
        void failureIsDataNotControlFlow() throws Exception {
            StructuredScope.ForkedTask<String> good;
            StructuredScope.ForkedTask<String> bad;

            try (StructuredScope scope = StructuredScope.openWithoutBindings()) {
                good = scope.fork(() -> "ok");
                bad = scope.fork(() -> {
                    throw new IllegalStateException("subsystem boom");
                });
                scope.join();
            }

            assertThat(good.state()).isEqualTo(StructuredScope.State.SUCCESS);
            assertThat(good.result()).isEqualTo("ok");
            assertThat(bad.state()).isEqualTo(StructuredScope.State.FAILED);
            assertThat(bad.exception())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("subsystem boom");
        }

        @Test
        @DisplayName("every task runs even when an earlier one has already failed")
        void oneFailureDoesNotCancelSiblings() throws Exception {
            CountDownLatch ran = new CountDownLatch(3);

            try (StructuredScope scope = StructuredScope.openWithoutBindings()) {
                scope.fork(() -> {
                    ran.countDown();
                    throw new IllegalStateException("first");
                });
                scope.fork(() -> {
                    ran.countDown();
                    return null;
                });
                scope.fork(() -> {
                    ran.countDown();
                    return null;
                });
                scope.join();
            }

            assertThat(ran.getCount())
                    .as("await-all, not fail-fast: siblings are not cancelled by a failure")
                    .isZero();
        }

        @Test
        @DisplayName("a failing task reports FAILED, not a thrown preview exception type")
        void joinDoesNotThrowOnTaskFailure() {
            assertThatCode(() -> {
                try (StructuredScope scope = StructuredScope.openWithoutBindings()) {
                    scope.fork(() -> {
                        throw new IllegalStateException("boom");
                    });
                    scope.join();
                }
            }).as("join() must not propagate the task's failure").doesNotThrowAnyException();
        }

        @Test
        @DisplayName("result() and exception() reject the state they do not describe")
        void accessorsRejectWrongState() throws Exception {
            StructuredScope.ForkedTask<String> good;
            StructuredScope.ForkedTask<String> bad;

            try (StructuredScope scope = StructuredScope.openWithoutBindings()) {
                good = scope.fork(() -> "ok");
                bad = scope.fork(() -> {
                    throw new IllegalStateException("boom");
                });
                scope.join();
            }

            assertThatThrownBy(good::exception).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(bad::result).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("Structured guarantee")
    class StructuredGuarantee {

        /**
         * The post-interrupt delay is what makes this test non-vacuous. Asserting only that the
         * task was interrupted, or that its state is no longer {@code RUNNING}, passes just as
         * happily against a {@code close()} that interrupts and returns without waiting — the task
         * usually wins the race to terminate before the assertion reads it. Holding the task
         * busy after the interrupt means a non-joining {@code close()} returns while the thread is
         * demonstrably still running, and {@code completed} is still false.
         */
        @Test
        @DisplayName("close() cancels a straggler and waits for it — no task outlives the scope")
        void closeCancelsAndJoinsStragglers() {
            AtomicBoolean observedInterrupt = new AtomicBoolean();
            AtomicBoolean completed = new AtomicBoolean();
            CountDownLatch started = new CountDownLatch(1);
            StructuredScope.ForkedTask<Void> straggler;

            CountDownLatch neverReleased = new CountDownLatch(1);

            try (StructuredScope scope = StructuredScope.openWithoutBindings()) {
                straggler = scope.fork(() -> {
                    started.countDown();
                    try {
                        // Blocks until interrupted. An unbounded await says exactly that, where a
                        // Thread.sleep(5 min) would only have said it by picking a duration no test
                        // run reaches.
                        neverReleased.await();
                    } catch (InterruptedException _) {
                        observedInterrupt.set(true);
                        spinFor(java.time.Duration.ofMillis(300));
                        completed.set(true);
                        Thread.currentThread().interrupt();
                    }
                    return null;
                });
                started.await();
                // leaving the block without join() must still not leak the task
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }

            assertThat(observedInterrupt)
                    .as("close() interrupts what is still running")
                    .isTrue();
            assertThat(completed)
                    .as("close() returned before the task finished — the task outlived its scope")
                    .isTrue();
            assertThat(straggler.state())
                    .as("close() returns only after the task has terminated")
                    .isNotEqualTo(StructuredScope.State.RUNNING);
        }

        /** Busy-waits without consuming the interrupt status the caller is about to re-assert. */
        private void spinFor(java.time.Duration duration) {
            long deadline = System.nanoTime() + duration.toNanos();
            while (System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
        }

        @Test
        @DisplayName("close() is idempotent")
        void closeIsIdempotent() throws Exception {
            StructuredScope scope = StructuredScope.openWithoutBindings();
            scope.fork(() -> "ok");
            scope.join();
            scope.close();

            assertThatCode(scope::close).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Owner confinement")
    class OwnerConfinement {

        @Test
        @DisplayName("fork() and join() reject a thread that did not open the scope")
        void foreignThreadIsRejected() throws Exception {
            try (StructuredScope scope = StructuredScope.openWithoutBindings()) {
                AtomicReference<Throwable> forkFailure = new AtomicReference<>();
                AtomicReference<Throwable> joinFailure = new AtomicReference<>();

                Thread foreign = Thread.ofVirtual().start(() -> {
                    forkFailure.set(catchThrowableOf(() -> scope.fork(() -> "nope")));
                    joinFailure.set(catchThrowableOf(scope::join));
                });
                foreign.join();

                assertThat(forkFailure.get())
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("confined");
                assertThat(joinFailure.get())
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("confined");
            }
        }

        @Test
        @DisplayName("fork() after join() is rejected")
        void forkAfterJoinIsRejected() throws Exception {
            try (StructuredScope scope = StructuredScope.openWithoutBindings()) {
                scope.fork(() -> "ok");
                scope.join();

                assertThatThrownBy(() -> scope.fork(() -> "late"))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("after join()");
            }
        }
    }

    @Nested
    @DisplayName("Memory model")
    class MemoryModel {

        /**
         * Reads the result through the {@code volatile state} edge alone, never through
         * {@code join()}. If the terminal state were published without the result that precedes it,
         * this is where it would show. A passing run is a regression guard, not a proof — ruling a
         * data race out rather than failing to observe one needs jcstress, which this repository
         * does not yet run.
         */
        @Test
        @DisplayName("observing a terminal state() implies the result is visible, without joining")
        void terminalStateImpliesVisibleResult() throws Exception {
            for (int round = 0; round < 500; round++) {
                String payload = "payload-" + round;
                try (StructuredScope scope = StructuredScope.openWithoutBindings()) {
                    StructuredScope.ForkedTask<String> task = scope.fork(() -> payload);

                    while (task.state() == StructuredScope.State.RUNNING) {
                        Thread.onSpinWait();
                    }

                    assertThat(task.result())
                            .as("round %d: SUCCESS was visible but the result was not", round)
                            .isEqualTo(payload);
                    scope.join();
                }
            }
        }

        @Test
        @DisplayName("close() rejects a foreign thread even once the scope is already closed")
        void foreignCloseIsRejectedEvenWhenAlreadyClosed() throws Exception {
            StructuredScope scope = StructuredScope.openWithoutBindings();
            scope.fork(() -> "ok");
            scope.join();
            scope.close();

            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread foreign = Thread.ofVirtual().start(() -> failure.set(catchThrowableOf(scope::close)));
            foreign.join();

            assertThat(failure.get())
                    .as("an early return on the plain `closed` field would have let a foreign "
                            + "thread read it unsynchronized")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("confined");
        }
    }

    private interface ThrowingBlock {
        void run() throws Exception;
    }

    private static Throwable catchThrowableOf(ThrowingBlock block) {
        try {
            block.run();
            return null;
        } catch (Throwable throwable) { //NOPMD AvoidCatchingThrowable — the assertion subject
            return throwable;
        }
    }
}
