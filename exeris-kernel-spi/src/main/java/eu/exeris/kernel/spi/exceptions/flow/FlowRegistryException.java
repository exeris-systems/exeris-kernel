/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.exceptions.flow;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Thrown when a {@link eu.exeris.kernel.spi.flow.FlowRegistry} operation fails
 * (duplicate registration, unknown step lookup, etc.).
 *
 * <h2>rawArgs Binary Layout — {@value KernelErrorCodes#EX_FLOW_7004}</h2>
 * <ul>
 *   <li>index 0 – {@code int}    stepId — the step identifier involved in the failure</li>
 *   <li>index 1 – {@code String} staticReasonCode — stable identifier, never user-supplied
 *       (e.g. {@code "DUPLICATE_STEP"}, {@code "STEP_NOT_FOUND"})</li>
 * </ul>
 *
 * @since 0.5
 */
public final class FlowRegistryException extends ExerisKernelException {

    private static final String MSG_DUPLICATE   = "Flow registry: duplicate step registration";
    private static final String MSG_NOT_FOUND   = "Flow registry: step not found";
    private static final String REASON_DUPLICATE = "DUPLICATE_STEP";
    private static final String REASON_NOT_FOUND = "STEP_NOT_FOUND";

    /**
     * Creates an {@code EX-FLOW-7004} registry failure that carries a message and no glass-box
     * context — {@code rawArgs} is empty, so no consumer can read the step id or reason off it.
     *
     * @param message stable failure description; never a formatted string, per the Glass-Box
     *                zero-allocation contract
     * @apiNote Use {@link #duplicateStep} or {@link #stepNotFound} wherever the step id is known.
     */
    public FlowRegistryException(String message) {
        super(KernelErrorCodes.EX_FLOW_7004, message, (Throwable) null);
    }

    private FlowRegistryException(String errorCode, String message, Throwable cause, Object... rawArgs) {
        super(errorCode, message, cause, rawArgs);
    }

    /**
     * Creates the refusal for a second registration under a step id the registry already holds —
     * accepting it would silently rebind an id the execution path resolves by direct index.
     *
     * <p>rawArgs layout: {@code [stepId, "DUPLICATE_STEP"]}.
     *
     * @param stepId the step identifier that was already registered
     * @return an {@code EX-FLOW-7004} exception with {@code staticReasonCode="DUPLICATE_STEP"}
     */
    public static FlowRegistryException duplicateStep(int stepId) {
        return new FlowRegistryException(KernelErrorCodes.EX_FLOW_7004, MSG_DUPLICATE, null,
                stepId, REASON_DUPLICATE);
    }

    /**
     * Creates the refusal for a lookup of a step id nothing was registered under.
     *
     * <p>rawArgs layout: {@code [stepId, "STEP_NOT_FOUND"]}.
     *
     * @param stepId the step identifier that resolved to nothing
     * @return an {@code EX-FLOW-7004} exception with {@code staticReasonCode="STEP_NOT_FOUND"}
     */
    public static FlowRegistryException stepNotFound(int stepId) {
        return new FlowRegistryException(KernelErrorCodes.EX_FLOW_7004, MSG_NOT_FOUND, null,
                stepId, REASON_NOT_FOUND);
    }
}

