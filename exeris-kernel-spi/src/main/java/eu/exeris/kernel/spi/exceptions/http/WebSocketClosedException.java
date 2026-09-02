/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.exceptions.http;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Thrown when a WebSocket send is attempted on a connection that is no longer writable.
 *
 * <p>The duplex sibling of {@link StreamClosedException}, and unchecked for the same reason: a peer
 * going away is not a condition every send site should be forced to declare, and a handler that
 * wants to react to it can.
 *
 * <p>Only the <em>send</em> direction raises. A closed connection reached from the receive side is
 * the ordinary end of a session and surfaces as {@code null}, so a handler falls out of its loop
 * rather than catching its way out of one.
 *
 * <h2>rawArgs Binary Layout ({@code EX-HTTP-4014})</h2>
 * <ul>
 *   <li>index 0 – {@code long} connectionAgeMillis</li>
 *   <li>index 1 – {@code long} messagesSent</li>
 *   <li>index 2 – {@code int}  closeCode, 0 when the peer sent none</li>
 * </ul>
 *
 * <p>Message content never appears — not in the message, not in {@code rawArgs}. The text a handler
 * was trying to send is exactly the payload most likely to be sensitive.
 *
 * @since 0.12.0
 */
public final class WebSocketClosedException extends ExerisKernelException {

    private static final long serialVersionUID = 1L;

    private static final String MSG_PEER_GONE = "WebSocket send on a closed connection";

    /**
     * @param errorCode structured error code
     * @param message   static message; never formatted from connection content
     * @param rawArgs   connection-scoped counters for Glass-Box telemetry
     */
    public WebSocketClosedException(String errorCode, String message, Object... rawArgs) {
        super(errorCode, message, rawArgs);
    }

    /**
     * The connection is no longer writable.
     *
     * @param connectionAgeMillis how long the connection had been open
     * @param messagesSent        messages successfully sent before this attempt
     * @param closeCode           the RFC 6455 close code observed, or 0 when none was seen
     * @return the exception; never {@code null}
     */
    public static WebSocketClosedException notWritable(long connectionAgeMillis,
                                                       long messagesSent,
                                                       int closeCode) {
        return new WebSocketClosedException(KernelErrorCodes.EX_HTTP_4014, MSG_PEER_GONE,
                connectionAgeMillis, messagesSent, closeCode);
    }
}
