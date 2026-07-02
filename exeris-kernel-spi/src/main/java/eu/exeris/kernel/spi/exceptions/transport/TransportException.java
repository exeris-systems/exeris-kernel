/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.exceptions.transport;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

import java.util.Objects;

/**
 * Thrown when a transport-level operation fails (bind, start, send, receive, shutdown).
 *
 * <h2>Zero-Allocation Contract (The Wall)</h2>
 * <p>The legacy implementation was a thin checked {@code Exception} wrapper with no
 * structured context. This version is a full {@link ExerisKernelException} that:
 * <ul>
 *   <li>carries a typed {@code EX-NET-*} error code</li>
 *   <li>stores transport name and port (or byte count) in {@link #rawArgs()} for
 *       binary Glass-Box serialization</li>
 *   <li>extends {@code RuntimeException} – transport failures are fatal in kernel context</li>
 * </ul>
 *
 * <h2>Checked → Unchecked Migration</h2>
 * <p>The legacy class extended {@code Exception} (checked). Transport operations at
 * kernel level are unrecoverable in place – the caller is always a {@code StructuredTaskScope}
 * or a top-level bootstrap handler. Checked exceptions here only force boilerplate.
 *
 * <h2>Error Code Variants</h2>
 * <p>Use the appropriate factory method to ensure the correct {@code EX-NET-*} code is recorded:
 * <ul>
 *   <li>{@link #bindFailure(String, int, Throwable)}            → {@value KernelErrorCodes#EX_NET_4001}</li>
 *   <li>{@link #sendFailure(String, long, Throwable)}           → {@value KernelErrorCodes#EX_NET_4002}</li>
 *   <li>{@link #receiveTimeout(String, long)}                   → {@value KernelErrorCodes#EX_NET_4003}</li>
 *   <li>{@link #bootstrapFailure(String, String, Throwable)}    → {@value KernelErrorCodes#EX_NET_4004}</li>
 *   <li>{@link #engineStartFailure(String, int, Throwable)}     → {@value KernelErrorCodes#EX_NET_4005}</li>
 * </ul>
 * <p>Direct construction via the general constructor is also permitted when the above
 * factory methods do not cover the specific operation.
 *
 * <h2>rawArgs Binary Layout (Enterprise Glass-Box)</h2>
 * <p>Layout depends on the {@code EX-NET-*} code stored in {@link #errorCode()}:
 * <pre>
 * EX-NET-4001 (bind failure):
 *   index 0 → String transportName
 *   index 1 → int    port   (-1 if unknown)
 *
 * EX-NET-4002 (send failure):
 *   index 0 → String transportName
 *   index 1 → long   bytesSent
 *
 * EX-NET-4003 (receive timeout):
 *   index 0 → String transportName
 *   index 1 → long   timeoutMs
 *
 * EX-NET-4004 (bootstrap failure):
 *   index 0 → String transportName
 *   index 1 → String reason
 *
 * EX-NET-4005 (engine start failure):
 *   index 0 → String transportName
 *   index 1 → int    port   (-1 if not applicable)
 * </pre>
 *
 * @since 0.5.0
 */
public final class TransportException extends ExerisKernelException {

    // -----------------------------------------------------------------------
    // Static message templates – JVM constants, never re-allocated
    // -----------------------------------------------------------------------

    private static final String MSG_BIND = "Transport bind failure";
    private static final String MSG_SEND = "Transport send failure";
    private static final String MSG_TIMEOUT = "Transport receive timeout";
    private static final String MSG_BOOTSTRAP = "Transport engine bootstrap failure";
    private static final String MSG_START = "Transport engine start failure";
    private static final String MSG_STREAM_SHED = "Stream rejected by PAQS load-shedding";
    private static final String ERR_TRANSPORT_NAME_NULL = "transportName must not be null";

    // -----------------------------------------------------------------------
    // General constructor (used when no typed factory matches)
    // -----------------------------------------------------------------------

    /**
     * General-purpose constructor.
     *
     * <p>Prefer the typed factory methods ({@link #bindFailure}, {@link #sendFailure},
     * {@link #receiveTimeout}) when the failure category is known – they set the correct
     * {@code EX-NET-*} sub-code automatically.
     *
     * @param errorCode     an {@code EX-NET-*} code from {@link eu.exeris.kernel.spi.exceptions.KernelErrorCodes}
     * @param staticMessage a static, pre-defined message template – no runtime formatting
     * @param cause         optional upstream throwable; may be {@code null}
     * @param rawArgs       raw transport-domain arguments for binary telemetry
     */
    public TransportException(
            String errorCode,
            String staticMessage,
            Throwable cause,
            Object... rawArgs) {
        super(errorCode, staticMessage, cause, rawArgs);
    }

    // -----------------------------------------------------------------------
    // Typed factory methods – preferred entry points
    // -----------------------------------------------------------------------

    /**
     * Creates a {@code TransportException} for a socket/port bind failure.
     *
     * <p>Sets error code {@value KernelErrorCodes#EX_NET_4001}.
     * rawArgs layout: {@code [String transportName, int port]}.
     *
     * @param transportName logical name of the transport driver (e.g. {@code "Http3Transport"})
     * @param port          port number that could not be bound; use {@code -1} if unknown
     * @param cause         the upstream {@link java.io.IOException} or similar; may be {@code null}
     * @return a fully initialised {@link TransportException}
     */
    public static TransportException bindFailure(String transportName, int port, Throwable cause) {
        Objects.requireNonNull(transportName, ERR_TRANSPORT_NAME_NULL);
        return new TransportException(
                KernelErrorCodes.EX_NET_4001,
                MSG_BIND,
                cause,
                transportName, port);
    }

    /**
     * Creates a {@code TransportException} for a frame/datagram send failure.
     *
     * <p>Sets error code {@value KernelErrorCodes#EX_NET_4002}.
     * rawArgs layout: {@code [String transportName, long bytesSent]}.
     *
     * @param transportName logical name of the transport driver
     * @param bytesSent     number of bytes that were successfully sent before the failure
     * @param cause         the upstream throwable; may be {@code null}
     * @return a fully initialised {@link TransportException}
     */
    public static TransportException sendFailure(String transportName, long bytesSent, Throwable cause) {
        Objects.requireNonNull(transportName, ERR_TRANSPORT_NAME_NULL);
        return new TransportException(
                KernelErrorCodes.EX_NET_4002,
                MSG_SEND,
                cause,
                transportName, bytesSent);
    }

    /**
     * Creates a {@code TransportException} for a receive-timeout condition.
     *
     * <p>Sets error code {@value KernelErrorCodes#EX_NET_4003}.
     * rawArgs layout: {@code [String transportName, long timeoutMs]}.
     *
     * @param transportName logical name of the transport driver
     * @param timeoutMs     deadline in milliseconds that was exceeded
     * @return a fully initialised {@link TransportException}
     */
    public static TransportException receiveTimeout(String transportName, long timeoutMs) {
        Objects.requireNonNull(transportName, ERR_TRANSPORT_NAME_NULL);
        return new TransportException(
                KernelErrorCodes.EX_NET_4003,
                MSG_TIMEOUT,
                null,
                transportName, timeoutMs);
    }

    /**
     * Creates a {@code TransportException} for a transport engine bootstrap failure.
     *
     * <p>Sets error code {@value KernelErrorCodes#EX_NET_4004}.
     * rawArgs layout: {@code [String transportName, String reason]}.
     *
     * @param transportName name of the transport provider/engine that failed
     *                      (from {@code TransportProvider.providerName()})
     * @param reason        static failure description
     * @param cause         the upstream throwable; may be {@code null}
     * @return a fully initialised {@link TransportException}
     */
    public static TransportException bootstrapFailure(String transportName, String reason, Throwable cause) {
        Objects.requireNonNull(transportName, ERR_TRANSPORT_NAME_NULL);
        Objects.requireNonNull(reason, "reason must not be null");
        return new TransportException(
                KernelErrorCodes.EX_NET_4004,
                MSG_BOOTSTRAP,
                cause,
                transportName, reason);
    }

    /**
     * Creates a {@code TransportException} for a transport engine start failure.
     *
     * <p>Sets error code {@value KernelErrorCodes#EX_NET_4005}.
     * rawArgs layout: {@code [String transportName, int port]}.
     *
     * @param transportName name of the transport engine that failed to start
     * @param port          port that could not be bound; use {@code -1} if not applicable
     * @param cause         the upstream throwable; may be {@code null}
     * @return a fully initialised {@link TransportException}
     */
    public static TransportException engineStartFailure(String transportName, int port, Throwable cause) {
        Objects.requireNonNull(transportName, ERR_TRANSPORT_NAME_NULL);
        return new TransportException(
                KernelErrorCodes.EX_NET_4005,
                MSG_START,
                cause,
                transportName, port);
    }

    /**
     * Creates a {@code TransportException} for a stream rejected by PAQS load-shedding at the
     * transport edge — the single source of truth for load-shedding (ADR-043 reuses this for a
     * sheded SSE stream-open rather than minting a parallel HTTP shed code).
     *
     * <p>Sets error code {@value KernelErrorCodes#EX_NET_4006}.
     * rawArgs layout: {@code [String transportName, long streamId]}.
     *
     * @param transportName name of the transport engine that shed the stream
     * @param streamId      the rejected stream's identifier
     * @return a fully initialised {@link TransportException}
     */
    public static TransportException streamShed(String transportName, long streamId) {
        Objects.requireNonNull(transportName, ERR_TRANSPORT_NAME_NULL);
        return new TransportException(
                KernelErrorCodes.EX_NET_4006,
                MSG_STREAM_SHED,
                null,
                transportName, streamId);
    }

    // -----------------------------------------------------------------------
    // Typed accessors – read rawArgs with explicit semantics
    // (Community telemetry may call these; Enterprise reads rawArgs() directly)
    // -----------------------------------------------------------------------

    /**
     * Returns the logical name of the transport driver that raised the exception.
     *
     * <p>Convenience accessor over {@code (String) rawArgs()[0]}.
     * Defined on all {@code EX-NET-*} variants ({@value KernelErrorCodes#EX_NET_4001}–
     * {@value KernelErrorCodes#EX_NET_4005}). Always represents the transport
     * provider/engine name, regardless of which factory method was used.
     *
     * @return transport name; never {@code null}
     * @throws IllegalStateException if {@code rawArgs} is missing or empty — use factory methods to construct
     */
    public String transportName() {
        Object[] args = rawArgs();
        if (args == null || args.length < 1) {
            throw new IllegalStateException(
                    "transportName() requires rawArgs[0]; use factory methods to construct TransportException");
        }
        return (String) args[0];
    }

    /**
     * Returns the second raw argument cast to {@code long}.
     *
     * <p>Semantics depend on the error code:
     * <ul>
     * <li>{@value KernelErrorCodes#EX_NET_4001} – port number (cast from {@code int})</li>
     * <li>{@value KernelErrorCodes#EX_NET_4002} – bytes sent</li>
     * <li>{@value KernelErrorCodes#EX_NET_4003} – timeout in milliseconds</li>
     * <li>{@value KernelErrorCodes#EX_NET_4005} – port number (cast from {@code int}; -1 if not applicable)</li>
     * </ul>
     *
     * <p><b>Note:</b> {@value KernelErrorCodes#EX_NET_4004} stores a {@code String} reason in
     * {@code rawArgs()[1]}, not a number. Calling this accessor on a bootstrap-failure exception
     * will throw {@link IllegalStateException}. Use {@code (String) rawArgs()[1]} directly for
     * that variant.
     *
     * @return numeric context value; interpretation is code-dependent
     * @throws IllegalStateException if {@code rawArgs} has fewer than 2 elements, is not a number,
     *                               or the error code is {@value KernelErrorCodes#EX_NET_4004}
     */
    public long numericContext() {
        Object[] args = rawArgs();
        if (args == null || args.length < 2) {
            throw new IllegalStateException(
                    "numericContext() requires rawArgs[1]; use factory methods to construct TransportException");
        }

        Object arg = args[1];
        if (arg instanceof Number number) {
            return number.longValue();
        }

        throw new IllegalStateException(
                "numericContext() requires a numeric rawArgs[1] (int/long); got: " + arg.getClass().getName());
    }
}
