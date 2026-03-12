/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.crypto.tls;

import eu.exeris.kernel.spi.crypto.TlsPhase;
import eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 Unit Tests: {@link TlsStateMachine} — state transitions, illegal moves,
 * concurrent CAS safety. Zero OpenSSL calls — pure state machine logic.
 *
 * @since 0.5.0
 */
@DisplayName("L1: TlsStateMachine")
class TlsStateMachineTest {

    // =========================================================================
    // Nested: Happy-Path Transitions
    // =========================================================================

    @Nested
    @DisplayName("Happy-Path Transitions")
    class HappyPath {

        @Test
        @DisplayName("Initial phase is UNINITIALIZED")
        void initialPhaseIsUninitialized() {
            TlsStateMachine sm = new TlsStateMachine();
            assertThat(sm.phase()).isEqualTo(TlsPhase.UNINITIALIZED);
        }

        @Test
        @DisplayName("Full session lifecycle: UNINIT → HANDSHAKE → ACTIVE → SHUTDOWN → CLOSED")
        void fullSessionLifecycle() {
            TlsStateMachine sm = new TlsStateMachine();

            sm.transitionTo(TlsPhase.HANDSHAKE_IN_PROGRESS);
            assertThat(sm.phase()).isEqualTo(TlsPhase.HANDSHAKE_IN_PROGRESS);

            sm.transitionTo(TlsPhase.HANDSHAKE_COMPLETE);
            assertThat(sm.phase()).isEqualTo(TlsPhase.HANDSHAKE_COMPLETE);

            sm.transitionTo(TlsPhase.ACTIVE);
            assertThat(sm.phase()).isEqualTo(TlsPhase.ACTIVE);

            sm.transitionTo(TlsPhase.SHUTDOWN_INITIATED);
            assertThat(sm.phase()).isEqualTo(TlsPhase.SHUTDOWN_INITIATED);

            sm.transitionTo(TlsPhase.SHUTDOWN_COMPLETE);
            assertThat(sm.phase()).isEqualTo(TlsPhase.SHUTDOWN_COMPLETE);

            sm.transitionTo(TlsPhase.CLOSED);
            assertThat(sm.phase()).isEqualTo(TlsPhase.CLOSED);
        }

        @Test
        @DisplayName("forceError() from any active phase transitions to ERROR")
        void forceErrorFromActive() {
            TlsStateMachine sm = new TlsStateMachine();
            sm.transitionTo(TlsPhase.HANDSHAKE_IN_PROGRESS);
            sm.forceError();
            assertThat(sm.phase()).isEqualTo(TlsPhase.ERROR);
        }

        @Test
        @DisplayName("forceError() from UNINITIALIZED transitions to ERROR")
        void forceErrorFromUninitialized() {
            TlsStateMachine sm = new TlsStateMachine();
            sm.forceError();
            assertThat(sm.phase()).isEqualTo(TlsPhase.ERROR);
        }

        @Test
        @DisplayName("forceError() from CLOSED is a no-op (terminal)")
        void forceErrorFromClosedIsNoop() {
            TlsStateMachine sm = new TlsStateMachine();
            // Walk to CLOSED
            sm.transitionTo(TlsPhase.HANDSHAKE_IN_PROGRESS);
            sm.transitionTo(TlsPhase.HANDSHAKE_COMPLETE);
            sm.transitionTo(TlsPhase.ACTIVE);
            sm.transitionTo(TlsPhase.SHUTDOWN_INITIATED);
            sm.transitionTo(TlsPhase.SHUTDOWN_COMPLETE);
            sm.transitionTo(TlsPhase.CLOSED);

            sm.forceError(); // must be a no-op
            assertThat(sm.phase()).isEqualTo(TlsPhase.CLOSED);
        }

        @Test
        @DisplayName("forceError() from ERROR is a no-op (idempotent)")
        void forceErrorFromErrorIsNoop() {
            TlsStateMachine sm = new TlsStateMachine();
            sm.forceError();
            assertThat(sm.phase()).isEqualTo(TlsPhase.ERROR);
            sm.forceError(); // must not throw
            assertThat(sm.phase()).isEqualTo(TlsPhase.ERROR);
        }
    }

    // =========================================================================
    // Nested: Illegal Transitions
    // =========================================================================

    @Nested
    @DisplayName("Illegal Transitions")
    class IllegalTransitions {

        @Test
        @DisplayName("UNINITIALIZED → ACTIVE is illegal")
        void uninitializedToActiveIsIllegal() {
            TlsStateMachine sm = new TlsStateMachine();
            assertThatThrownBy(() -> sm.transitionTo(TlsPhase.ACTIVE))
                    .isInstanceOf(TlsHandshakeException.class);
        }

        @Test
        @DisplayName("ACTIVE → HANDSHAKE_IN_PROGRESS (backward) is illegal")
        void backwardTransitionIsIllegal() {
            TlsStateMachine sm = new TlsStateMachine();
            sm.transitionTo(TlsPhase.HANDSHAKE_IN_PROGRESS);
            sm.transitionTo(TlsPhase.HANDSHAKE_COMPLETE);
            sm.transitionTo(TlsPhase.ACTIVE);
            assertThatThrownBy(() -> sm.transitionTo(TlsPhase.HANDSHAKE_IN_PROGRESS))
                    .isInstanceOf(TlsHandshakeException.class);
        }

        @Test
        @DisplayName("CLOSED → HANDSHAKE_IN_PROGRESS (from terminal) is illegal")
        void transitionFromClosedIsIllegal() {
            TlsStateMachine sm = new TlsStateMachine();
            sm.transitionTo(TlsPhase.HANDSHAKE_IN_PROGRESS);
            sm.transitionTo(TlsPhase.HANDSHAKE_COMPLETE);
            sm.transitionTo(TlsPhase.ACTIVE);
            sm.transitionTo(TlsPhase.SHUTDOWN_INITIATED);
            sm.transitionTo(TlsPhase.SHUTDOWN_COMPLETE);
            sm.transitionTo(TlsPhase.CLOSED);
            assertThatThrownBy(() -> sm.transitionTo(TlsPhase.HANDSHAKE_IN_PROGRESS))
                    .isInstanceOf(TlsHandshakeException.class);
        }

        @Test
        @DisplayName("canTransitionTo() returns false for illegal targets")
        void canTransitionToReturnsFalseForIllegal() {
            TlsStateMachine sm = new TlsStateMachine();
            assertThat(sm.canTransitionTo(TlsPhase.ACTIVE)).isFalse();
            assertThat(sm.canTransitionTo(TlsPhase.CLOSED)).isFalse();
            assertThat(sm.canTransitionTo(TlsPhase.HANDSHAKE_IN_PROGRESS)).isTrue();
        }

        @Test
        @DisplayName("Two-arg transitionTo with wrong expected phase throws CAS failure")
        void twoArgTransitionWithWrongExpectedThrows() {
            TlsStateMachine sm = new TlsStateMachine();
            // Machine is UNINITIALIZED but we lie about expected
            assertThatThrownBy(() ->
                    sm.transitionTo(TlsPhase.HANDSHAKE_IN_PROGRESS, TlsPhase.HANDSHAKE_COMPLETE))
                    .isInstanceOf(TlsHandshakeException.class);
        }
    }

    // =========================================================================
    // Nested: Concurrent Safety (Virtual Threads)
    // =========================================================================

    @Nested
    @DisplayName("Concurrent CAS Safety")
    class ConcurrentCasSafety {

        @Test
        @DisplayName("Only one of N concurrent transitionTo() calls succeeds — rest throw")
        void onlyOneTransitionSucceedsUnderContention() throws InterruptedException {
            TlsStateMachine sm = new TlsStateMachine();
            int threads = 100;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done  = new CountDownLatch(threads);
            AtomicInteger successes = new AtomicInteger(0);
            AtomicInteger failures  = new AtomicInteger(0);

            for (int i = 0; i < threads; i++) {
                Thread.ofVirtual().start(() -> {
                    try {
                        start.await();
                        sm.transitionTo(TlsPhase.HANDSHAKE_IN_PROGRESS);
                        successes.incrementAndGet();
                    } catch (TlsHandshakeException | InterruptedException _) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown(); // release all
            done.await();

            assertThat(successes.get()).isEqualTo(1);
            assertThat(failures.get()).isEqualTo(threads - 1);
            assertThat(sm.phase()).isEqualTo(TlsPhase.HANDSHAKE_IN_PROGRESS);
        }

        @Test
        @DisplayName("forceError() is safe under concurrent calls — exactly ERROR state reached")
        void forceErrorIsSafeUnderConcurrency() throws InterruptedException {
            TlsStateMachine sm = new TlsStateMachine();
            sm.transitionTo(TlsPhase.HANDSHAKE_IN_PROGRESS);

            int threads = 50;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done  = new CountDownLatch(threads);

            for (int i = 0; i < threads; i++) {
                Thread.ofVirtual().start(() -> {
                    try {
                        start.await();
                        sm.forceError();
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            done.await();

            assertThat(sm.phase()).isEqualTo(TlsPhase.ERROR);
        }
    }
}
