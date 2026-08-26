/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.exceptions.events;

import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventProvider;
import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Thrown when an {@link EventProvider} cannot create the {@link EventEngine}
 * from the given configuration.
 *
 * <h2>Hierarchy &amp; java:S110</h2>
 * <p>Extends {@link ExerisKernelException} directly — one level below
 * {@code RuntimeException} in the Kernel tree — to stay within the
 * {@code java:S110} inheritance-depth limit of 5:
 * {@code Object → Throwable → Exception → RuntimeException →
 * ExerisKernelException → EventProviderException}.
 *
 * <h2>rawArgs Binary Layout (Enterprise Glass-Box)</h2>
 * <p>For {@value KernelErrorCodes#EX_EVENT_6004}:
 * <ul>
 *   <li>index 0 – {@code String} providerName</li>
 *   <li>index 1 – {@code String} reason (static failure description)</li>
 * </ul>
 *
 * @since 0.5.0
 */
public class EventProviderException extends ExerisKernelException {

    private static final String MSG_PROVIDER_FAILURE = "Event provider engine creation failure";

    /**
     * General-purpose constructor.
     *
     * @param message static message template
     */
    public EventProviderException(String message) {
        super(KernelErrorCodes.EX_EVENT_6004, message, (Throwable) null);
    }

    /**
     * General-purpose constructor with cause.
     *
     * @param message static message template
     * @param cause   upstream throwable; may be {@code null}
     */
    public EventProviderException(String message, Throwable cause) {
        super(KernelErrorCodes.EX_EVENT_6004, message, cause);
    }

    // Full-args constructor for factory methods — must precede static factory methods per DeclarationOrder
    private EventProviderException(String errorCode, String message, Throwable cause, Object... rawArgs) {
        super(errorCode, message, cause, rawArgs);
    }

    /**
     * Creates an {@code EventProviderException} for an engine creation failure.
     *
     * <p>Sets error code {@value KernelErrorCodes#EX_EVENT_6004}.
     * rawArgs layout: {@code [String providerName, String reason]}.
     *
     * @param providerName logical name of the provider (e.g. {@code "StandardEvents"})
     * @param reason       static failure description
     * @param cause        upstream throwable; may be {@code null}
     * @return a fully initialised {@link EventProviderException}
     */
    public static EventProviderException creationFailure(String providerName, String reason, Throwable cause) {
        return new EventProviderException(KernelErrorCodes.EX_EVENT_6004, MSG_PROVIDER_FAILURE, cause,
                providerName, reason);
    }
}
