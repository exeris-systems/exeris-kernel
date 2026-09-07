/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.flow.model.FlowSnapshot;
import eu.exeris.kernel.spi.flow.model.FlowSnapshotStore;
import eu.exeris.kernel.spi.flow.model.FlowState;

/**
 * The durable checkpoint write, with the failure made observable.
 *
 * <p>Exists as its own seam because a refused {@code save} is not a neutral event: the caller
 * has already applied the in-memory transition the snapshot was meant to make durable, so a
 * failure leaves memory and store disagreeing and then escapes {@code runInstance} uncaught.
 * Behaviour here is unchanged - the exception still propagates - but it no longer propagates
 * silently, which is what made this class of loss cost a day to diagnose from stderr alone.
 */
final class FlowSnapshotWriter {

    /** Not instantiable; every member here is static. */
    private FlowSnapshotWriter() {
        // static-only
    }

    /**
     * Writes the checkpoint for a live instance's current transition.
     *
     * @param store     the durable store to write to
     * @param instance  the instance whose state is being checkpointed
     * @param state     the state the checkpoint records
     * @param stepIndex the step index the checkpoint records
     * @throws eu.exeris.kernel.spi.exceptions.flow.FlowEngineException ({@code EX-FLOW-7002}) if
     *         the store refuses or fails the write; the failure is recorded via
     *         {@link FlowSnapshotPersistFailedEvent} before it propagates
     */
    /* default */ static void save(FlowSnapshotStore store,
                                   RuntimeFlowInstance instance,
                                   FlowState state,
                                   int stepIndex) {
        write(store, instance.toSnapshot(state, stepIndex), instance.definitionName(),
                state.name(), stepIndex,
                instance.key().instanceIdMost(), instance.key().instanceIdLeast());
    }

    /**
     * The migration-on-load write, which carries a snapshot rather than a live instance.
     *
     * <p>Same guard for the same reason: this write also runs on a wake, so it meets the same
     * exhausted pool, and a refusal here abandons a saga mid-migration rather than mid-park.
     *
     * @param store    the durable store to write to
     * @param snapshot the already-migrated snapshot to persist
     * @throws eu.exeris.kernel.spi.exceptions.flow.FlowEngineException ({@code EX-FLOW-7002}) if
     *         the store refuses or fails the write; the failure is recorded via
     *         {@link FlowSnapshotPersistFailedEvent} before it propagates
     */
    /* default */ static void save(FlowSnapshotStore store, FlowSnapshot snapshot) {
        write(store, snapshot, snapshot.definitionName(), snapshot.state().name(),
                snapshot.currentStep(), snapshot.instanceIdMost(), snapshot.instanceIdLeast());
    }

    /**
     * Attempts the write, emitting {@link FlowSnapshotPersistFailedEvent} with the given
     * identifying fields and rethrowing on failure.
     *
     * @param store           the durable store to write to
     * @param snapshot        the snapshot to persist
     * @param definitionName  the flow definition name to record on a failure event
     * @param state           the state name to record on a failure event
     * @param stepIndex       the step index to record on a failure event
     * @param instanceIdMost  most-significant bits of the flow instance key UUID
     * @param instanceIdLeast least-significant bits of the flow instance key UUID
     * @throws eu.exeris.kernel.spi.exceptions.flow.FlowEngineException ({@code EX-FLOW-7002}) if
     *         the store refuses or fails the write
     */
    private static void write(FlowSnapshotStore store, FlowSnapshot snapshot,
                              String definitionName, String state, int stepIndex,
                              long instanceIdMost, long instanceIdLeast) {
        try {
            store.save(snapshot);
        } catch (RuntimeException | Error saveFailure) { //NOPMD AvoidCatchingGenericException
            FlowSnapshotPersistFailedEvent.emit(
                    definitionName, state, stepIndex,
                    instanceIdMost, instanceIdLeast, saveFailure);
            throw saveFailure;
        }
    }
}
