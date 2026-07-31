/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.scheduling;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A {@link CommunitySchedulerClock} where time only moves when a test says so.
 *
 * <p>{@link #awaitUntil} ignores its deadline and waits for a signal instead: with virtual time,
 * a deadline can only arrive through {@link #advance(Duration)}, which signals. Honouring the
 * deadline against wall time would reintroduce exactly the real-time waiting ADR-057 §4 removes.
 */
final class VirtualSchedulerClock implements CommunitySchedulerClock {

    /** A fixed, unremarkable instant: no DST edge, no month boundary, no leap day. */
    private static final Instant EPOCH = Instant.parse("2026-03-10T12:00:00Z");

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition wakeup = lock.newCondition();

    // Volatile: the dispatcher reads these under the lock, but a running job body reads nanoTime()
    // outside it when the scheduler measures execution duration.
    private volatile long nanos;
    private volatile Instant wall = EPOCH;

    @Override
    public long nanoTime() {
        return nanos;
    }

    @Override
    public Instant wallTime() {
        return wall;
    }

    @Override
    public ReentrantLock lock() {
        return lock;
    }

    @Override
    public void awaitUntil(long deadlineNanos) throws InterruptedException {
        wakeup.await();
    }

    @Override
    public void awaitSignal() throws InterruptedException {
        wakeup.await();
    }

    @Override
    public void signal() {
        wakeup.signalAll();
    }

    /** Moves both clocks forward and releases the dispatcher, in one atomic step. */
    /* default */ void advance(Duration amount) {
        lock.lock();
        try {
            nanos += amount.toNanos();
            wall = wall.plus(amount);
            wakeup.signalAll();
        } finally {
            lock.unlock();
        }
    }
}
