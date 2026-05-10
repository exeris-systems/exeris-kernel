/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.exceptions.flow;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Thrown when the {@link eu.exeris.kernel.spi.flow.FlowEngine} fails to start,
 * stop, or perform a lifecycle operation.
 *
 * <h2>rawArgs Binary Layout — {@value KernelErrorCodes#EX_FLOW_7002}</h2>
 * <ul>
 *   <li>index 0 – {@code String} engineName</li>
 *   <li>index 1 – {@code String} phase — one of: {@code "START"}, {@code "STOP"},
 *       {@code "COMPILE"}, {@code "SCHEDULE"}, {@code "OPTIMISTIC_LOCK_CONFLICT"} (since 0.7)</li>
 *   <li>index 2 – {@code String} staticReasonCode — stable identifier, never user-supplied
 *       (e.g. {@code "STARTUP_FAILED"}, {@code "COMPILE_FAILED"}, {@code "QUEUE_FULL"},
 *       {@code "STALE_VERSION"})</li>
 *   <li>index 3 – {@code int} contextValue — phase-specific numeric context
 *       (e.g. current queue depth for SCHEDULE phase, incoming schemaVersion for
 *       OPTIMISTIC_LOCK_CONFLICT); {@code -1} when not applicable</li>
 * </ul>
 *
 * @since 0.5.0
 */
public final class FlowEngineException extends ExerisKernelException {

    private static final String MSG_ENGINE_FAILURE  = "Flow engine lifecycle failure";
    private static final String REASON_STARTUP      = "STARTUP_FAILED";
    private static final String REASON_COMPILE      = "COMPILE_FAILED";
    private static final String REASON_QUEUE_FULL   = "QUEUE_FULL";
    private static final String REASON_STALE_VERSION = "STALE_VERSION";

    public FlowEngineException(String message) {
        super(KernelErrorCodes.EX_FLOW_7002, message, (Throwable) null);
    }

    public FlowEngineException(String message, Throwable cause) {
        super(KernelErrorCodes.EX_FLOW_7002, message, cause);
    }

    private FlowEngineException(String errorCode, String message, Throwable cause, Object... rawArgs) {
        super(errorCode, message, cause, rawArgs);
    }

    public static FlowEngineException startupFailure(String engineName, Throwable cause) {
        return new FlowEngineException(KernelErrorCodes.EX_FLOW_7002, MSG_ENGINE_FAILURE, cause,
                engineName, "START", REASON_STARTUP, -1);
    }

    public static FlowEngineException compileFailure(String engineName, Throwable cause) {
        return new FlowEngineException(KernelErrorCodes.EX_FLOW_7002, MSG_ENGINE_FAILURE, cause,
                engineName, "COMPILE", REASON_COMPILE, -1);
    }

    /**
     * Creates an exception for a full scheduler queue.
     *
     * <p>rawArgs layout: {@code [engineName, "SCHEDULE", "QUEUE_FULL", queueDepth]}.
     * {@code queueDepth} is stored as a typed {@code int} rawArg — no String formatting.
     *
     * @param engineName the engine name
     * @param queueDepth current depth of the scheduler queue at the time of overflow
     */
    public static FlowEngineException schedulerFull(String engineName, int queueDepth) {
        return new FlowEngineException(KernelErrorCodes.EX_FLOW_7002, MSG_ENGINE_FAILURE, null,
                engineName, "SCHEDULE", REASON_QUEUE_FULL, queueDepth);
    }

    /**
     * Creates an exception for an optimistic-lock conflict on a durable snapshot store
     * (see ADR-013). Raised when a durable {@code FlowSnapshotStore} implementation
     * finds the on-disk {@code schemaVersion} no longer matches the incoming snapshot's
     * version — another kernel advanced the saga first.
     *
     * <p>rawArgs layout: {@code [engineName, "OPTIMISTIC_LOCK_CONFLICT", "STALE_VERSION",
     * incomingSchemaVersion]}. The version is bound to {@code int}; saga rows that exceed
     * {@code Integer.MAX_VALUE} versions are not anticipated within the operational
     * lifetime, but if needed callers may clamp via {@link Math#min}.
     *
     * @param engineName            the engine name
     * @param incomingSchemaVersion the schemaVersion the caller attempted to write
     * @since 0.7.0
     */
    public static FlowEngineException optimisticLockConflict(String engineName, long incomingSchemaVersion) {
        return optimisticLockConflict(engineName, incomingSchemaVersion, null);
    }

    /**
     * Cause-preserving variant of {@link #optimisticLockConflict(String, long)}. Used when the
     * conflict surfaces through an underlying driver exception — for example, a composite-PK
     * integrity-constraint violation raised by a concurrent INSERT race-loser in a durable
     * binding. The original {@code SQLException}-shaped chain is preserved for diagnostics;
     * the caller still observes the same OCC contract from ADR-013 §5.
     *
     * @param engineName            the engine name
     * @param incomingSchemaVersion the schemaVersion the caller attempted to write
     * @param cause                 underlying driver exception that surfaced the conflict; may be {@code null}
     * @since 0.7.0
     */
    public static FlowEngineException optimisticLockConflict(
            String engineName, long incomingSchemaVersion, Throwable cause) {
        int contextValue = (int) Math.min(incomingSchemaVersion, Integer.MAX_VALUE);
        return new FlowEngineException(KernelErrorCodes.EX_FLOW_7002, MSG_ENGINE_FAILURE, cause,
                engineName, "OPTIMISTIC_LOCK_CONFLICT", REASON_STALE_VERSION, contextValue);
    }
}

