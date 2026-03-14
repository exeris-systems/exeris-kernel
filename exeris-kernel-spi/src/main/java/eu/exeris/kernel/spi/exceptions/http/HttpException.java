/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.exceptions.http;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Thrown when an HTTP SPI-level operation fails (provider bootstrap, engine start,
 * client connection, or handler execution).
 *
 * <h2>Zero-Allocation Contract (The Wall)</h2>
 * <p>Follows the {@link ExerisKernelException} Glass-Box pattern: domain context
 * is stored in {@link #rawArgs()} as typed primitives, never as formatted strings.
 * Factory methods guarantee the correct {@code EX-HTTP-*} error code.
 *
 * <h2>Error Code Variants</h2>
 * <ul>
 *   <li>{@link #providerBootstrapFailure(String, Throwable)}   → {@value KernelErrorCodes#EX_HTTP_4007}</li>
 *   <li>{@link #serverStartFailure(String, int, Throwable)}    → {@value KernelErrorCodes#EX_HTTP_4008}</li>
 *   <li>{@link #clientConnectFailure(String, String, int, Throwable)} → {@value KernelErrorCodes#EX_HTTP_4009}</li>
 * </ul>
 *
 * <h2>rawArgs Binary Layout</h2>
 * <pre>
 * EX-HTTP-4007 (provider bootstrap failure):
 *   index 0 → String providerName
 *
 * EX-HTTP-4008 (server start failure):
 *   index 0 → String providerName
 *   index 1 → int    port (-1 if not applicable)
 *
 * EX-HTTP-4009 (client connection failure):
 *   index 0 → String providerName
 *   index 1 → String host
 *   index 2 → int    port (-1 if not applicable)
 * </pre>
 *
 * @since 0.5.0
 */
public final class HttpException extends ExerisKernelException {

    private static final String MSG_PROVIDER_BOOTSTRAP = "HttpProvider bootstrap failure";
    private static final String MSG_SERVER_START        = "HttpServerEngine start failure";
    private static final String MSG_CLIENT_CONNECT      = "HttpClientEngine connection failure";

    /**
     * General-purpose constructor — used when no specific factory method matches the scenario.
     *
     * @param errorCode {@code EX-HTTP-*} code; must not be {@code null}
     * @param message   static message template; no runtime formatting
     * @param cause     optional chained throwable; may be {@code null}
     * @param rawArgs   raw domain arguments for Glass-Box telemetry
     */
    public HttpException(String errorCode, String message, Throwable cause, Object... rawArgs) {
        super(errorCode, message, cause, rawArgs);
    }

    /**
     * No-cause overload.
     *
     * @param errorCode {@code EX-HTTP-*} code
     * @param message   static message template
     * @param rawArgs   raw domain arguments
     */
    public HttpException(String errorCode, String message, Object... rawArgs) {
        super(errorCode, message, rawArgs);
    }

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    /**
     * Creates an {@code HttpException} for an {@link eu.exeris.kernel.spi.http.HttpProvider}
     * that could not be bootstrapped (no provider on classpath, or initialisation failure).
     *
     * @param providerName programmatic name of the failing provider (or {@code "unknown"})
     * @param cause        optional chained cause
     * @return a new exception carrying {@value KernelErrorCodes#EX_HTTP_4007}
     */
    public static HttpException providerBootstrapFailure(String providerName, Throwable cause) {
        return new HttpException(KernelErrorCodes.EX_HTTP_4007, MSG_PROVIDER_BOOTSTRAP, cause, providerName);
    }

    /**
     * Creates an {@code HttpException} for an {@link eu.exeris.kernel.spi.http.HttpServerEngine}
     * that could not be started (port in use, TLS context failure, etc.).
     *
     * @param providerName programmatic name of the provider that created this engine
     * @param port         port that could not be bound; {@code -1} if not applicable
     * @param cause        optional chained cause
     * @return a new exception carrying {@value KernelErrorCodes#EX_HTTP_4008}
     */
    public static HttpException serverStartFailure(String providerName, int port, Throwable cause) {
        return new HttpException(KernelErrorCodes.EX_HTTP_4008, MSG_SERVER_START, cause, providerName, port);
    }

    /**
     * Creates an {@code HttpException} for an {@link eu.exeris.kernel.spi.http.HttpClientEngine}
     * that could not connect to a remote host.
     *
     * @param providerName programmatic name of the provider that created this engine
     * @param host         target host that could not be reached
     * @param port         target port; {@code -1} if not applicable
     * @param cause        optional chained cause
     * @return a new exception carrying {@value KernelErrorCodes#EX_HTTP_4009}
     */
    public static HttpException clientConnectFailure(String providerName, String host, int port, Throwable cause) {
        return new HttpException(KernelErrorCodes.EX_HTTP_4009, MSG_CLIENT_CONNECT, cause, providerName, host, port);
    }
}
