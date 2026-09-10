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
 * JFR event emitted when {@link JdbcFlowSnapshotStore#save}'s inner transactional
 * try-block catches a non-OCC {@code PersistenceProviderException} and rolls back
 * (driver timeout, deadlock, network partition, schema mismatch, and similar). A
 * {@code PersistenceProviderException} thrown while opening the connection or beginning
 * the transaction propagates without emitting this event, and an optimistic-lock conflict
 * emits {@link OptimisticLockConflictEvent} instead.
 *
 * <p>Captures the engine name, the conflicting SQLSTATE (when extractable from the
 * exception chain), and the wrapped exception class and message. Secret-safe — JDBC
 * credentials are never included.
 */
@Name("eu.exeris.kernel.flow.FlowSnapshotSaveFailed")
@Label("Flow Snapshot Save Failed")
@Category({"Exeris Kernel", "Flow"})
@Description("Emitted when JdbcFlowSnapshotStore.save catches a non-OCC "
        + "PersistenceProviderException, rolls back the transaction, and wraps the cause "
        + "as FlowEngineException. Operators rely on this to attribute save-side failures "
        + "to specific SQLSTATE classes (connection lost, deadlock, schema drift, etc.) "
        + "without the OCC race-loser noise that OptimisticLockConflictEvent already filters.")
@StackTrace(false)
public final class FlowSnapshotSaveFailedEvent extends Event {

    /** Sentinel for "no SQLSTATE could be extracted from the cause chain". */
    public static final String SQLSTATE_UNKNOWN = "<unknown>";

    @Label("Engine Name")
    @Description("Human-readable engine name from FlowEngineConfig.engineName()")
    /* default */ String engineName;

    @Label("SQLSTATE")
    @Description("ANSI SQLSTATE class of the underlying SQLException, or '<unknown>' "
            + "if no SQLException was found in the cause chain")
    /* default */ String sqlState;

    @Label("Exception Class")
    @Description("Fully-qualified class name of the PersistenceProviderException cause")
    /* default */ String exceptionClass;

    @Label("Exception Message")
    @Description("Exception message — secret-safe; JDBC credentials are never included")
    /* default */ String exceptionMessage;

    /**
     * Constructed by {@link #emit} — and, reflectively, by the JFR runtime when this event type
     * is registered — with every field left unset; {@code emit} assigns them and commits only if
     * the recording has this event type enabled.
     */
    public FlowSnapshotSaveFailedEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /* default */ static void emit(String engineName,
                                   String sqlState,
                                   String exceptionClass,
                                   String exceptionMessage) {
        FlowSnapshotSaveFailedEvent event = new FlowSnapshotSaveFailedEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.begin();
        event.engineName = engineName;
        event.sqlState = sqlState;
        event.exceptionClass = exceptionClass;
        event.exceptionMessage = exceptionMessage;
        event.commit();
    }
}
