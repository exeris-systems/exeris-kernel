/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NativeTcpStreamRegistrationGate: a writer waits for the reactor to arm the key")
class NativeTcpStreamRegistrationGateTest {

    private static final Duration BOUND = Duration.ofSeconds(10);

    @Test
    @DisplayName("a new gate is ready, so neither form of the wait blocks")
    void newGateIsReady() {
        NativeTcpStreamRegistrationGate gate = new NativeTcpStreamRegistrationGate(() -> false);

        assertThat(gate.awaitReady(false)).isTrue();
        assertThat(gate.awaitReady(true)).isTrue();
    }

    @Test
    @DisplayName("a pending gate answers false at once to a non-blocking wait")
    void pendingGateRefusesNonBlockingWait() {
        NativeTcpStreamRegistrationGate gate = new NativeTcpStreamRegistrationGate(() -> false);
        gate.markPending();

        assertThat(gate.awaitReady(false)).isFalse();
        assertThat(gate.waiter()).as("a non-blocking wait registers no waiter").isNull();
    }

    @Test
    @DisplayName("a blocked waiter is released by markReady and deregisters itself")
    void markReadyReleasesTheWaiter() throws InterruptedException {
        NativeTcpStreamRegistrationGate gate = new NativeTcpStreamRegistrationGate(() -> false);
        gate.markPending();
        AtomicReference<Boolean> result = new AtomicReference<>();
        Thread waiter = Thread.ofVirtual().start(() -> result.set(gate.awaitReady(true)));
        awaitRegistered(gate, waiter);

        gate.markReady();

        assertThat(waiter.join(BOUND)).as("the waiter returns within the bound").isTrue();
        assertThat(result.get()).isTrue();
        assertThat(gate.waiter()).isNull();
    }

    @Test
    @DisplayName("a blocking wait on an ending stream returns false without waiting for registration")
    void endingStreamEndsTheWait() {
        NativeTcpStreamRegistrationGate gate = new NativeTcpStreamRegistrationGate(() -> true);
        gate.markPending();

        assertThat(gate.awaitReady(true)).isFalse();
        assertThat(gate.waiter()).isNull();
    }

    @Test
    @DisplayName("a blocked waiter whose stream starts ending returns false")
    void streamEndingWhileWaitingReturnsFalse() throws InterruptedException {
        AtomicBoolean ending = new AtomicBoolean(false);
        NativeTcpStreamRegistrationGate gate = new NativeTcpStreamRegistrationGate(ending::get);
        gate.markPending();
        AtomicReference<Boolean> result = new AtomicReference<>();
        Thread waiter = Thread.ofVirtual().start(() -> result.set(gate.awaitReady(true)));
        awaitRegistered(gate, waiter);

        ending.set(true);

        assertThat(waiter.join(BOUND)).as("the waiter returns within the bound").isTrue();
        assertThat(result.get()).isFalse();
    }

    @Test
    @DisplayName("an interrupted waiter gets IllegalStateException and keeps its interrupt status")
    void interruptedWaitThrowsAndKeepsTheFlag() throws InterruptedException {
        NativeTcpStreamRegistrationGate gate = new NativeTcpStreamRegistrationGate(() -> false);
        gate.markPending();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean flagAfter = new AtomicBoolean();
        Thread waiter = Thread.ofVirtual().start(() -> {
            Thread.currentThread().interrupt();
            try {
                gate.awaitReady(true);
            } catch (IllegalStateException e) {
                failure.set(e);
            }
            flagAfter.set(Thread.currentThread().isInterrupted());
        });

        assertThat(waiter.join(BOUND)).as("the waiter returns within the bound").isTrue();
        assertThat(failure.get()).isInstanceOf(IllegalStateException.class).hasMessageContaining("interrupted");
        assertThat(flagAfter.get()).isTrue();
        assertThat(gate.waiter()).isNull();
    }

    private static void awaitRegistered(NativeTcpStreamRegistrationGate gate, Thread waiter) {
        long deadline = System.nanoTime() + BOUND.toNanos();
        while (!waiter.equals(gate.waiter())) {
            assertThat(System.nanoTime()).as("the waiter registers within the bound").isLessThan(deadline);
            Thread.onSpinWait();
        }
    }
}
