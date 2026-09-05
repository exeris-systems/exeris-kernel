/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.websocket;

/**
 * SPI: a bound duplex endpoint.
 *
 * <p>Deliberately the same lifecycle as {@code HttpServerEngine} — construct, {@code setHandler},
 * {@code start} — because that shape is what lets a consumer obtain an endpoint <strong>without
 * booting the kernel</strong> (ADR-084 §1). A language server starts per editing session; requiring
 * a full runtime to open a socket would make the tool pay for something it does not use.
 *
 * <p><b>Ownership:</b> the engine owns the bound port and the connections it accepts once
 * {@link #start()} returns; {@link #close()} releases them, stopping a running engine first.
 *
 * @since 0.12
 */
public interface WebSocketServerEngine extends AutoCloseable {

    /**
     * Sets the handler invoked once per accepted connection. Must be called before {@link #start()}.
     *
     * @param handler the connection handler; must not be null
     */
    void setHandler(WebSocketHandler handler);

    /**
     * Sets the handshake decision callback. Optional — when unset, the configured origin allowlist
     * decides alone, and refuses an origin it was not given.
     *
     * @param handshakeHandler the callback, or {@code null} to clear a previously set one
     */
    void setHandshakeHandler(WebSocketHandshakeHandler handshakeHandler);

    /**
     * Binds and begins accepting.
     *
     * @throws IllegalStateException if no handler has been set, or the engine is already started
     * @apiNote Potentially blocking (socket bind); do not call it from a virtual thread that is
     *          expected never to park.
     */
    void start();

    /**
     * Stops accepting and closes live connections, ideally with
     * {@link WebSocketCloseCode#GOING_AWAY} so a peer can tell an orderly shutdown from a crash.
     *
     * @implSpec Refuse new connections immediately; connections already accepted are drained
     *           and closed at the transport level before returning.
     * @implNote The Community binding closes accepted connections through its underlying
     *           {@code TransportEngine}, which is protocol-blind and does not send an RFC 6455
     *           close frame — so {@link WebSocketCloseCode#GOING_AWAY} is not yet observed on
     *           the wire for connections live at {@code stop()} time. Sending it there is
     *           tracked as a gap in the 0.12 Community driver, not guaranteed by this contract.
     */
    void stop();

    /**
     * The port actually bound, which is what a caller that asked for an ephemeral one needs.
     *
     * @return the bound port
     * @throws IllegalStateException if the engine has not been started
     */
    int boundPort();

    @Override
    void close();
}
