/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.http1;

import eu.exeris.kernel.spi.exceptions.FaultOrigin;

/**
 * An HTTP/1.1 parse violation on the inbound path: a request this server read from a remote client
 * and could not frame — a DoS limit exceeded, or malformed framing.
 *
 * <p>It exists to fix the fault origin at the type. Every constructor here passes
 * {@link FaultOrigin#CALLER}, so an inbound framing violation cannot be raised as anything else,
 * and a throw site added later cannot pick the wrong origin by omission. Its supertype
 * {@link Http1ParseException} defaults to {@link FaultOrigin#SYSTEM}, which is the right answer on
 * the outbound path — a response this client read from an upstream it depends on. The two are the
 * same failure seen from opposite ends of a connection, and ADR-083 is what makes the difference
 * worth a type rather than a convention.
 *
 * @since 0.12
 */
public final class Http1RequestParseException extends Http1ParseException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs the exception with {@code EX-HTTP-4004} and no chained cause.
     *
     * @param messageTemplate static message template describing the violation
     * @param rawArgs         domain-specific detail carried as-is for telemetry
     */
    public Http1RequestParseException(String messageTemplate, Object... rawArgs) {
        super(FaultOrigin.CALLER, messageTemplate, rawArgs);
    }

    /**
     * Constructs the exception with {@code EX-HTTP-4004}, chaining {@code cause}.
     *
     * @param messageTemplate static message template describing the violation
     * @param cause           the exception that caused the parse failure
     * @param rawArgs         domain-specific detail carried as-is for telemetry
     */
    public Http1RequestParseException(String messageTemplate, Throwable cause, Object... rawArgs) {
        super(FaultOrigin.CALLER, messageTemplate, cause, rawArgs);
    }
}
