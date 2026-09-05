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
 * @since 0.5
 */
public class EventEngineException extends ExerisKernelException {

    private static final String DEFAULT_EVENT_ENGINE_ERROR_CODE = KernelErrorCodes.EX_EVENT_6001;

    /**
     * Constructs a generic engine failure — the fallback shape for a condition none of the
     * specialised subclasses covers.
     *
     * @param message static message template — no runtime formatting
     * @apiNote Sets {@value KernelErrorCodes#EX_EVENT_6001} and leaves {@code rawArgs} empty. Use
     *          the error-code constructors when the failure has structured detail worth carrying.
     */
    public EventEngineException(String message) {
        super(DEFAULT_EVENT_ENGINE_ERROR_CODE, message, (Throwable) null);
    }

    /**
     * Constructs a generic engine failure that carries an upstream cause.
     *
     * @param message static message template — no runtime formatting
     * @param cause   upstream throwable; may be {@code null}
     * @apiNote Sets {@value KernelErrorCodes#EX_EVENT_6001} and leaves {@code rawArgs} empty.
     */
    public EventEngineException(String message, Throwable cause) {
        super(DEFAULT_EVENT_ENGINE_ERROR_CODE, message, cause);
    }

    /**
     * Constructs an engine failure under a specific error code, carrying the domain values a
     * Glass-Box decoder needs to reconstruct what happened.
     *
     * @param errorCode an {@code EX-EVENT-*} code from {@link KernelErrorCodes}
     * @param message   static message template — no runtime formatting
     * @param rawArgs   raw domain arguments for binary Glass-Box serialization, in the order the
     *                  code's documented layout specifies
     * @apiNote This is the constructor a subclass uses when it has telemetry data to carry.
     *          {@code rawArgs} reach a decoder verbatim, so keep secrets out of them.
     */
    public EventEngineException(String errorCode, String message, Object... rawArgs) {
        super(errorCode, message, null, rawArgs);
    }

    /**
     * Constructs an engine failure under a specific error code, carrying both an upstream cause
     * and the domain values a Glass-Box decoder needs.
     *
     * @param errorCode an {@code EX-EVENT-*} code from {@link KernelErrorCodes}
     * @param message   static message template — no runtime formatting
     * @param cause     upstream throwable; may be {@code null}
     * @param rawArgs   raw domain arguments for binary Glass-Box serialization, in the order the
     *                  code's documented layout specifies
     */
    public EventEngineException(String errorCode, String message, Throwable cause, Object... rawArgs) {
        super(errorCode, message, cause, rawArgs);
    }
}
