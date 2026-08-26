/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 *                              use {@code 0} for ephemeral bind in SERVER / DUAL modes;
 *                              use {@code -1} as sentinel for CLIENT / DISABLED
 * @param maxConnections        hard cap on concurrent connections; ignored for DISABLED
 * @param idleTimeoutMillis     connection idle timeout in ms (0 = no timeout); ignored for DISABLED
 * @param maxRequestHeaderCount maximum number of header fields per request (DoS guard); must be
 *                              &gt; 0 — 0 is refused rather than read as "unlimited", because it
 *                              refuses every request carrying a header (ADR-071)
 * @param maxRequestHeaderSize  maximum byte size of a single header field (DoS guard); must be
 *                              &gt; 0, refused on the same grounds
 * @param maxRequestBodyBytes   maximum request body size in bytes; ({@code -1} = unlimited)
 * @param h2cUpgradeEnabled     whether to accept HTTP/1.1 → HTTP/2 cleartext upgrade (RFC 7540 §3.2)
 * @param defaultAuthority      CLIENT/DUAL default peer as {@code host} or {@code host:port}, used
 *                              when an {@link HttpRequest#authority()} is {@code null}; may itself be
 *                              {@code null}, in which case an unaddressed request is refused rather
 *                              than sent somewhere unintended (ADR-074). This is a DIAL address and
 *                              is deliberately not {@code bindHost}, which is a LISTEN address
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
        HttpVersion maxVersion,
        String defaultAuthority
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
        if (mode != HttpMode.DISABLED) {
            HttpConfigValidation.validateConnectionLimits(maxConnections, idleTimeoutMillis);
            HttpConfigValidation.validateRequestLimits(
                    maxRequestHeaderCount, maxRequestHeaderSize, maxRequestBodyBytes);
            HttpConfigValidation.validatePort(mode, port, bindHost);
            HttpConfigValidation.validateDefaultAuthority(defaultAuthority);
        }
    }

    /**
     * Creates a configuration with no default client peer.
     *
     * <p>This is the canonical constructor as it stood before 0.12, retained as a compatibility
     * bridge so that adding {@link #defaultAuthority()} to a {@code stable} carrier does not break
     * existing callers. It delegates with a {@code null} default authority.
     *
     * @param mode                  operating mode
     * @param bindHost              listener bind address for SERVER / DUAL modes
     * @param port                  listener port for SERVER / DUAL modes
     * @param maxConnections        maximum concurrent connections
     * @param idleTimeoutMillis     idle connection timeout, {@code 0} disables
     * @param maxRequestHeaderCount maximum request header count
     * @param maxRequestHeaderSize  maximum single request header size in bytes
     * @param maxRequestBodyBytes   maximum request body size in bytes
     * @param h2cUpgradeEnabled     whether cleartext h2c upgrade is honoured
     * @param maxVersion            highest negotiable HTTP version
     * @since 0.5.0
     */
    @SuppressWarnings("PMD.ExcessiveParameterList") // backward-compat bridge
    public HttpConfig(HttpMode mode,
                      String bindHost,
                      int port,
                      int maxConnections,
                      long idleTimeoutMillis,
                      int maxRequestHeaderCount,
                      int maxRequestHeaderSize,
                      long maxRequestBodyBytes,
                      boolean h2cUpgradeEnabled,
                      HttpVersion maxVersion) {
        this(mode, bindHost, port, maxConnections, idleTimeoutMillis, maxRequestHeaderCount,
                maxRequestHeaderSize, maxRequestBodyBytes, h2cUpgradeEnabled, maxVersion, null);
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
                HttpVersion.HTTP_2,
                null
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
                HttpVersion.HTTP_2,
                null
        );
    }
}

