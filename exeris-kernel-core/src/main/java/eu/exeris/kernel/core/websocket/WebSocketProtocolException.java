/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.websocket;

import eu.exeris.kernel.spi.websocket.WebSocketCloseCode;

/**
 * Core: the peer broke the protocol, and the close code says how.
 *
 * <p>Deliberately a Core type rather than an SPI one. It never reaches a handler — the engine
 * catches it and closes the connection with {@link #closeCode()} — so putting it on the SPI would
 * publish a type nothing outside the codec can throw or usefully catch.
 *
 * <p>Carries no payload content. A frame that violated the protocol is exactly the input most likely
 * to be hostile, and the diagnostic value is in <em>which rule</em> broke, not in the bytes.
 */
public final class WebSocketProtocolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient WebSocketCloseCode closeCode;

    /**
     * @param closeCode the code to close the connection with; must not be null
     * @param message   a static description of the rule that was broken
     */
    public WebSocketProtocolException(WebSocketCloseCode closeCode, String message) {
        super(message);
        this.closeCode = closeCode;
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
        super(message, cause);
        this.closeCode = closeCode;
    }

    /**
     * @return the close code this violation maps to
     */
    public WebSocketCloseCode closeCode() {
        return closeCode;
    }
}
