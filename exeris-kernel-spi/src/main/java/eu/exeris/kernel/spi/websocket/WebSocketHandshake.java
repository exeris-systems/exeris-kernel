/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.websocket;

import eu.exeris.kernel.spi.http.HttpStatus;

import java.util.Objects;
import java.util.Optional;

/**
 * SPI: what a handshake handler answers — accept, optionally naming a subprotocol, or refuse with a
 * status the client receives.
 *
 * <p>The refusal carries an {@link HttpStatus} because a WebSocket handshake is an HTTP GET and its
 * rejection is an HTTP response; the status comes from the set already in use rather than a parallel
 * one minted for this surface (ADR-084 §6).
 *
 * @since 0.12
 */
public final class WebSocketHandshake {

    private static final WebSocketHandshake ACCEPTED = new WebSocketHandshake(true, null, null);

    private final boolean accepted;
    private final String subprotocol;
    private final HttpStatus refusalStatus;

    private WebSocketHandshake(boolean accepted, String subprotocol, HttpStatus refusalStatus) {
        this.accepted = accepted;
        this.subprotocol = subprotocol;
        this.refusalStatus = refusalStatus;
    }

    /**
     * Accepts without negotiating a subprotocol.
     *
     * @return an accepting decision; never {@code null}
     */
    public static WebSocketHandshake accept() {
        return ACCEPTED;
    }

    /**
     * Accepts and names the negotiated subprotocol, which is echoed in
     * {@code Sec-WebSocket-Protocol} and reported on {@link WebSocketSession#subprotocol()}.
     *
     * @param subprotocol the agreed subprotocol; must not be null or blank
     * @return an accepting decision; never {@code null}
     */
    public static WebSocketHandshake accept(String subprotocol) {
        Objects.requireNonNull(subprotocol, "subprotocol must not be null");
        if (subprotocol.isBlank()) {
            throw new IllegalArgumentException("subprotocol must not be blank; use accept() instead");
        }
        return new WebSocketHandshake(true, subprotocol, null);
    }

    /**
     * Refuses the handshake. The client receives {@code status} and no connection is established.
     *
     * @param status the HTTP status to answer with; must not be null and must not be 2xx, since a
     *               success status would tell the client the handshake was accepted
     * @return a refusing decision; never {@code null}
     */
    public static WebSocketHandshake refuse(HttpStatus status) {
        Objects.requireNonNull(status, "status must not be null");
        if (status.code() >= 200 && status.code() < 300) {
            throw new IllegalArgumentException(
                    "a refusal must not carry a 2xx status: " + status.code());
        }
        return new WebSocketHandshake(false, null, status);
    }

    /**
     * Returns whether this decision accepts the connection.
     *
     * @return {@code true} for either {@link #accept()} or {@link #accept(String)};
     *         {@code false} for {@link #refuse(HttpStatus)}
     */
    public boolean accepted() {
        return accepted;
    }

    /**
     * Returns the subprotocol this decision negotiated, if any.
     *
     * @return the negotiated subprotocol, empty when none was agreed or the handshake was refused
     */
    public Optional<String> subprotocol() {
        return Optional.ofNullable(subprotocol);
    }

    /**
     * Returns the status a refusal answers the client with.
     *
     * @return the status to answer a refusal with, empty when the handshake was accepted
     */
    public Optional<HttpStatus> refusalStatus() {
        return Optional.ofNullable(refusalStatus);
    }
}
