/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 *   <li>index 1 – {@code long}   instanceIdMost</li>
 *   <li>index 2 – {@code long}   instanceIdLeast</li>
 *   <li>index 3 – {@code int}    stepIndex</li>
 *   <li>index 4 – {@code String} staticReasonCode — stable identifier, never user-supplied
 *       (e.g. {@code "STEP_FAILED"}, {@code "COMPENSATION_FAILED"})</li>
 *   <li>index 5 – {@code String} causeType — {@code cause.getClass().getName()} or
 *       {@code "none"} when no cause; class names are stable and not user-controlled</li>
 * </ul>
 *
 * @since 0.5
 */
public final class FlowExecutionException extends ExerisKernelException {

    private static final String MSG_STEP_FAILURE  = "Flow step execution failure";
    private static final String MSG_COMP_FAILURE  = "Flow compensation step failure";
    private static final String REASON_STEP       = "STEP_FAILED";
    private static final String REASON_COMP       = "COMPENSATION_FAILED";
    private static final String CAUSE_NONE        = "none";

    /**
     * Creates an {@code EX-FLOW-7003} step failure that carries a message and no glass-box context —
     * {@code rawArgs} is empty, so no consumer can read the instance, step index or reason off it.
     *
     * @param message stable failure description; never a formatted string, per the Glass-Box
     *                zero-allocation contract
     * @apiNote Use {@link #stepFailure} or {@link #compensationFailure} at any site that knows which
     *          instance and step failed; both fill the layout this class documents.
     */
    public FlowExecutionException(String message) {
        super(KernelErrorCodes.EX_FLOW_7003, message, (Throwable) null);
    }

    /**
     * Creates an {@code EX-FLOW-7003} step failure that wraps an underlying cause and carries no
     * glass-box context — {@code rawArgs} is empty.
     *
     * @param message stable failure description; never a formatted string, per the Glass-Box
     *                zero-allocation contract
     * @param cause   the throwable the step raised; may be {@code null}
     * @apiNote Use {@link #stepFailure} or {@link #compensationFailure} at any site that knows which
     *          instance and step failed.
     */
    public FlowExecutionException(String message, Throwable cause) {
        super(KernelErrorCodes.EX_FLOW_7003, message, cause);
    }

    private FlowExecutionException(String errorCode, String message, Throwable cause, Object... rawArgs) {
        super(errorCode, message, cause, rawArgs);
    }

    /**
     * Creates the failure for a forward step that raised — the outcome that puts the instance on
     * the compensation path.
     *
     * <p>rawArgs layout: {@code [definitionName, idMost, idLeast, stepIndex, "STEP_FAILED",
     * causeType]}, where {@code causeType} is the cause's class name, or {@code "none"} when there
     * is no cause. The class name is recorded rather than the message because it is stable and not
     * user-controlled.
     *
     * @param definitionName the definition the failing instance is running
     * @param idMost         high 64 bits of the flow instance id
     * @param idLeast        low 64 bits of the flow instance id
     * @param stepIndex      zero-based index of the step that raised
     * @param cause          the throwable the step raised; may be {@code null}
     * @return an {@code EX-FLOW-7003} exception with {@code staticReasonCode="STEP_FAILED"}
     */
    public static FlowExecutionException stepFailure(
            String definitionName, long idMost, long idLeast, int stepIndex, Throwable cause) {
        String causeType = cause != null ? cause.getClass().getName() : CAUSE_NONE;
        return new FlowExecutionException(KernelErrorCodes.EX_FLOW_7003, MSG_STEP_FAILURE, cause,
                definitionName, idMost, idLeast, stepIndex, REASON_STEP, causeType);
    }

    /**
     * Creates the failure for a compensation action that raised while a rollback was unwinding.
     *
     * <p>rawArgs layout: {@code [definitionName, idMost, idLeast, stepIndex, "COMPENSATION_FAILED",
     * causeType]}, with {@code causeType} as for {@link #stepFailure}. The distinct reason code is
     * what separates a failed rollback from a failed forward step in the same {@code EX-FLOW-7003}
     * stream.
     *
     * @param definitionName the definition the unwinding instance is running
     * @param idMost         high 64 bits of the flow instance id
     * @param idLeast        low 64 bits of the flow instance id
     * @param stepIndex      zero-based index of the step whose compensation raised
     * @param cause          the throwable the compensation raised; may be {@code null}
     * @return an {@code EX-FLOW-7003} exception with {@code staticReasonCode="COMPENSATION_FAILED"}
     * @apiNote A compensation failure does not stop the unwind — the engine reports it and continues
     *          to the next entry on the compensation stack, because cleanup has to reach every step
     *          that needs it, not only the ones before the first failure.
     */
    public static FlowExecutionException compensationFailure(
            String definitionName, long idMost, long idLeast, int stepIndex, Throwable cause) {
        String causeType = cause != null ? cause.getClass().getName() : CAUSE_NONE;
        return new FlowExecutionException(KernelErrorCodes.EX_FLOW_7003, MSG_COMP_FAILURE, cause,
                definitionName, idMost, idLeast, stepIndex, REASON_COMP, causeType);
    }
}

