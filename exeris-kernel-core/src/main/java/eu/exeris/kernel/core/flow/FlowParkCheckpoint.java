/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.flow;

import java.util.Collection;

/**
 * The PARK checkpoint, which must not take the saga down with it.
 *
 * <p>A refused save must not escape {@code runInstance} uncaught: by the time the write can fail,
 * the instance has already been flipped to PARKED and registered, so an uncaught exception would
 * leave it advertising a durability it does not have. Flipping the state only after a successful
 * write would look like the fix and is worse — the instance is wakeable in this JVM, so refusing
 * to park it would turn a transient store outage into a saga lost even without a restart.
 *
 * <p>So the park stands and the claim does not. See docs/subsystems/flow.md for the contract.
 */
final class FlowParkCheckpoint {

    /** Attempts before the instance is marked non-durable. */
    private static final int ATTEMPTS = 2;

    /** Not instantiable; every member here is static. */
    private FlowParkCheckpoint() {
        // static-only
    }

    /**
     * Runs the checkpoint, absorbing refusal into a non-durable mark rather than an escape.
     *
     * <p>Retries are few and unspaced on purpose: this runs under the instance monitor, and the
     * failure it most often meets is an exhausted connection pool, where waiting longer holds a
     * thread against the very contention that caused it. {@code Error} still propagates - an
     * exhausted heap is not a checkpoint that can be retried.
     *
     * @param instance  the flow instance the checkpoint belongs to; marked non-durable once the
     *                  retry budget is spent
     * @param stepIndex the step index the checkpoint is taken at
     * @param attempt   the underlying write, retried on {@code RuntimeException} until it succeeds
     *                  or the retry budget is spent
     */
    /* default */ static void persist(RuntimeFlowInstance instance, int stepIndex, Attempt attempt) {
        for (int i = 1; i <= ATTEMPTS; i++) {
            try {
                attempt.persist(instance, stepIndex);
                return;
            } catch (RuntimeException saveFailure) { //NOPMD AvoidCatchingGenericException
                if (i == ATTEMPTS) {
                    instance.markCheckpointDirty();
                }
            }
        }
    }

    /**
     * Parked instances the store refused: wakeable now, gone after a restart.
     *
     * @param parked the parked instances to scan
     * @return count of instances whose PARK checkpoint was refused and are therefore marked
     *         non-durable
     */
    /* default */ static long countNonDurable(Collection<RuntimeFlowInstance> parked) {
        return parked.stream().filter(RuntimeFlowInstance::checkpointDirty).count();
    }

    /** A single checkpoint write, supplied by the caller and retried on failure by the enclosing class. */
    /* default */ @FunctionalInterface
    interface Attempt {
        void persist(RuntimeFlowInstance instance, int stepIndex);
    }
}
