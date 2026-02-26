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
 * @param bindAddress       listener bind address (e.g. {@code "0.0.0.0"}); required for
 *                          SERVER and DUAL modes, ignored for CLIENT and DISABLED modes
 * @param port              listener port number (1–65 535) for SERVER/DUAL modes;
 *                          use {@code -1} as a sentinel "not used" for CLIENT and DISABLED modes.
 *                          Arbitrary out-of-range non-sentinel values are rejected even in CLIENT
 *                          mode to prevent misleading diagnostics output.
 * @param reactorCount      number of carrier reactor threads (typically ≤ CPU cores);
 *                          ignored and not validated when mode is {@link TransportMode#DISABLED}
 * @param certPath          path to TLS certificate (PEM); {@code null} if TLS not configured
 * @param keyPath           path to TLS private key (PEM); {@code null} if TLS not configured
 * @param maxConnections    hard cap on concurrent connections across all reactors;
 *                          ignored and not validated when mode is {@link TransportMode#DISABLED}
 * @param idleTimeoutMillis connection idle timeout in milliseconds (0 = no timeout);
 *                          ignored and not validated when mode is {@link TransportMode#DISABLED}
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
            validateServerDualFields(mode, bindAddress, port);
            validateSharedFields(reactorCount, maxConnections, idleTimeoutMillis);
        }
    }

    private static void validateServerDualFields(TransportMode mode, String bindAddress, int port) {
        if (mode != TransportMode.CLIENT && (bindAddress == null || bindAddress.isBlank())) {
            throw new IllegalArgumentException("bindAddress must not be null/blank for SERVER/DUAL mode");
        }
        validatePort(mode, port);
    }

    /**
     * Validates the port value for the given mode.
     *
     * <ul>
     *   <li>SERVER / DUAL: port must be in the range {@value MIN_PORT}–{@value MAX_PORT}.</li>
     *   <li>CLIENT: port must be either {@code 0} (sentinel "not used") or a valid port number
     *       in the range {@value MIN_PORT}–{@value MAX_PORT}. Arbitrary out-of-range values
     *       are rejected to prevent misleading diagnostics output.</li>
     * </ul>
     */
    private static void validatePort(TransportMode mode, int port) {
        if (mode == TransportMode.CLIENT) {
            // CLIENT mode: 0 is the canonical sentinel; valid port numbers are also accepted
            // (e.g., when the same config object is reused for both client and server roles).
            if (port != 0 && (port < MIN_PORT || port > MAX_PORT)) {
                throw new IllegalArgumentException(
                        "port must be 0 (not used) or 1–65535 for CLIENT mode, got: " + port);
            }
        } else {
            if (port < MIN_PORT || port > MAX_PORT) {
                throw new IllegalArgumentException(
                        "port must be 1–65535 for SERVER/DUAL mode, got: " + port);
            }
        }
    }

    private static void validateSharedFields(int reactorCount, int maxConnections, long idleTimeoutMillis) {
        if (reactorCount < MIN_REACTOR_COUNT) {
            throw new IllegalArgumentException(
                    "reactorCount must be >= " + MIN_REACTOR_COUNT + ", got: " + reactorCount);
        }
        if (maxConnections < MIN_CONNECTIONS) {
            throw new IllegalArgumentException(
                    "maxConnections must be >= " + MIN_CONNECTIONS + ", got: " + maxConnections);
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
                "bindAddress='" + (bindAddress != null ? bindAddress : "unbound") + "', " +
                "port=" + port + ", " +
                "reactorCount=" + reactorCount + ", " +
                "certPath=" + (certPath != null ? "***REDACTED***" : "null") + ", " +
                "keyPath=" + (keyPath != null ? "***REDACTED***" : "null") + ", " +
                "maxConnections=" + maxConnections + ", " +
                "idleTimeoutMillis=" + idleTimeoutMillis + ']';
    }

    /**
     * Returns a disabled configuration — zero resources allocated, no sockets bound.
     *
     * <p>All fields other than {@code mode} carry explicit sentinel values to make the
     * "disabled / not applicable" state unambiguous in diagnostics and {@link #toString()}
     * output:
     * <ul>
     *   <li>{@code bindAddress} — {@code "unbound"} (displayed as-is by {@code toString()})</li>
     *   <li>{@code port} — {@code -1} (out-of-range sentinel; DISABLED skips port validation)</li>
     *   <li>{@code reactorCount} — {@code -1} (no carrier loops started)</li>
     *   <li>{@code maxConnections} — {@code -1} (no connection cap applies)</li>
     *   <li>{@code certPath}, {@code keyPath} — {@code null} (no TLS)</li>
     *   <li>{@code idleTimeoutMillis} — {@code -1} (not validated, not used)</li>
     * </ul>
     *
     * @return config with {@link TransportMode#DISABLED}
     */
    public static TransportConfig disabled() {
        return new TransportConfig(
                TransportMode.DISABLED,
                null,
                -1,
                -1,
                null,
                null,
                -1,
                -1
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



