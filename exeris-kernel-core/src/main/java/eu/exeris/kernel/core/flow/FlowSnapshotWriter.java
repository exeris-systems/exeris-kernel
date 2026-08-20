/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.flow;

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

    private FlowSnapshotWriter() {
        // static-only
    }

    /* default */ static void save(FlowSnapshotStore store,
                     RuntimeFlowInstance instance,
                     FlowState state,
                     int stepIndex) {
        try {
            store.save(instance.toSnapshot(state, stepIndex));
        } catch (RuntimeException | Error saveFailure) { //NOPMD AvoidCatchingGenericException
            FlowSnapshotPersistFailedEvent.emit(
                    instance.definitionName(),
                    state.name(),
                    stepIndex,
                    instance.key().instanceIdMost(),
                    instance.key().instanceIdLeast(),
                    saveFailure);
            throw saveFailure;
        }
    }
}
