/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.websocket;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * SPI: how one duplex endpoint is configured.
 *
 * <p>Shaped after {@code HttpConfig} rather than inventing a second spelling for the knobs both
 * need — bind address, connection ceiling, idle timeout — so an operator who has tuned one does not
 * have to learn the other.
 *
 * @param bindHost               address to bind; must not be null or blank
 * @param port                   port to bind; 0 selects an ephemeral port, which is what a test wants
 * @param maxConnections         ceiling on concurrent connections; must be positive
 * @param idleTimeoutMillis      how long a connection may go without traffic before it is closed;
 *                               must be positive
 * @param keepAliveIntervalMillis how often the engine sends a ping. The engine's job and not the
 *                               handler's: intermediaries drop idle connections, commonly around a
 *                               minute, and a handler that must remember to ping is a handler that
 *                               forgets. Must be positive and below {@code idleTimeoutMillis}, since
 *                               a keepalive that fires after the timeout keeps nothing alive
 * @param maxMessageBytes        largest message accepted, after reassembling continuation frames.
 *                               The default is chosen against the payloads this carries rather than
 *                               a round number: 8 KB, measured on another server, is orders of
 *                               magnitude too small for a request carrying a serialised model
 *                               baseline or a full projection. A limit a normal request exceeds is a
 *                               limit somebody raises in a hurry (ADR-071). Must be positive
 * @param allowedOrigins         origins permitted to open a connection. <strong>Empty means no
 *                               browser origin is accepted</strong>, not "any": a WebSocket
 *                               handshake is not subject to CORS, so an unchecked {@code Origin}
 *                               lets any page the user has visited open a connection carrying their
 *                               cookies. Forgetting this produces a refusal somebody notices, rather
 *                               than a hole nobody does
 * @since 0.12
 */
public record WebSocketConfig(
        String bindHost,
        int port,
        int maxConnections,
        long idleTimeoutMillis,
        long keepAliveIntervalMillis,
        long maxMessageBytes,
        Set<String> allowedOrigins
) {

    /** 1 MiB. Two orders of magnitude above the 8 KB that was measured to be too small. */
    public static final long DEFAULT_MAX_MESSAGE_BYTES = 1_048_576L;

    /** 60 s, matching {@code HttpConfig}'s idle default. */
    public static final long DEFAULT_IDLE_TIMEOUT_MILLIS = 60_000L;

    /** 20 s — comfortably inside the intervals intermediaries commonly enforce. */
    public static final long DEFAULT_KEEP_ALIVE_INTERVAL_MILLIS = 20_000L;

    /** 1024, matching {@code HttpConfig}'s connection default. */
    public static final int DEFAULT_MAX_CONNECTIONS = 1024;

    /**
     * Validates every invariant eagerly and copies {@code allowedOrigins} into an immutable set, so
     * a constructed configuration cannot be changed through a reference the caller retained.
     *
     * @throws NullPointerException     if {@code bindHost} or {@code allowedOrigins} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code bindHost} is blank; if {@code port} is outside
     *                                  {@code [0, 65535]}; if {@code maxConnections},
     *                                  {@code idleTimeoutMillis}, {@code keepAliveIntervalMillis}
     *                                  or {@code maxMessageBytes} is not positive; or if
     *                                  {@code keepAliveIntervalMillis} is not below
     *                                  {@code idleTimeoutMillis}
     */
    public WebSocketConfig {
        Objects.requireNonNull(bindHost, "bindHost must not be null");
        Objects.requireNonNull(allowedOrigins, "allowedOrigins must not be null");
        if (bindHost.isBlank()) {
            throw new IllegalArgumentException("bindHost must not be blank");
        }
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (maxConnections <= 0) {
            throw new IllegalArgumentException("maxConnections must be positive: " + maxConnections);
        }
        if (idleTimeoutMillis <= 0) {
            throw new IllegalArgumentException(
                    "idleTimeoutMillis must be positive: " + idleTimeoutMillis);
        }
        if (keepAliveIntervalMillis <= 0) {
            throw new IllegalArgumentException(
                    "keepAliveIntervalMillis must be positive: " + keepAliveIntervalMillis);
        }
        // A keepalive that fires after the idle timeout has already closed the connection keeps
        // nothing alive; the pair is only meaningful in one order, so the invalid one is refused
        // at construction rather than discovered as a connection that drops every minute.
        if (keepAliveIntervalMillis >= idleTimeoutMillis) {
            throw new IllegalArgumentException(
                    "keepAliveIntervalMillis must be below idleTimeoutMillis: "
                            + keepAliveIntervalMillis + " >= " + idleTimeoutMillis);
        }
        if (maxMessageBytes <= 0) {
            throw new IllegalArgumentException(
                    "maxMessageBytes must be positive: " + maxMessageBytes);
        }
        allowedOrigins = Set.copyOf(allowedOrigins);
    }

    /**
     * A server configuration with the defaults above.
     *
     * @param bindHost       address to bind
     * @param port           port to bind, or 0 for an ephemeral one
     * @param allowedOrigins origins permitted to connect; empty accepts no browser origin
     * @return the configuration; never {@code null}
     */
    public static WebSocketConfig defaultServer(String bindHost, int port,
                                                List<String> allowedOrigins) {
        Objects.requireNonNull(allowedOrigins, "allowedOrigins must not be null");
        return new WebSocketConfig(bindHost, port, DEFAULT_MAX_CONNECTIONS,
                DEFAULT_IDLE_TIMEOUT_MILLIS, DEFAULT_KEEP_ALIVE_INTERVAL_MILLIS,
                DEFAULT_MAX_MESSAGE_BYTES, Set.copyOf(allowedOrigins));
    }
}
