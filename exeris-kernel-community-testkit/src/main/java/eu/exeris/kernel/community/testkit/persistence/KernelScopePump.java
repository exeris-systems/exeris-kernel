/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.testkit.persistence;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Carries work to the thread that holds the kernel scope open.
 *
 * <p>A {@code ScopedValue} binding cannot outlive the frame that opened it, so a fixture cannot hand
 * its scope to the test thread. The inversion is the only option left: one thread stays parked inside
 * the boot running {@link #pumpUntilStopped()}, and callers post work to it.
 *
 * <p>The pump is single-consumer by construction — {@link #pumpUntilStopped()} is called from exactly
 * one thread, the one inside the scope — and any number of producers may submit.
 */
final class KernelScopePump {

    /** Identity-compared sentinel; a plain no-op {@code Runnable} would be indistinguishable. */
    private static final Runnable STOP_SENTINEL = () -> { };

    private final BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();

    /**
     * Runs queued work on the calling thread until {@link #requestStop()} is seen.
     *
     * <p>Called from inside the kernel scope; returns when the fixture closes.
     */
    /* default */ void pumpUntilStopped() {
        while (true) {
            Runnable task;
            try {
                task = tasks.take();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            }
            if (task == STOP_SENTINEL) {
                return;
            }
            task.run();
        }
    }

    /**
     * Posts work to the scope-holding thread and blocks until it has run.
     *
     * <p>Whatever the body threw is re-raised on the calling thread, so an assertion failure inside the
     * scope fails the test that wrote it rather than disappearing onto the fixture thread.
     *
     * @param body           the work to run inside the kernel scope
     * @param timeoutSeconds how long to wait for it to complete
     */
    /* default */ void submitAndWait(Runnable body, long timeoutSeconds) {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        tasks.add(() -> runCapturing(body, failure, done));

        try {
            if (!done.await(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out running work inside the kernel scope");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted running work inside the kernel scope", interrupted);
        }

        rethrow(failure.get());
    }

    /** Signals {@link #pumpUntilStopped()} to return, releasing the thread that holds the scope. */
    /* default */ void requestStop() {
        tasks.add(STOP_SENTINEL);
    }

    private static void runCapturing(Runnable body,
                                     AtomicReference<Throwable> failure,
                                     CountDownLatch done) {
        try {
            body.run();
        } catch (Throwable t) { //NOPMD AvoidCatchingThrowable — assertion errors must reach the caller
            failure.set(t);
        } finally {
            done.countDown();
        }
    }

    private static void rethrow(Throwable thrown) {
        switch (thrown) {
            case null -> { }
            case RuntimeException runtimeException -> throw runtimeException;
            case Error error -> throw error;
            default -> throw new IllegalStateException("Work inside the kernel scope failed", thrown);
        }
    }
}
