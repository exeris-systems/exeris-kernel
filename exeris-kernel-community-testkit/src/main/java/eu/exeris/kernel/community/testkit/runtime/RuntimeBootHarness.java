/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.testkit.runtime;

import eu.exeris.kernel.community.testkit.FixtureThreads;
import eu.exeris.kernel.community.testkit.KernelScopePump;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds a kernel boot open on a dedicated thread, and reports whether it got there.
 *
 * <p>A {@code ScopedValue} binding cannot outlive the frame that opened it, so a fixture cannot boot on
 * the test thread and hand the scope out: one thread has to stay parked inside the boot. That thread,
 * the latch that says it arrived, the failure it may have arrived with instead, and the join deadline
 * on the way out are one concept, and this is it. The fixture keeps what is specific to it — which
 * properties to publish and which engines to read.
 *
 * <p>Extracted rather than left inline because it is also the seam where this and
 * {@code KernelBootstrapPersistenceEngineFixture} converge: they carry the same 60 lines today. Merging
 * them is a change to a delivered fixture and belongs in its own cycle, but the shape is now named.
 */
final class RuntimeBootHarness {

    private final String threadName;
    private final long startTimeoutSeconds;
    private final long stopTimeoutSeconds;

    private final CountDownLatch startedSignal = new CountDownLatch(1);
    private final AtomicReference<Throwable> startupFailure = new AtomicReference<>();
    private final KernelScopePump pump = new KernelScopePump();

    private Thread thread;

    /* default */ RuntimeBootHarness(String threadName,
                                     long startTimeoutSeconds,
                                     long stopTimeoutSeconds) {
        this.threadName = threadName;
        this.startTimeoutSeconds = startTimeoutSeconds;
        this.stopTimeoutSeconds = stopTimeoutSeconds;
    }

    /**
     * Runs {@code runtimeBody} on a dedicated platform thread and blocks until it signals readiness or
     * fails.
     *
     * @param runtimeBody     the boot, which must call {@link #signalStarted()} once engines are bound
     *                        and then {@link #park()} to hold the scope open
     * @param failureAdvice   appended to the message when the boot did not arrive, to say what a caller
     *                        is likely missing
     * @throws IllegalStateException if the boot times out, is interrupted, or fails
     */
    /* default */ void bootAndAwait(Runnable runtimeBody, String failureAdvice) {
        Thread booting = Thread.ofPlatform()
                .name(threadName)
                .uncaughtExceptionHandler((_, throwable) -> {
                    startupFailure.compareAndSet(null, throwable);
                    startedSignal.countDown();
                })
                .start(runtimeBody);

        try {
            if (!startedSignal.await(startTimeoutSeconds, TimeUnit.SECONDS)) {
                throw abandon(booting, "Timed out while starting " + threadName, null);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw abandon(booting, "Interrupted while starting " + threadName, interrupted);
        }

        Throwable failure = startupFailure.get();
        if (failure != null) {
            throw abandon(booting, failureAdvice, failure);
        }
        thread = booting;
    }

    /** Records a boot failure the caller caught itself, and releases the waiting starter. */
    /* default */ void signalFailed(Throwable failure) {
        startupFailure.compareAndSet(null, failure);
        startedSignal.countDown();
    }

    /** Announces that the engines are bound and the fixture may be used. */
    /* default */ void signalStarted() {
        startedSignal.countDown();
    }

    /** Parks the boot thread, serving work submitted by {@link #submit(Runnable)}. */
    /* default */ void park() {
        pump.pumpUntilStopped();
    }

    /* default */ void submit(Runnable body) {
        pump.submitAndWait(body, startTimeoutSeconds);
    }

    /** Stops the pump and joins the boot thread on a deadline. Safe before {@link #bootAndAwait}. */
    /* default */ void shutdown() {
        pump.requestStop();
        FixtureThreads.joinQuietly(thread, stopTimeoutSeconds);
    }

    /** Releases a boot thread that will never be adopted, and describes why. */
    private IllegalStateException abandon(Thread booting, String message, Throwable cause) {
        pump.requestStop();
        FixtureThreads.joinQuietly(booting, stopTimeoutSeconds);
        return new IllegalStateException(message, cause);
    }
}
