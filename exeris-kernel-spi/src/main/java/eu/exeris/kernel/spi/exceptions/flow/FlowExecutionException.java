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
 * Thrown when a flow step execution fails with an unrecoverable error.
 *
 * <h2>rawArgs Binary Layout — {@value KernelErrorCodes#EX_FLOW_7003}</h2>
 * <ul>
 *   <li>index 0 – {@code String} definitionName</li>
 *   <li>index 1 – {@code long} instanceIdMost</li>
 *   <li>index 2 – {@code long} instanceIdLeast</li>
 *   <li>index 3 – {@code int}  stepIndex</li>
 *   <li>index 4 – {@code String} reason</li>
 * </ul>
 *
 * @since 0.5.0
 */
public final class FlowExecutionException extends ExerisKernelException {

    private static final String MSG_STEP_FAILURE = "Flow step execution failure";
    private static final String MSG_COMP_FAILURE  = "Flow compensation step failure";

    public FlowExecutionException(String message) {
        super(KernelErrorCodes.EX_FLOW_7003, message, (Throwable) null);
    }

    public FlowExecutionException(String message, Throwable cause) {
        super(KernelErrorCodes.EX_FLOW_7003, message, cause);
    }

    private FlowExecutionException(String errorCode, String message, Throwable cause, Object... rawArgs) {
        super(errorCode, message, cause, rawArgs);
    }

    public static FlowExecutionException stepFailure(
            String definitionName, long idMost, long idLeast, int stepIndex, Throwable cause) {
        return new FlowExecutionException(KernelErrorCodes.EX_FLOW_7003, MSG_STEP_FAILURE, cause,
                definitionName, idMost, idLeast, stepIndex, cause != null ? cause.getMessage() : "unknown");
    }

    public static FlowExecutionException compensationFailure(
            String definitionName, long idMost, long idLeast, int stepIndex, String reason) {
        return new FlowExecutionException(KernelErrorCodes.EX_FLOW_7003, MSG_COMP_FAILURE, null,
                definitionName, idMost, idLeast, stepIndex, reason);
    }
}

