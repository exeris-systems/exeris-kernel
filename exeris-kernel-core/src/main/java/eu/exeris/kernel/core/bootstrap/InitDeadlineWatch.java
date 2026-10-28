/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.bootstrap;

import eu.exeris.kernel.core.bootstrap.health.KernelHealthMonitor;
import eu.exeris.kernel.core.bootstrap.jfr.BootstrapJfrEvents;
import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.spi.exceptions.bootstrap.SubsystemInitDeadlineException;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.concurrent.locks.LockSupport;

/**
 * The initialization deadline of one subsystem: a platform daemon thread that, when that subsystem's
 * {@code initialize()} runs past its budget, emits the deadline event, marks the subsystem FAILED and
 * interrupts the booting thread. {@link SubsystemOrchestrator} owns the call sequence; this class owns
 * the watchdog.
 *
 * <h2>Why the booting thread is interrupted, not the call moved</h2>
 * <p>{@code initialize()} stays on the booting thread, as every lifecycle call does. A subsystem run on
 * another thread would lose the {@link ScopedValue} bindings the booting thread carries, and a call
 * abandoned on that thread could still be running when {@code shutdown()} stops the subsystem.
 *
 * <h2>Why a platform thread</h2>
 * <p>The watchdog must run while every carrier is held by a hung native call, and a virtual thread
 * could not. It reads no {@link ScopedValue}, and nothing interrupts it.
 *
 * <h2>Protocol</h2>
 * <p>The state moves once, from {@code ARMED} to {@code FIRED} (the watchdog) or to {@code DISARMED}
 * (the booting thread, when {@code initialize()} ends first). Whichever side wins owns the outcome.
 * A {@code FIRED} outcome is decided only after the watchdog has finished, so the interrupt it delivers
 * is in place before the booting thread clears it.
 */
final class InitDeadlineWatch implements Runnable {

    /**
     * Test seam: how the watchdog delivers its interrupt to the booting thread, called on the watchdog
     * thread. A test substitutes a delivery that waits until the booting thread is joining the watchdog,
     * and does not return until the interrupt has been taken, so the interrupt is never lost to the
     * watchdog's own termination.
     */
    private static final AtomicReference<Consumer<Thread>> INTERRUPTER = new AtomicReference<>(Thread::interrupt);

    private static final System.Logger LOG = System.getLogger(InitDeadlineWatch.class.getName());

    /** Name prefix of the watchdog threads; the suffix is the subsystem name. */
    private static final String THREAD_NAME_PREFIX = "exeris-boot-init-deadline-";

    private static final int ARMED    = 0;
    private static final int FIRED    = 1;
    private static final int DISARMED = 2;

    private final KernelHealthMonitor healthMonitor;
    private final String subsystemName;
    private final long budgetNanos;
    private final long armedAtNanos;
    private final Thread bootingThread;
    private final Thread watchdog;
    private final AtomicInteger state = new AtomicInteger(ARMED);

    /**
     * Prepares the watch. The watchdog thread is started by {@link #initialize}.
     *
     * @param healthMonitor  the monitor the subsystem is marked FAILED in when the deadline fires
     * @param subsystemName  the subsystem whose {@code initialize()} is watched
     * @param budgetNanos    the longest {@code initialize()} may run, in nanoseconds; positive
     */
    /* default */ InitDeadlineWatch(KernelHealthMonitor healthMonitor, String subsystemName, long budgetNanos) {
        this.healthMonitor = healthMonitor;
        this.subsystemName = subsystemName;
        this.budgetNanos = budgetNanos;
        this.armedAtNanos = System.nanoTime();
        this.bootingThread = Thread.currentThread();
        this.watchdog = Thread.ofPlatform()
                .name(THREAD_NAME_PREFIX + subsystemName)
                .daemon(true)
                .unstarted(this);
    }

    /**
     * Runs {@code subsystem.initialize()} on the calling thread under a deadline. With {@code budgetNanos}
     * of {@code 0} the deadline is disabled: no watchdog is started and nothing is allocated for it.
     *
     * <p>A missed deadline replaces the outcome. The deadline failure is thrown whether {@code initialize()}
     * then returned or threw, and any throwable it threw becomes the cause. A {@link VirtualMachineError} is
     * never wrapped.
     *
     * @param subsystem     the subsystem being initialized
     * @param healthMonitor the monitor the subsystem is marked FAILED in when the deadline fires
     * @param budgetNanos   the longest {@code initialize()} may run, in nanoseconds; {@code 0} for none
     * @throws SubsystemInitDeadlineException ({@code EX-BOOT-0003}) if the deadline fired
     */
    /* default */ static void initialize(Subsystem subsystem, KernelHealthMonitor healthMonitor, long budgetNanos) {
        if (budgetNanos == 0) {
            subsystem.initialize();
            return;
        }
        InitDeadlineWatch watch = new InitDeadlineWatch(healthMonitor, subsystem.name(), budgetNanos);
        watch.start();
        watch.runWatched(subsystem);
    }

    /**
     * Substitutes how the watchdog delivers its interrupt; tests only.
     *
     * @param delivery the delivery to use; {@code Thread::interrupt} restores the default
     */
    /* default */ static void deliverInterruptWith(Consumer<Thread> delivery) {
        INTERRUPTER.set(delivery);
    }

    private void runWatched(Subsystem subsystem) {
        try {
            subsystem.initialize();
        } catch (VirtualMachineError fatal) {
            finish();
            throw fatal;
        } catch (Throwable ex) { // NOPMD NOSONAR — the deadline decides whether ex is rethrown; a sneaky-thrown
                                 // checked exception must disarm the watchdog as well
            if (finish()) {
                throw exceeded(ex);
            }
            throw ex;
        }
        if (finish()) {
            throw exceeded(null);
        }
    }

    /**
     * Starts the watchdog thread. Called on the booting thread, before {@code initialize()}.
     */
    private void start() {
        watchdog.start();
    }

    @Override
    public void run() {
        while (state.get() == ARMED) {
            long remaining = budgetNanos - (System.nanoTime() - armedAtNanos);
            if (remaining <= 0) {
                break;
            }
            LockSupport.parkNanos(this, remaining);
        }
        if (state.compareAndSet(ARMED, FIRED)) {
            fire();
        }
    }

    private void fire() {
        long deadlineMs = TimeUnit.NANOSECONDS.toMillis(budgetNanos);
        try {
            BootstrapJfrEvents.emitInitDeadlineExceeded(subsystemName, deadlineMs);
            LOG.log(System.Logger.Level.ERROR,
                    "  [{0}] initialize() did not return within {1} ms; interrupting the booting thread",
                    subsystemName, deadlineMs);
            healthMonitor.markSubsystemState(subsystemName, KernelHealthMonitor.SubsystemState.FAILED);
        } finally {
            // Last, and in a finally: a failure above must not leave the boot blocked on an
            // initialize() that nothing is going to interrupt.
            INTERRUPTER.get().accept(bootingThread);
        }
    }

    /**
     * Ends the watch once {@code initialize()} has returned or thrown. The watchdog is joined either way,
     * so it never outlives its subsystem's initialization.
     *
     * @return {@code true} if the deadline fired; the interrupt status has then been cleared
     */
    private boolean finish() {
        if (state.compareAndSet(ARMED, DISARMED)) {
            // Nothing was delivered on this path, so the booting thread's interrupt status is left alone.
            LockSupport.unpark(watchdog);
            if (awaitWatchdog()) {
                bootingThread.interrupt();
            }
            return false;
        }
        // The interrupt that ends this join is usually the watchdog's own, delivered last in fire(), and
        // the flag a subsystem restores after catching it is indistinguishable from it. All of them are
        // discarded and the flag is cleared, so none reaches the next subsystem or the caller of boot().
        // A genuine external interrupt arriving in this window is lost with them; the missed deadline
        // is the contract, so that loss is accepted.
        awaitWatchdog();
        Thread.interrupted();
        return true;
    }

    /**
     * Builds the failure the orchestrator throws for a missed deadline.
     *
     * @param cause the throwable {@code initialize()} threw, or {@code null} when it returned
     * @return the deadline exception, carrying the configured deadline in milliseconds
     */
    private SubsystemInitDeadlineException exceeded(Throwable cause) {
        return new SubsystemInitDeadlineException(subsystemName,
                TimeUnit.NANOSECONDS.toMillis(budgetNanos), cause);
    }

    /**
     * Joins the watchdog, retrying through interrupts.
     *
     * @return {@code true} if an interrupt arrived while joining
     */
    private boolean awaitWatchdog() {
        boolean interrupted = false;
        while (watchdog.isAlive()) {
            try {
                watchdog.join();
            } catch (InterruptedException _) { // NOSONAR — reported to finish(), which restores or discards it
                interrupted = true;
            }
        }
        return interrupted;
    }
}
