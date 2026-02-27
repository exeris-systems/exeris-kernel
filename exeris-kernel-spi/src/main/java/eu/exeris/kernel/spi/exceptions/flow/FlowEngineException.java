/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
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
 *       {@code "COMPILE"}, {@code "SCHEDULE"}</li>
 *   <li>index 2 – {@code String} staticReasonCode — stable identifier, never user-supplied
 *       (e.g. {@code "STARTUP_FAILED"}, {@code "COMPILE_FAILED"}, {@code "QUEUE_FULL"})</li>
 *   <li>index 3 – {@code int} contextValue — phase-specific numeric context
 *       (e.g. current queue depth for SCHEDULE phase); {@code -1} when not applicable</li>
 * </ul>
 *
 * @since 0.5.0
 */
public final class FlowEngineException extends ExerisKernelException {

    private static final String MSG_ENGINE_FAILURE  = "Flow engine lifecycle failure";
    private static final String REASON_STARTUP      = "STARTUP_FAILED";
    private static final String REASON_COMPILE      = "COMPILE_FAILED";
    private static final String REASON_QUEUE_FULL   = "QUEUE_FULL";

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
}

