/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

/**
 * SPI: HTTP engine operational mode — mirrors the transport-layer
 * {@code TransportMode} pattern to provide a symmetric bootstrap experience.
 *
 * <h2>Mix-and-Match</h2>
 * <p>The HTTP engine mode is independent of the underlying transport mode. It is
 * valid to run an HTTP server engine over a QUIC transport (Enterprise), or an
 * HTTP client engine over a plain TCP transport (Community).
 *
 * @since 0.5
 */
public enum HttpMode {

    /**
     * Engine acts as an HTTP server: binds a port, accepts inbound requests,
     * dispatches to the registered {@link HttpHandler}.
     */
    SERVER,

    /**
     * Engine acts as an HTTP client: establishes outbound connections,
     * sends requests, receives responses.
     */
    CLIENT,

    /**
     * Engine acts as both server and client simultaneously.
     * Useful for reverse-proxy and service-mesh deployments.
     */
    DUAL,

    /**
     * HTTP layer is disabled. The engine is not started and no resources are allocated.
     * Used to opt out of HTTP when operating over a raw transport (e.g., gRPC-only deployments).
     */
    DISABLED
}

