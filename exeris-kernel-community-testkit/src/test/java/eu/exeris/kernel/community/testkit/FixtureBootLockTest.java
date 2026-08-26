/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.testkit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What is testable here is <em>mutual exclusion</em>, not the race it prevents.
 *
 * <p>A lost race inside the kernel is not deterministically reproducible, so no test asserts that two
 * fixtures would have swapped configuration. What can be pinned is the property the fix rests on: a
 * second caller does not enter while the first is inside. The blocked half of that is necessarily a
 * bounded wait; the released half is deterministic, and a broken lock fails the released half's
 * premise as surely as the blocked one.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
@DisplayName("FixtureBootLock")
class FixtureBootLockTest {

    private static final long BLOCKED_PROBE_MILLIS = 300L;

    @Test
    @DisplayName("runs the supplied body")
    void runsBody() {
        AtomicBoolean ran = new AtomicBoolean(false);

        FixtureBootLock.bootExclusively(() -> ran.set(true));

        assertThat(ran).isTrue();
    }

    @Test
    @DisplayName("a second caller waits until the first has left the boot window")
    void secondCallerWaitsForTheFirst() throws InterruptedException {
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondInside = new CountDownLatch(1);

        Thread first = Thread.ofPlatform().start(() -> FixtureBootLock.bootExclusively(() -> {
            firstInside.countDown();
            awaitQuietly(releaseFirst);
        }));
        assertThat(firstInside.await(5L, TimeUnit.SECONDS)).isTrue();

        Thread second = Thread.ofPlatform()
                .start(() -> FixtureBootLock.bootExclusively(secondInside::countDown));

        assertThat(secondInside.await(BLOCKED_PROBE_MILLIS, TimeUnit.MILLISECONDS))
                .as("entering while the first holds the window is exactly the configuration swap "
                        + "this lock exists to prevent")
                .isFalse();

        releaseFirst.countDown();

        assertThat(secondInside.await(5L, TimeUnit.SECONDS))
                .as("and it must then proceed — a lock that never releases would deadlock every suite")
                .isTrue();
        first.join(TimeUnit.SECONDS.toMillis(5L));
        second.join(TimeUnit.SECONDS.toMillis(5L));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }
}
