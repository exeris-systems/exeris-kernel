/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.http1;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.FaultOrigin;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Unchecked exception for HTTP/1.1 protocol parse violations (DoS limits, malformed framing).
 *
 * <p>Carries error code {@link KernelErrorCodes#EX_HTTP_4004}.
 *
 * <h2>rawArgs Binary Layout (Enterprise Glass-Box)</h2>
 * <p>Carries domain detail for {@link KernelErrorCodes#EX_HTTP_4004}:
 * <ul>
 *   <li><b>Empty (0 elements):</b> structural framing violations without a numeric argument
 *       (e.g., missing status-line or header terminators, empty Content-Length value, ambiguous framing).</li>
 *   <li><b>Single value:</b>
 *     <ul>
 *       <li>{@code [0] long statusCode} — status code out of protocol bounds (100–599)</li>
 *       <li>{@code [0] long versionLength} — unsupported or invalid protocol version length</li>
 *       <li>{@code [0] long offendingByte} — invalid ASCII character byte in numeric field</li>
 *       <li>{@code [0] long overflowAccumulator} — numeric value prior to integer overflow</li>
 *       <li>{@code [0] long fieldSize} — size of a malformed header line missing colon or empty header name</li>
 *       <li>{@code [0] String rawName} — rejected invalid header field name</li>
 *     </ul>
 *   </li>
 *   <li><b>Pair {@code [long, long]}:</b>
 *     <ul>
 *       <li>{@code [0] long firstLength, [1] long conflictingLength} — conflicting Content-Length headers</li>
 *       <li>{@code [0] long declaredLength, [1] long availableBytes} — truncated body or unconsumed trailing bytes</li>
 *       <li>{@code [0] long fieldSize, [1] long maxHeaderSize} — single header field size limit breach</li>
 *       <li>{@code [0] long headerCount, [1] long maxHeaders} — header count limit breach</li>
 *     </ul>
 *   </li>
 *   <li><b>Triple {@code [long, long, long]}:</b>
 *     <ul>
 *       <li>{@code [0] long offset, [1] long requestedEnd, [2] long bufferSize} — buffer range limit breach</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * @since 0.12
 */
public class Http1ParseException extends ExerisKernelException {

    private static final long serialVersionUID = 1L;

    private static final String ERROR_CODE = KernelErrorCodes.EX_HTTP_4004;

    private final FaultOrigin faultOrigin;

    /**
     * Constructs the exception with {@code EX-HTTP-4004}, defaulting to {@link FaultOrigin#SYSTEM}
     * per ADR-083 (the conservative default direction), and no chained cause.
     *
     * @param messageTemplate static message template describing the violation
     * @param rawArgs         domain-specific detail carried as-is for telemetry, never used to
     *                        build {@code messageTemplate}
     */
    public Http1ParseException(String messageTemplate, Object... rawArgs) {
        this(FaultOrigin.SYSTEM, messageTemplate, rawArgs);
    }

    /**
     * Constructs the exception with {@code EX-HTTP-4004}, defaulting to {@link FaultOrigin#SYSTEM}
     * per ADR-083 (the conservative default direction), chaining {@code cause}.
     *
     * @param messageTemplate static message template describing the violation
     * @param cause           the exception that caused the parse failure
     * @param rawArgs         domain-specific detail carried as-is for telemetry, never used to
     *                        build {@code messageTemplate}
     */
    public Http1ParseException(String messageTemplate, Throwable cause, Object... rawArgs) {
        this(FaultOrigin.SYSTEM, messageTemplate, cause, rawArgs);
    }

    /**
     * Constructs the exception with {@code EX-HTTP-4004}, an explicit {@link FaultOrigin},
     * and no chained cause.
     *
     * @param faultOrigin     the fault origin (e.g. CALLER for inbound requests, SYSTEM for outbound client responses)
     * @param messageTemplate static message template describing the violation
     * @param rawArgs         domain-specific detail carried as-is for telemetry, never used to
     *                        build {@code messageTemplate}
     */
    public Http1ParseException(FaultOrigin faultOrigin, String messageTemplate, Object... rawArgs) {
        super(ERROR_CODE, messageTemplate, rawArgs);
        this.faultOrigin = java.util.Objects.requireNonNull(faultOrigin, "faultOrigin must not be null");
    }

    /**
     * Constructs the exception with {@code EX-HTTP-4004}, an explicit {@link FaultOrigin},
     * chaining {@code cause}.
     *
     * @param faultOrigin     the fault origin (e.g. CALLER for inbound requests, SYSTEM for outbound client responses)
     * @param messageTemplate static message template describing the violation
     * @param cause           the exception that caused the parse failure
     * @param rawArgs         domain-specific detail carried as-is for telemetry, never used to
     *                        build {@code messageTemplate}
     */
    public Http1ParseException(FaultOrigin faultOrigin, String messageTemplate, Throwable cause, Object... rawArgs) {
        super(ERROR_CODE, messageTemplate, cause, rawArgs);
        this.faultOrigin = java.util.Objects.requireNonNull(faultOrigin, "faultOrigin must not be null");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns {@link FaultOrigin#CALLER} for inbound request parse violations (the client sent
     * malformed framing) or {@link FaultOrigin#SYSTEM} for outbound client response parse violations
     * (the upstream server dependency returned malformed framing, per ADR-083).
     */
    @Override
    public FaultOrigin faultOrigin() {
        return faultOrigin;
    }
}
