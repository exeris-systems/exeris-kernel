/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.websocket;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.FaultOrigin;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.websocket.WebSocketCloseCode;

/**
 * Core: the peer broke the protocol, and the close code says how.
 *
 * <p>Deliberately a Core type rather than an SPI one. It never reaches a handler — the engine
 * catches it and closes the connection with {@link #closeCode()} — so putting it on the SPI would
 * publish a type nothing outside the codec can throw or usefully catch.
 *
 * <p>An {@link ExerisKernelException} nonetheless, like every other wire-format violation in Core
 * ({@code Http1ParseException}, {@code HpackDecodingException}, {@code ContinuationViolationException}).
 * Core placement decides who can catch it; it does not exempt it from the error-code registry or
 * from {@code ExceptionDisclosure}, which only envelopes kernel exceptions — a plain
 * {@code RuntimeException} would surface its detail verbatim in {@code PROD}.
 *
 * <p>Carries no payload content. A frame that violated the protocol is exactly the input most likely
 * to be hostile, and the diagnostic value is in <em>which rule</em> broke, not in the bytes.
 */
public final class WebSocketProtocolException extends ExerisKernelException {

    private static final long serialVersionUID = 1L;

    private static final String ERROR_CODE = KernelErrorCodes.EX_HTTP_4015;

    private final WebSocketCloseCode closeCode;

    /**
     * @param closeCode the code to close the connection with; must not be null
     * @param message   a static description of the rule that was broken
     */
    public WebSocketProtocolException(WebSocketCloseCode closeCode, String message) {
        this(closeCode, message, null);
    }

    /**
     * @param closeCode the code to close the connection with; must not be null
     * @param message   a static description of the rule that was broken
     * @param cause     the underlying failure. Only causes that carry no payload content may be
     *                  passed — a decoder failure reports a length, which is safe; anything quoting
     *                  the offending bytes is not
     */
    public WebSocketProtocolException(WebSocketCloseCode closeCode, String message,
                                      Throwable cause) {
        super(ERROR_CODE, message, cause, closeCode.code());
        this.closeCode = closeCode;
    }

    /**
     * @return the close code this violation maps to
     */
    public WebSocketCloseCode closeCode() {
        return closeCode;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always {@link FaultOrigin#CALLER}: every case this is thrown for is something the peer put
     * on the wire, so an operator should not be paged for a malformed client.
     */
    @Override
    public FaultOrigin faultOrigin() {
        return FaultOrigin.CALLER;
    }
}
