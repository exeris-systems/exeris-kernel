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
 * Thrown when a {@link eu.exeris.kernel.spi.flow.FlowProvider} cannot create the
 * {@link eu.exeris.kernel.spi.flow.FlowEngine} from the given configuration.
 *
 * <h2>Hierarchy &amp; java:S110</h2>
 * <pre>
 * Object → Throwable → Exception → RuntimeException →
 * ExerisKernelException → FlowProviderException
 * </pre>
 *
 * <h2>rawArgs Binary Layout — {@value KernelErrorCodes#EX_FLOW_7001}</h2>
 * <ul>
 *   <li>index 0 – {@code String} providerName</li>
 *   <li>index 1 – {@code String} reason (static failure description)</li>
 * </ul>
 *
 * @since 0.5.0
 */
public final class FlowProviderException extends ExerisKernelException {

    private static final String MSG_CREATION_FAILURE = "Flow provider engine creation failure";

    public FlowProviderException(String message) {
        super(KernelErrorCodes.EX_FLOW_7001, message, (Throwable) null);
    }

    public FlowProviderException(String message, Throwable cause) {
        super(KernelErrorCodes.EX_FLOW_7001, message, cause);
    }

    private FlowProviderException(String errorCode, String message, Throwable cause, Object... rawArgs) {
        super(errorCode, message, cause, rawArgs);
    }

    /**
     * Creates a {@code FlowProviderException} for an engine creation failure.
     *
     * <p>rawArgs layout: {@code [String providerName, String reason]}.
     *
     * @param providerName logical name of the provider (e.g. {@code "ExerisEnterprise/SlabFlow"})
     * @param reason       static failure description
     * @param cause        upstream throwable; may be {@code null}
     * @return a fully initialised {@link FlowProviderException}
     */
    public static FlowProviderException creationFailure(String providerName, String reason, Throwable cause) {
        return new FlowProviderException(KernelErrorCodes.EX_FLOW_7001, MSG_CREATION_FAILURE, cause,
                providerName, reason);
    }
}

