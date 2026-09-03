/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.exceptions.events;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Root exception for all event engine failures.
 *
 * <h2>Zero-Allocation Telemetry Contract</h2>
 * <p>Extends {@link ExerisKernelException} — carries a structured {@code EX-EVENT-*}
 * error code and stores domain arguments in {@link #rawArgs()} for binary Glass-Box
 * serialization. No {@code String} formatting occurs on the hot-path.
 *
 * <h2>Error Code Variants</h2>
 * <ul>
 *   <li>Generic failure     → {@value KernelErrorCodes#EX_EVENT_6001}</li>
 *   <li>Bus publish failure → {@value KernelErrorCodes#EX_EVENT_6002} (see {@link EventBusException})</li>
 *   <li>Registry conflict   → {@value KernelErrorCodes#EX_EVENT_6003} (see {@link EventRegistryException})</li>
 *   <li>Provider failure    → {@value KernelErrorCodes#EX_EVENT_6004} (see {@link EventProviderException})</li>
 * </ul>
 *
 * @since 0.5.0
 */
public class EventEngineException extends ExerisKernelException {

    private static final String DEFAULT_EVENT_ENGINE_ERROR_CODE = KernelErrorCodes.EX_EVENT_6001;

    /**
     * Constructs a generic event engine exception with the default error code.
     *
     * @param message static message template — no runtime formatting
     */
    public EventEngineException(String message) {
        super(DEFAULT_EVENT_ENGINE_ERROR_CODE, message, (Throwable) null);
    }

    /**
     * Constructs a generic event engine exception with cause.
     *
     * @param message static message template — no runtime formatting
     * @param cause   upstream throwable; may be {@code null}
     */
    public EventEngineException(String message, Throwable cause) {
        super(DEFAULT_EVENT_ENGINE_ERROR_CODE, message, cause);
    }

    /**
     * Constructs an event engine exception with a specific error code and raw domain args.
     *
     * <p>Subclasses should use this constructor when they carry domain-specific telemetry data.
     *
     * @param errorCode an {@code EX-EVENT-*} code from {@link KernelErrorCodes}
     * @param message   static message template — no runtime formatting
     * @param rawArgs   raw domain arguments for binary Glass-Box serialization
     */
    public EventEngineException(String errorCode, String message, Object... rawArgs) {
        super(errorCode, message, null, rawArgs);
    }

    /**
     * Constructs an event engine exception with a specific error code, cause, and raw domain args.
     *
     * @param errorCode an {@code EX-EVENT-*} code from {@link KernelErrorCodes}
     * @param message   static message template — no runtime formatting
     * @param cause     upstream throwable; may be {@code null}
     * @param rawArgs   raw domain arguments for binary Glass-Box serialization
     */
    public EventEngineException(String errorCode, String message, Throwable cause, Object... rawArgs) {
        super(errorCode, message, cause, rawArgs);
    }
}
