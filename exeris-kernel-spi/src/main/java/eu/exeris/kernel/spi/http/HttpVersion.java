/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

/**
 * SPI: HTTP protocol version identifiers.
 *
 * <h2>The Wall</h2>
 * <p>This enum carries the protocol version token only ({@link #token()}). Framing details
 * (HTTP/2 frame types, QPACK encoder streams, QUIC stream IDs) are never present
 * here. Community maps parsed HTTP/1.x request/status-line versions and internal
 * HTTP/2 selection to these constants; Enterprise maps negotiated HTTP/3 runtime
 * state to {@link #HTTP_3}. ALPN identifiers are transport-layer data and are not
 * modeled by this enum.
 *
 * @since 0.5
 */
public enum HttpVersion {

    /** HTTP/1.0 — RFC 1945. */
    HTTP_1_0("HTTP/1.0"),
    /** HTTP/1.1 — RFC 9112 (persistent connections, chunked transfer). */
    HTTP_1_1("HTTP/1.1"),
    /** HTTP/2 — RFC 9113 (multiplexed streams, HPACK header compression). */
    HTTP_2("HTTP/2"),
    /** HTTP/3 — RFC 9114 (QUIC transport, QPACK header compression). Enterprise-only. */
    HTTP_3("HTTP/3");

    private final String token;

    HttpVersion(String token) {
        this.token = token;
    }

    /**
     * Returns the protocol version token as it appears in textual HTTP version
     * representations (e.g., {@code "HTTP/1.1"} in a request/status line).
     *
     * @return non-null, non-blank version token
     */
    public String token() {
        return token;
    }

    /**
     * Returns {@code true} if this protocol version supports stream multiplexing
     * over a single connection.
     *
     * <p>HTTP/2 and HTTP/3 multiplex many logical streams; HTTP/1.x does not.
     *
     * @return {@code true} for HTTP/2 and HTTP/3
     */
    public boolean supportsMultiplexing() {
        return this == HTTP_2 || this == HTTP_3;
    }

    /**
     * Returns {@code true} if this protocol version requires QUIC transport.
     *
     * <p>Only {@link #HTTP_3} requires QUIC. The Enterprise driver is the only
     * tier that may bind HTTP/3 traffic.
     *
     * @return {@code true} for HTTP/3
     */
    public boolean requiresQuic() {
        return this == HTTP_3;
    }

    @Override
    public String toString() {
        return token;
    }
}

