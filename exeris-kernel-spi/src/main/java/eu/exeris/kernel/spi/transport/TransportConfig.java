/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.transport;

/**
 * SPI: Transport layer configuration — protocol-blind, tier-agnostic.
 *
 * <h2>The Wall</h2>
 * <p>This record contains <strong>only</strong> parameters that are meaningful to every
 * transport tier (Community and Enterprise). Protocol-specific tuning (QUIC congestion
 * control, io_uring ring depth, provided-buffer count) is the implementation's private
 * concern and MUST NOT leak into this record.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>This is a standard {@code record} — no identity operations ({@code ==},
 * {@code synchronized}, {@code System.identityHashCode()}) may be performed on instances.
 * Future migration to {@code value record} (JEP 401) is expected.
 *
 * @param mode              operational mode (SERVER/CLIENT/DUAL/DISABLED)
 * @param bindAddress       address to bind the listener to (e.g. {@code "0.0.0.0"})
 * @param port              listener port number (1–65 535); ignored if mode is CLIENT
 * @param reactorCount      number of carrier reactor threads (typically ≤ CPU cores)
 * @param certPath          path to TLS certificate (PEM); {@code null} if TLS not configured
 * @param keyPath           path to TLS private key (PEM); {@code null} if TLS not configured
 * @param maxConnections    hard cap on concurrent connections across all reactors
 * @param idleTimeoutMillis connection idle timeout in milliseconds (0 = no timeout)
 * @since 0.5.0
 * @see TransportProvider
 * @see TransportEngine
 */
public record TransportConfig(
        TransportMode mode,
        String bindAddress,
        int port,
        int reactorCount,
        String certPath,
        String keyPath,
        int maxConnections,
        long idleTimeoutMillis
) {

    /** Default bind address: all interfaces. */
    @SuppressWarnings("PMD.AvoidUsingHardCodedIP") // Wildcard address is intentional SPI constant
    public static final String DEFAULT_BIND_ADDRESS = "0.0.0.0";

    /** Minimum allowed reactor count. */
    private static final int MIN_REACTOR_COUNT = 1;

    /** Maximum default reactor count (capped for container environments). */
    private static final int MAX_DEFAULT_REACTORS = 4;

    /** Minimum allowed connection count. */
    private static final int MIN_CONNECTIONS = 1;

    /** Minimum valid port number. */
    private static final int MIN_PORT = 1;

    /** Maximum valid port number. */
    private static final int MAX_PORT = 65_535;

    /** Default reactor count: number of available processors, capped at 4. */
    public static final int DEFAULT_REACTOR_COUNT =
            Math.clamp(Runtime.getRuntime().availableProcessors(), MIN_REACTOR_COUNT, MAX_DEFAULT_REACTORS);

    /**
     * Compact constructor — validates invariants eagerly (fail-fast bootstrap).
     */
    public TransportConfig {
        if (mode == null) {
            throw new IllegalArgumentException("TransportMode must not be null");
        }
        if (mode != TransportMode.DISABLED) {
            if (bindAddress == null || bindAddress.isBlank()) {
                throw new IllegalArgumentException("bindAddress must not be null/blank when transport is enabled");
            }
            if (mode != TransportMode.CLIENT && (port < MIN_PORT || port > MAX_PORT)) {
                throw new IllegalArgumentException("port must be 1–65535 for SERVER/DUAL mode, got: " + port);
            }
            if (reactorCount < MIN_REACTOR_COUNT) {
                throw new IllegalArgumentException(
                        "reactorCount must be >= " + MIN_REACTOR_COUNT + ", got: " + reactorCount);
            }
            if (maxConnections < MIN_CONNECTIONS) {
                throw new IllegalArgumentException(
                        "maxConnections must be >= " + MIN_CONNECTIONS + ", got: " + maxConnections);
            }
        }
        if (idleTimeoutMillis < 0) {
            throw new IllegalArgumentException(
                    "idleTimeoutMillis must be >= 0 (0 = no timeout), got: " + idleTimeoutMillis);
        }
    }

    /**
     * Overrides the default record {@code toString()} to redact sensitive TLS paths
     * (certificate and key file locations) from logs and diagnostics output.
     *
     * @return a safe string representation with redacted credential paths
     */
    @Override
    public String toString() {
        return "TransportConfig[" +
                "mode=" + mode + ", " +
                "bindAddress='" + bindAddress + "', " +
                "port=" + port + ", " +
                "reactorCount=" + reactorCount + ", " +
                "certPath=" + (certPath != null ? "***REDACTED***" : "null") + ", " +
                "keyPath=" + (keyPath != null ? "***REDACTED***" : "null") + ", " +
                "maxConnections=" + maxConnections + ", " +
                "idleTimeoutMillis=" + idleTimeoutMillis + ']';
    }

    /**
     * Returns a disabled configuration — zero resources allocated.
     *
     * @return config with {@link TransportMode#DISABLED}
     */
    public static TransportConfig disabled() {
        return new TransportConfig(
                TransportMode.DISABLED,
                DEFAULT_BIND_ADDRESS,
                0,
                0,
                null,
                null,
                0,
                0
        );
    }

    /**
     * Returns a sensible server-mode configuration for the given port.
     *
     * @param port listener port
     * @return server config with defaults
     */
    public static TransportConfig serverDefaults(int port) {
        return new TransportConfig(
                TransportMode.SERVER,
                DEFAULT_BIND_ADDRESS,
                port,
                DEFAULT_REACTOR_COUNT,
                null,
                null,
                4096,
                30_000
        );
    }
}



