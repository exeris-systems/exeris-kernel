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
import java.util.concurrent.locks.ReentrantLock;

/**
 * Time and waiting, both injected (ADR-057 §4).
 *
 * <p>A time source alone would not make the trigger TCK deterministic: a test that advances a clock
 * while the dispatcher sleeps on a real monitor still waits in wall-clock time. So this seam owns the
 * lock the dispatcher parks on as well as the two clocks it reads, and a test binding can advance
 * virtual time and release the dispatcher in the same step.
 *
 * <p>Two clocks, because the subsystem needs both kinds of time and conflating them is a defect
 * source: deadlines are monotonic ({@link #nanoTime()}), while cron fields name calendar instants
 * ({@link #wallTime()}). A test binding advances them together.
 *
 * <p><strong>Locking protocol.</strong> {@link #awaitUntil}, {@link #awaitSignal} and {@link #signal}
 * are called holding {@link #lock()}. Everything the scheduler mutates is guarded by that same lock,
 * so a submission that moves the earliest deadline cannot slip between the dispatcher's check and its
 * park — the lost-wakeup this design is most exposed to.
 *
 * <p>Deliberately subsystem-local. The kernel has no unified clock abstraction yet; this SPI is
 * shaped so that migrating onto one is a substitution rather than a redesign.
 *
 * @since 0.11.0
 */
interface CommunitySchedulerClock {

    /** @return monotonic nanoseconds, for deadlines; comparable only against itself */
    long nanoTime();

    /** @return calendar time, for cron fields */
    Instant wallTime();

    /** @return the lock guarding scheduler state and the wait condition */
    ReentrantLock lock();

    /**
     * Waits until the deadline passes or a {@link #signal()} arrives, whichever is first.
     *
     * @param deadlineNanos deadline on the {@link #nanoTime()} scale
     * @throws InterruptedException if the waiting thread is interrupted
     */
    void awaitUntil(long deadlineNanos) throws InterruptedException;

    /**
     * Waits until a {@link #signal()} arrives. Used when nothing is scheduled.
     *
     * @throws InterruptedException if the waiting thread is interrupted
     */
    void awaitSignal() throws InterruptedException;

    /** Releases anything waiting. */
    void signal();
}
