/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.http;

import java.util.Objects;

/**
 * SPI: HTTP engine configuration — protocol-blind, tier-agnostic.
 *
 * <h2>The Wall</h2>
 * <p>This record contains <strong>only</strong> parameters that are meaningful to every
 * HTTP engine tier (Community HTTP/1.1 + HTTP/2, Enterprise HTTP/3/QPACK). Protocol-specific
 * tuning (QPACK dynamic table capacity, io_uring ring depth, QUIC congestion window) is
 * the implementation's private concern and MUST NOT leak into this record.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Standard {@code record}. No identity operations ({@code ==},
 * {@code System.identityHashCode()}, {@code synchronized}) on instances.
 * Will migrate to {@code value record} (JEP 401) once mainline GA is reached.
 *
 * @param mode                  operational mode (SERVER / CLIENT / DUAL / DISABLED)
 * @param bindHost              listener bind address for SERVER / DUAL modes;
 *                              {@code null} or ignored for CLIENT / DISABLED
 * @param port                  listener port for SERVER / DUAL modes;
 *                              use {@code -1} as sentinel for CLIENT / DISABLED
 * @param maxConnections        hard cap on concurrent connections; ignored for DISABLED
 * @param idleTimeoutMillis     connection idle timeout in ms (0 = no timeout); ignored for DISABLED
 * @param maxRequestHeaderCount maximum number of header fields per request (DoS guard)
 * @param maxRequestHeaderSize  maximum byte size of a single header field (DoS guard)
 * @param maxRequestBodyBytes   maximum request body size in bytes; ({@code -1} = unlimited)
 * @param h2cUpgradeEnabled     whether to accept HTTP/1.1 → HTTP/2 cleartext upgrade (RFC 7540 §3.2)
 * @param maxVersion            highest HTTP version this engine is permitted to negotiate;
 *                              {@link HttpVersion#HTTP_3} requires Enterprise provider
 * @since 0.5.0
 */
public record HttpConfig(
        HttpMode mode,
        String bindHost,
        int port,
        int maxConnections,
        long idleTimeoutMillis,
        int maxRequestHeaderCount,
        int maxRequestHeaderSize,
        long maxRequestBodyBytes,
        boolean h2cUpgradeEnabled,
        HttpVersion maxVersion
) {

    /** Default bind address: all interfaces. */
    @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
    public static final String DEFAULT_BIND_HOST = "0.0.0.0";

    /** Default server port. */
    public static final int DEFAULT_PORT = 8080;

    /** Default maximum concurrent connections. */
    public static final int DEFAULT_MAX_CONNECTIONS = 1_000;

    /** Default idle timeout: 30 seconds. */
    public static final long DEFAULT_IDLE_TIMEOUT_MS = 30_000L;

    /** Default max header field count (DoS guard). */
    public static final int DEFAULT_MAX_HEADER_COUNT = 100;

    /** Default max header field size in bytes (DoS guard). */
    public static final int DEFAULT_MAX_HEADER_SIZE = 8_192;

    /** Default max request body: 10 MiB. */
    public static final long DEFAULT_MAX_REQUEST_BODY_BYTES = 10L * 1_024 * 1_024;

    public HttpConfig {
        Objects.requireNonNull(mode,       "mode must not be null");
        Objects.requireNonNull(maxVersion, "maxVersion must not be null");
        if (mode == HttpMode.SERVER || mode == HttpMode.DUAL) {
            Objects.requireNonNull(bindHost, "bindHost must not be null for SERVER/DUAL mode");
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException(
                        "port must be in range [1, 65535] for SERVER/DUAL mode, got: " + port);
            }
        }
    }

    /**
     * Returns a default HTTP server configuration on port 8080, HTTP/1.1 + HTTP/2,
     * h2c upgrade enabled.
     *
     * @return pre-configured server config
     */
    public static HttpConfig defaultServer() {
        return new HttpConfig(
                HttpMode.SERVER,
                DEFAULT_BIND_HOST,
                DEFAULT_PORT,
                DEFAULT_MAX_CONNECTIONS,
                DEFAULT_IDLE_TIMEOUT_MS,
                DEFAULT_MAX_HEADER_COUNT,
                DEFAULT_MAX_HEADER_SIZE,
                DEFAULT_MAX_REQUEST_BODY_BYTES,
                true,
                HttpVersion.HTTP_2
        );
    }

    /**
     * Returns a default HTTP client configuration (no server binding).
     *
     * @return pre-configured client config
     */
    public static HttpConfig defaultClient() {
        return new HttpConfig(
                HttpMode.CLIENT,
                null,
                -1,
                DEFAULT_MAX_CONNECTIONS,
                DEFAULT_IDLE_TIMEOUT_MS,
                DEFAULT_MAX_HEADER_COUNT,
                DEFAULT_MAX_HEADER_SIZE,
                DEFAULT_MAX_REQUEST_BODY_BYTES,
                false,
                HttpVersion.HTTP_2
        );
    }
}

