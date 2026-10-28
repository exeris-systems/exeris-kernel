/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * Whether the reactor has armed a {@link NativeTcpStream}'s key yet, and the one caller that may be
 * waiting for it.
 *
 * <p><b>Allocation:</b> none per call; one instance per stream.
 * <p><b>Thread confinement:</b> any thread; the ready flag and the waiter are atomic, and at most one
 * thread waits at a time.
 * <p><b>Ownership:</b> holds no buffer.
 */
final class NativeTcpStreamRegistrationGate {

    private static final long REGISTRATION_BACKOFF_NANOS = 100_000L;
    private static final long REGISTRATION_TIMEOUT_MILLIS = 5_000L;

    private final BooleanSupplier ending;
    private final AtomicBoolean ready = new AtomicBoolean(true);
    private final AtomicReference<Thread> waiter = new AtomicReference<>();

    /**
     * Creates a gate that starts ready.
     *
     * @param ending whether the owning stream is closing or closed, which ends a wait
     */
    /* default */ NativeTcpStreamRegistrationGate(BooleanSupplier ending) {
        this.ending = Objects.requireNonNull(ending, "ending must not be null");
    }

    /* default */ void markPending() {
        ready.set(false);
    }

    /* default */ void markReady() {
        ready.set(true);
        Thread waiterThread = waiter.getAndSet(null);
        if (waiterThread != null) {
            LockSupport.unpark(waiterThread);
        }
    }

    /* default */ Thread waiter() {
        return waiter.get();
    }

    /* default */ boolean awaitReady(boolean blocking) {
        if (ready.get()) {
            return true;
        }
        if (!blocking) {
            return false;
        }

        Thread currentThread = Thread.currentThread();
        waiter.set(currentThread);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REGISTRATION_TIMEOUT_MILLIS);
        try {
            while (!ending.getAsBoolean()) {
                if (ready.get()) {
                    return true;
                }
                if (System.nanoTime() >= deadline) {
                    return false;
                }
                LockSupport.parkNanos(REGISTRATION_BACKOFF_NANOS);
                if (Thread.interrupted()) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Registration wait interrupted");
                }
            }
            return false;
        } finally {
            waiter.compareAndSet(currentThread, null);
        }
    }
}
