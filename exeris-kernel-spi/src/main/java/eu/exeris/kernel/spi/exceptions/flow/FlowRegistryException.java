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

    public FlowRegistryException(String message) {
        super(KernelErrorCodes.EX_FLOW_7004, message, (Throwable) null);
    }

    private FlowRegistryException(String errorCode, String message, Throwable cause, Object... rawArgs) {
        super(errorCode, message, cause, rawArgs);
    }

    public static FlowRegistryException duplicateStep(int stepId) {
        return new FlowRegistryException(KernelErrorCodes.EX_FLOW_7004, MSG_DUPLICATE, null,
                stepId, REASON_DUPLICATE);
    }

    public static FlowRegistryException stepNotFound(int stepId) {
        return new FlowRegistryException(KernelErrorCodes.EX_FLOW_7004, MSG_NOT_FOUND, null,
                stepId, REASON_NOT_FOUND);
    }
}

