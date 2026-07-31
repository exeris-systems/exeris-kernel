/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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

    @Override
    public void awaitUntil(long deadlineNanos) throws InterruptedException {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining > 0) {
            wakeup.awaitNanos(remaining);
        }
    }

    @Override
    public void awaitSignal() throws InterruptedException {
        wakeup.await();
    }

    @Override
    public void signal() {
        wakeup.signalAll();
    }
}
