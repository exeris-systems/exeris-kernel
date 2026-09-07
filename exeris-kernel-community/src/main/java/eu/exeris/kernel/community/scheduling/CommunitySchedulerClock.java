/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.scheduling;

import eu.exeris.kernel.spi.time.TimeSource;

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
 * <p><strong>This is {@link TimeSource} plus waiting.</strong> The two reads below are inherited,
 * not redeclared: an earlier version of this file said the kernel had no unified clock abstraction
 * and that this interface was shaped so migrating onto one would be a substitution rather than a
 * redesign. ADR-082 added the abstraction and this is that substitution — the wait primitives stay
 * here, because a {@code ReentrantLock} has no business in the SPI.
 *
 * @since 0.11
 */
interface CommunitySchedulerClock extends TimeSource {

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
