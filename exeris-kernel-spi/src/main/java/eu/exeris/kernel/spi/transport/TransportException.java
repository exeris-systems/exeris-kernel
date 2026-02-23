/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.transport;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Thrown when a transport-level operation fails (bind, start, send, receive, shutdown).
 *
 * <h2>Zero-Allocation Contract (The Wall)</h2>
 * <p>The legacy implementation was a thin checked {@code Exception} wrapper with no
 * structured context. This version is a full {@link ExerisKernelException} that:
 * <ul>
 *   <li>carries a typed {@code EX-NET-*} error code</li>
 *   <li>stores transport name and port (or byte count) in {@link #rawArgs()} for
 *       binary Black-Box serialization</li>
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
 *   <li>{@link #bindFailure(String, int, Throwable)} → {@value KernelErrorCodes#EX_NET_4001}</li>
 *   <li>{@link #sendFailure(String, long, Throwable)} → {@value KernelErrorCodes#EX_NET_4002}</li>
 *   <li>{@link #receiveTimeout(String, long)} → {@value KernelErrorCodes#EX_NET_4003}</li>
 * </ul>
 * <p>Direct construction via the general constructor is also permitted when the above
 * factory methods do not cover the specific operation.
 *
 * <h2>rawArgs Binary Layout (Enterprise Black-Box)</h2>
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
 * </pre>
 *
 * @since 0.5.0
 */
public final class TransportException extends ExerisKernelException {

    // -----------------------------------------------------------------------
    // Static message templates – JVM constants, never re-allocated
    // -----------------------------------------------------------------------

    private static final String MSG_BIND    = "Transport bind failure";
    private static final String MSG_SEND    = "Transport send failure";
    private static final String MSG_TIMEOUT = "Transport receive timeout";

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
        return new TransportException(
                KernelErrorCodes.EX_NET_4003,
                MSG_TIMEOUT,
                null,
                transportName, timeoutMs);
    }

    // -----------------------------------------------------------------------
    // Typed accessors – read rawArgs with explicit semantics
    // (Community telemetry may call these; Enterprise reads rawArgs() directly)
    // -----------------------------------------------------------------------

    /**
     * Returns the logical name of the transport driver that raised the exception.
     *
     * <p>Convenience accessor over {@code (String) rawArgs()[0]}.
     * Defined on all {@code EX-NET-*} variants.
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
     * </ul>
     *
     * @return numeric context value; interpretation is code-dependent
     * @throws IllegalStateException if {@code rawArgs} has fewer than 2 elements or is not a number
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


