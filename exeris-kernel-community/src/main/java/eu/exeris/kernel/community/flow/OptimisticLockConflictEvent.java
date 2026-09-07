/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.flow;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted when {@link JdbcFlowSnapshotStore} raises an optimistic-lock
 * conflict ({@code EX-FLOW-7002}, phase {@code OPTIMISTIC_LOCK_CONFLICT}).
 *
 * <p>Captures the discriminator between the two race-loser flavors named in
 * ADR-013 §5: {@code UPDATE_STALE} (a UPDATE with a stale {@code schemaVersion})
 * and {@code INSERT_TOCTOU} (an INSERT race remapped from an integrity-constraint
 * violation). Operators tracking distributed-saga health watch the rate of this
 * event to detect contention hotspots before they manifest as wake-storm failures.
 *
 * @since 0.7
 */
@Name("eu.exeris.kernel.flow.OptimisticLockConflict")
@Label("Distributed Saga — Optimistic-Lock Conflict")
@Category({"Exeris Kernel", "Flow"})
@Description("Emitted when JdbcFlowSnapshotStore.save raises EX-FLOW-7002 because a "
        + "concurrent writer has already advanced the saga row past the loaded "
        + "schemaVersion (UPDATE_STALE) or has just inserted the same instance row "
        + "(INSERT_TOCTOU).")
@StackTrace(false)
public final class OptimisticLockConflictEvent extends Event {

    /** Race-loser flavor: stale {@code schemaVersion} on UPDATE. */
    public static final String PHASE_UPDATE_STALE = "UPDATE_STALE";

    /** Race-loser flavor: integrity-constraint violation on first-writer INSERT. */
    public static final String PHASE_INSERT_TOCTOU = "INSERT_TOCTOU";

    @Label("Engine Name")
    @Description("Human-readable engine name from FlowEngineConfig.engineName()")
    /* default */ String engineName;

    @Label("Conflict Phase")
    @Description("UPDATE_STALE for stale-version UPDATE losers; INSERT_TOCTOU for first-writer "
            + "INSERT race losers — see ADR-013 §5")
    /* default */ String phase;

    @Label("Loaded Schema Version")
    @Description("schemaVersion of the snapshot the losing writer attempted to save with — i.e. "
            + "the version it had loaded prior to the failed save")
    /* default */ long loadedSchemaVersion;

    /**
     * Constructed by {@link #emit} — and, reflectively, by the JFR runtime when this event type
     * is registered — with every field left unset; {@code emit} assigns them and commits only if
     * the recording has this event type enabled.
     */
    public OptimisticLockConflictEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /* default */ static void emit(String engineName, String phase, long loadedSchemaVersion) {
        OptimisticLockConflictEvent event = new OptimisticLockConflictEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.begin();
        event.engineName = engineName;
        event.phase = phase;
        event.loadedSchemaVersion = loadedSchemaVersion;
        event.commit();
    }
}
