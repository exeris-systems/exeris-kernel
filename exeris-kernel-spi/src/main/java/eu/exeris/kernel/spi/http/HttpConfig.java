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
 * <p>No identity operations ({@code ==}, {@code System.identityHashCode()},
 * {@code synchronized}) on instances. Declared {@code value record} on the `preview` line (JEP 401); the distributed line compiles
 * the same source as an identity {@code record}, and the modifier is asserted by
 * {@code Class::isValue} in the module's value-carrier registry test.
 *
 * @param mode                  operational mode (SERVER / CLIENT / DUAL / DISABLED)
 * @param bindHost              listener bind address for SERVER / DUAL modes;
 *                              {@code null} or ignored for CLIENT / DISABLED
 * @param port                  listener port for SERVER / DUAL modes;
 *                              use {@code 0} for ephemeral bind in SERVER / DUAL modes;
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
public value record HttpConfig(
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

    private static final int MIN_SERVER_PORT = 0;
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65_535;
    private static final int MIN_CONNECTIONS = 1;

    public HttpConfig {
        Objects.requireNonNull(mode,       "mode must not be null");
        Objects.requireNonNull(maxVersion, "maxVersion must not be null");
        if (mode != HttpMode.DISABLED) {
            validateConnectionLimits(maxConnections, idleTimeoutMillis);
            validateRequestLimits(maxRequestHeaderCount, maxRequestHeaderSize, maxRequestBodyBytes);
            validatePort(mode, port, bindHost);
        }
    }

    private static void validatePort(HttpMode mode, int port, String bindHost) {
        if (mode == HttpMode.SERVER || mode == HttpMode.DUAL) {
            validateServerDualBinding(bindHost, port);
        } else {
            validateClientPort(port);
        }
    }

    private static void validateServerDualBinding(String bindHost, int port) {
        if (bindHost == null || bindHost.isBlank()) {
            throw new IllegalArgumentException(
                    "bindHost must not be null or blank for SERVER/DUAL mode");
        }
        if (port < MIN_SERVER_PORT || port > MAX_PORT) {
            throw new IllegalArgumentException(
                    "port must be in range [0, 65535] for SERVER/DUAL mode (0 = ephemeral), got: " + port);
        }
    }

    private static void validateClientPort(int port) {
        if (port != -1 && (port < MIN_PORT || port > MAX_PORT)) {
            throw new IllegalArgumentException(
                    "port must be -1 (sentinel) or 1-65535 for CLIENT mode, got: " + port);
        }
    }

    private static void validateConnectionLimits(int maxConnections, long idleTimeoutMillis) {
        if (maxConnections < MIN_CONNECTIONS) {
            throw new IllegalArgumentException(
                    "maxConnections must be >= 1, got: " + maxConnections);
        }
        if (idleTimeoutMillis < 0) {
            throw new IllegalArgumentException(
                    "idleTimeoutMillis must be >= 0 (0 = no timeout), got: " + idleTimeoutMillis);
        }
    }

    private static void validateRequestLimits(int maxRequestHeaderCount, int maxRequestHeaderSize,
                                               long maxRequestBodyBytes) {
        if (maxRequestHeaderCount < 0) {
            throw new IllegalArgumentException(
                    "maxRequestHeaderCount must be >= 0, got: " + maxRequestHeaderCount);
        }
        if (maxRequestHeaderSize < 0) {
            throw new IllegalArgumentException(
                    "maxRequestHeaderSize must be >= 0, got: " + maxRequestHeaderSize);
        }
        if (maxRequestBodyBytes < -1) {
            throw new IllegalArgumentException(
                    "maxRequestBodyBytes must be >= -1 (-1 = unlimited), got: " + maxRequestBodyBytes);
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

