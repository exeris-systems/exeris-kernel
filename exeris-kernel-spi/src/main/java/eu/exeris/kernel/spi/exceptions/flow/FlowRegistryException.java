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
 * Thrown when a {@link eu.exeris.kernel.spi.flow.FlowRegistry} operation fails
 * (duplicate registration, unknown step lookup, etc.).
 *
 * <h2>rawArgs Binary Layout — {@value KernelErrorCodes#EX_FLOW_7004}</h2>
 * <ul>
 *   <li>index 0 – {@code int}    stepId</li>
 *   <li>index 1 – {@code String} reason</li>
 * </ul>
 *
 * @since 0.5.0
 */
public final class FlowRegistryException extends ExerisKernelException {

    private static final String MSG_DUPLICATE  = "Flow registry: duplicate step registration";
    private static final String MSG_NOT_FOUND  = "Flow registry: step not found";

    public FlowRegistryException(String message) {
        super(KernelErrorCodes.EX_FLOW_7004, message, (Throwable) null);
    }

    private FlowRegistryException(String errorCode, String message, Throwable cause, Object... rawArgs) {
        super(errorCode, message, cause, rawArgs);
    }

    public static FlowRegistryException duplicateStep(int stepId) {
        return new FlowRegistryException(KernelErrorCodes.EX_FLOW_7004, MSG_DUPLICATE, null,
                stepId, "Step id already registered: " + stepId);
    }

    public static FlowRegistryException stepNotFound(int stepId) {
        return new FlowRegistryException(KernelErrorCodes.EX_FLOW_7004, MSG_NOT_FOUND, null,
                stepId, "No step registered with id: " + stepId);
    }
}

