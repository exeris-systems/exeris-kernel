/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.bootstrap;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The state of one full {@link KernelBootstrap#boot(Runnable)}. The boot thread's "may I run kernelMain?"
 * and an external caller's "is kernelMain running, or must I wait for boot to unwind?" are decided by one
 * atomic transition of the stage; with two separate flags a caller could wait for a {@code boot()} that
 * never unwinds because the boot thread entered a {@code kernelMain} that does not return.
 */
final class BootSession {

    /** Where the boot is, as far as a stop request is concerned. */
    private enum Stage {
        /** Initializing or starting subsystems; {@code kernelMain} has not been entered. */
        BOOTING,
        /** A stop was requested while booting; the boot thread stops the kernel when it unwinds. */
        STOP_PENDING,
        /** {@code kernelMain} runs (or has run); initialization and start are over. */
        RUNNING
    }

    /**
     * Bound to the session for the whole boot. A subsystem's {@code start()} forked into a
     * {@link java.util.concurrent.StructuredTaskScope} inherits it, so a stop it requests is recognised
     * as coming from the boot itself even though it runs on another thread.
     */
    /* default */ static final ScopedValue<BootSession> CURRENT = ScopedValue.newInstance();

    private final AtomicReference<SubsystemOrchestrator> orchestrator = new AtomicReference<>();
    private final Thread bootThread;
    private final AtomicReference<Stage> stage = new AtomicReference<>(Stage.BOOTING);
    private final CountDownLatch unwound = new CountDownLatch(1);
    private final AtomicReference<Thread> hook = new AtomicReference<>();

    /**
     * Creates the session of the boot running on {@code bootThread}.
     *
     * @param bootThread   the thread that runs {@code initialize()}, {@code start()} and {@code kernelMain}
     */
    /* default */ BootSession(Thread bootThread) {
        this.bootThread = bootThread;
    }

    /** Attaches the orchestrator once it is built; a stop requested earlier is served by the boot thread. */
    /* default */ void attach(SubsystemOrchestrator built) {
        orchestrator.set(built);
    }

    /* default */ SubsystemOrchestrator orchestrator() {
        return orchestrator.get();
    }

    /* default */ Thread hook() {
        return hook.get();
    }

    /** Records the shutdown hook; written by the boot thread before any subsystem is initialized. */
    /* default */ void hook(Thread registered) {
        hook.set(registered);
    }

    /** Counted down once the stop has completed and {@code boot()} is about to return. */
    /* default */ void markUnwound() {
        unwound.countDown();
    }

    /** Boot thread: {@code true} when kernelMain may run; {@code false} when a stop was requested first. */
    /* default */ boolean enterRunning() {
        return stage.compareAndSet(Stage.BOOTING, Stage.RUNNING);
    }

    /* default */ boolean stopPending() {
        return stage.get() == Stage.STOP_PENDING;
    }

    /** Requests the stop; see {@link KernelBootstrap#shutdown()} for what each caller observes. */
    /* default */ void requestStop() {
        while (true) {
            Stage current = stage.get();
            if (current == Stage.RUNNING) { // init and start are over: the orchestrator is quiescent
                orchestrator.get().shutdown(); // winner runs it; losers wait; re-entry returns
                return;
            }
            if (current == Stage.STOP_PENDING || stage.compareAndSet(Stage.BOOTING, Stage.STOP_PENDING)) {
                if (!actsForBoot()) {
                    awaitUnwound(); // boot() runs the stop in its finally
                }
                return; // a call from initialize()/start() made for the boot returns at once
            }
            // lost the BOOTING -> STOP_PENDING race to enterRunning(); re-read
        }
    }

    /**
     * Whether the caller runs for this boot: on the boot thread, or in a task forked from its scope,
     * which the boot waits for and so must not wait for the boot in turn.
     */
    private boolean actsForBoot() {
        // Identity, not equals(): the question is whether this is the boot thread, or this session.
        return Thread.currentThread() == bootThread // NOPMD CompareObjectsWithEquals
                || CURRENT.isBound() && CURRENT.get() == this; // NOPMD CompareObjectsWithEquals
    }

    private void awaitUnwound() {
        boolean interrupted = false;
        while (true) {
            try {
                unwound.await();
                break;
            } catch (InterruptedException _) { // NOSONAR — an uninterruptible wait restores the flag once it ends
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
