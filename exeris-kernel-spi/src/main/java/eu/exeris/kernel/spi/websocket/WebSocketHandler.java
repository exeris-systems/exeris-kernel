/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.websocket;

/**
 * SPI: application logic for one accepted connection.
 *
 * <p>Invoked once per connection on its own virtual thread, and the connection lives as long as the
 * call does: returning from {@code handle} closes it normally. A handler that wants to serve until
 * the peer goes away loops on {@link WebSocketExchange#receive()} until it returns {@code null}.
 *
 * <p><b>Thread confinement:</b> owner thread — each invocation runs on its own virtual thread, one
 * per connection, and the exchange it is given belongs to that thread alone.
 * <p><b>Ownership:</b> the handler owns nothing it is handed; the engine owns the connection and
 * reclaims it when the handler returns or when {@link WebSocketExchange#close()} is called.
 *
 * @implSpec Returning normally from {@link #handle} closes the connection the way
 *           {@link WebSocketExchange#close()} does — {@link WebSocketCloseCode#NORMAL_CLOSURE},
 *           no reason.
 * @since 0.12
 */
@FunctionalInterface
public interface WebSocketHandler {

    /**
     * Serves one connection.
     *
     * @param exchange the connection; never {@code null}
     */
    void handle(WebSocketExchange exchange);
}
