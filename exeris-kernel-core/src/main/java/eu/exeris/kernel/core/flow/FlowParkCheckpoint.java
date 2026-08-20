/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.flow;

import java.util.Collection;

/**
 * The PARK checkpoint, which must not take the saga down with it.
 *
 * <p>A refused save used to escape {@code runInstance} uncaught and kill the flow virtual thread,
 * with the instance already flipped to PARKED and registered - advertising a durability it did not
 * have. Flipping the state only after a successful write looks like the honest repair and is
 * worse: the instance is wakeable in this JVM, so refusing to park it turns a transient store
 * outage into a saga lost even without a restart.
 *
 * <p>So the park stands and the claim does not. See docs/subsystems/flow.md for the contract.
 */
final class FlowParkCheckpoint {

    /** Attempts before the instance is marked non-durable. */
    private static final int ATTEMPTS = 2;

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

    /** Parked instances the store refused: wakeable now, gone after a restart. */
    /* default */ static long countNonDurable(Collection<RuntimeFlowInstance> parked) {
        return parked.stream().filter(RuntimeFlowInstance::checkpointDirty).count();
    }

    /* default */ @FunctionalInterface
    interface Attempt {
        void persist(RuntimeFlowInstance instance, int stepIndex);
    }
}
