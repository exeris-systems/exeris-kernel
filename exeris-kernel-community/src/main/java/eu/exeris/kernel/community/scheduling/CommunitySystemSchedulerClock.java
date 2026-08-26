/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.scheduling;

import java.time.Instant;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The production {@link CommunitySchedulerClock}: real time, real waiting.
 *
 * @since 0.11.0
 */
final class CommunitySystemSchedulerClock implements CommunitySchedulerClock {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition wakeup = lock.newCondition();

    /**
     * Counts deliveries so a wait can tell a real signal from a spurious wake-up. Guarded by
     * {@link #lock}, like everything else here.
     */
    private long signals;

    @Override
    public long nanoTime() {
        return System.nanoTime();
    }

    @Override
    public Instant wallTime() {
        return Instant.now();
    }

    @Override
    public ReentrantLock lock() {
        return lock;
    }

    /**
     * Waits until the deadline or a signal, looping on the residual timeout {@code awaitNanos}
     * returns so a spurious wake-up neither ends the wait early nor restarts the full interval.
     */
    @Override
    public void awaitUntil(long deadlineNanos) throws InterruptedException {
        long seen = signals;
        long remaining = deadlineNanos - System.nanoTime();
        while (remaining > 0 && signals == seen) {
            remaining = wakeup.awaitNanos(remaining);
        }
    }

    @Override
    public void awaitSignal() throws InterruptedException {
        long seen = signals;
        while (signals == seen) {
            wakeup.await();
        }
    }

    @Override
    public void signal() {
        signals++;
        wakeup.signalAll();
    }
}
