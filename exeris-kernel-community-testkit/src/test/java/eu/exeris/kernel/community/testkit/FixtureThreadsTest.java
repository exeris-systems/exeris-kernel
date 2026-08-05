/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.testkit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The property worth pinning is the deadline: a fixture that hangs on close must not hang the suite.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
@DisplayName("FixtureThreads.joinQuietly")
class FixtureThreadsTest {

    @Test
    @DisplayName("a null thread is a no-op")
    void nullThreadIsNoOp() {
        assertThatCode(() -> FixtureThreads.joinQuietly(null, 1L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("joins a thread that finishes on its own")
    void joinsFinishedThread() {
        Thread thread = Thread.ofPlatform().start(() -> { });

        FixtureThreads.joinQuietly(thread, 5L);

        assertThat(thread.isAlive()).isFalse();
    }

    @Test
    @DisplayName("interrupts a thread that outlives the deadline instead of waiting forever")
    void interruptsThreadThatOutlivesDeadline() throws InterruptedException {
        CountDownLatch neverReleased = new CountDownLatch(1);
        AtomicBoolean sawInterrupt = new AtomicBoolean(false);
        CountDownLatch finished = new CountDownLatch(1);

        Thread thread = Thread.ofPlatform().start(() -> {
            try {
                neverReleased.await();
            } catch (InterruptedException _) {
                sawInterrupt.set(true);
                Thread.currentThread().interrupt();
            }
            finished.countDown();
        });

        long startNanos = System.nanoTime();
        FixtureThreads.joinQuietly(thread, 1L);
        long elapsedNanos = System.nanoTime() - startNanos;

        assertThat(finished.await(5L, TimeUnit.SECONDS))
                .as("without the interrupt the thread would park forever and the suite with it")
                .isTrue();
        assertThat(sawInterrupt).isTrue();
        assertThat(elapsedNanos)
                .as("it must actually wait out the deadline rather than interrupting immediately — "
                        + "a fixture shutting down cleanly deserves the grace period")
                .isGreaterThanOrEqualTo(TimeUnit.SECONDS.toNanos(1L));
    }
}
