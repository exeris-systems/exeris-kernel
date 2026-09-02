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
 * @since 0.12.0
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
