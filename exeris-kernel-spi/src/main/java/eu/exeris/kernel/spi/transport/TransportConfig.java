/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 * @param idleTimeoutMillis connection idle timeout in milliseconds (0 = no timeout). Validated and
 *                          carried to the driver, but <b>not enforced by the Community NIO carrier</b>,
 *                          which has no idle reaper: setting it there changes nothing. Stated rather
 *                          than implied, because a timeout an operator believes is active and is not
 *                          is worse than one that is documented as absent. A driver is free to honour
 *                          it;
 *                          ignored and not validated when mode is {@link TransportMode#DISABLED}
 * @see TransportProvider
 * @see TransportEngine
 * @since 0.5.0
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

    /**
     * Default bind address: all interfaces.
     */
    @SuppressWarnings("PMD.AvoidUsingHardCodedIP") // Wildcard address is intentional SPI constant
    public static final String DEFAULT_BIND_ADDRESS = "0.0.0.0";

    /**
     * Default reactor count: number of available processors (minimum 1),
     * optionally capped by {@code exeris.transport.defaultMaxReactors}.
     *
     * <p>If the property is absent, blank, non-numeric, or non-positive,
     * no cap is applied.
     */
    public static final int DEFAULT_REACTOR_COUNT =
            TransportConfigSupport.computeDefaultReactorCount("exeris.transport.defaultMaxReactors");

    /**
     * Compact constructor — validates invariants eagerly (fail-fast bootstrap).
     */
    public TransportConfig {
        if (mode == null) {
            throw new IllegalArgumentException("TransportMode must not be null");
        }
        if (mode != TransportMode.DISABLED) {
            TransportConfigSupport.validateNonDisabled(
                    mode,
                    bindAddress,
                    port,
                    reactorCount,
                    maxConnections,
                    idleTimeoutMillis
            );
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
                "unbound",
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
