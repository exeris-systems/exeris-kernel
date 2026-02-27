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
 *   <li>index 1 – {@code String} phase (e.g. "START", "STOP", "COMPILE")</li>
 *   <li>index 2 – {@code String} reason</li>
 * </ul>
 *
 * @since 0.5.0
 */
public final class FlowEngineException extends ExerisKernelException {

    private static final String MSG_ENGINE_FAILURE = "Flow engine lifecycle failure";

    public FlowEngineException(String message) {
        super(KernelErrorCodes.EX_FLOW_7002, message, (Throwable) null);
    }

    public FlowEngineException(String message, Throwable cause) {
        super(KernelErrorCodes.EX_FLOW_7002, message, cause);
    }

    private FlowEngineException(String errorCode, String message, Throwable cause, Object... rawArgs) {
        super(errorCode, message, cause, rawArgs);
    }

    public static FlowEngineException startupFailure(String engineName, String reason, Throwable cause) {
        return new FlowEngineException(KernelErrorCodes.EX_FLOW_7002, MSG_ENGINE_FAILURE, cause,
                engineName, "START", reason);
    }

    public static FlowEngineException compileFailure(String engineName, String reason, Throwable cause) {
        return new FlowEngineException(KernelErrorCodes.EX_FLOW_7002, MSG_ENGINE_FAILURE, cause,
                engineName, "COMPILE", reason);
    }

    public static FlowEngineException schedulerFull(String engineName, int queueDepth) {
        return new FlowEngineException(KernelErrorCodes.EX_FLOW_7002, MSG_ENGINE_FAILURE, null,
                engineName, "SCHEDULE", "Scheduler queue full at depth: " + queueDepth);
    }
}

