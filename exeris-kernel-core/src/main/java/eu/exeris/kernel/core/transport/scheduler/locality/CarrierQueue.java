/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.scheduler.locality;

/**
 * The run queue of one carrier.
 *
 * <p>Exactly one thread, the carrier's own, is the <em>owner</em>: only it polls, tests emptiness
 * and offers locally. Any thread may offer remotely, ask for a size estimate, or steal.
 *
 * <p>Wakeup protocol: a producer publishes with {@link #offerRemote} and only then reads the
 * carrier's park state; the owner moves the state to PARKED and only then calls {@link #isEmpty}.
 * For that to be a handshake, {@code offerRemote} must publish with a volatile write or a
 * read-modify-write, and {@code isEmpty} must read that publication with a volatile read and
 * report a task that is published but not yet readable as present.
 */
interface CarrierQueue {

    /** Publishes a task from any thread. */
    void offerRemote(Runnable task);

    /** Publishes a task from the owner thread; needs no wakeup because the owner is running. */
    void offerLocal(Runnable task);

    /** Removes the next task; owner only. {@code null} means nothing was available. */
    Runnable poll();

    /**
     * Whether no task is published or in flight; owner only. Never reports empty while a producer
     * has claimed a place that is not yet readable.
     */
    boolean isEmpty();

    /** An O(1) estimate of the number of queued tasks; any thread. */
    int sizeEstimate();

    /**
     * Takes one task another carrier may run; any thread. {@code null} when none is available
     * (or when the implementation does not support stealing).
     */
    Runnable steal();
}
