/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * @param idleTimeoutMillis connection idle timeout in milliseconds (0 = no timeout). Enforced by the
 *                          Community carrier's per-reactor idle sweep, which closes a connection whose
 *                          stream has moved no bytes for this long; {@code 0} disables the sweep. A
 *                          driver is free to honour it in its own way;
 *                          ignored and not validated when mode is {@link TransportMode#DISABLED}
 * @param maxActiveStreams  hard cap on streams admitted concurrently by the scheduler, across all
 *                          connections on this engine. One connection carries one stream on HTTP/1
 *                          and many on HTTP/2, which is why this is a separate bound from
 *                          {@code maxConnections} rather than derivable from it.
 *                          {@link #UNBOUNDED_ACTIVE_STREAMS} removes the ceiling — see that
 *                          constant for what remains protecting the engine when it is set;
 *                          ignored and not validated when mode is {@link TransportMode#DISABLED}
 * @param maxQueuedWriteBytes bytes a stream may hold that its peer has not yet taken before
 *                          {@link TransportStream#write} blocks: a positive bound, or
 *                          {@link #UNBOUNDED_QUEUED_WRITE_BYTES} for none. Bytes handed over through
 *                          {@link TransportStream#queueWrite} count against it and never wait on it.
 *                          {@code 0} and other negatives are refused, because a zero bound would block
 *                          every write; ignored and not validated when mode is
 *                          {@link TransportMode#DISABLED} (since 0.13)
 * @since 0.5
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
        long idleTimeoutMillis,
        int maxActiveStreams,
        int maxQueuedWriteBytes
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
     * Default cap on concurrently admitted streams per engine: 5 000, the queue-saturation
     * threshold the performance contract is stated against.
     */
    public static final int DEFAULT_MAX_ACTIVE_STREAMS = 5_000;

    /**
     * Sentinel for {@link #maxActiveStreams()}: admit without a stream-count ceiling.
     *
     * <p>Removing the ceiling does <b>not</b> remove admission control. The count cap is the
     * second of two gates: every stream is still decided by the memory-pressure arbiter first,
     * and a saturated engine sheds on watermark pressure whatever this value is. What an operator
     * gives up is the fixed ceiling that sheds "regardless of memory pressure" — which is the
     * point for a JVM-controlled deployment measuring where saturation actually falls, and a
     * foot-gun for a shared one. {@code 0} is refused rather than read as "off", because an
     * engine that admits nothing serves nothing.
     */
    public static final int UNBOUNDED_ACTIVE_STREAMS = -1;

    /**
     * Default bound on the bytes a stream may hold that its peer has not yet taken: 256 KiB.
     */
    public static final int DEFAULT_MAX_QUEUED_WRITE_BYTES = 256 * 1024;

    /**
     * Sentinel for {@link #maxQueuedWriteBytes()}: {@link TransportStream#write} never blocks for
     * backpressure, so a writer faster than its peer grows the stream's outbound queue without
     * limit. {@code 0} is refused rather than read as "off", because a zero bound would block every
     * write.
     */
    public static final int UNBOUNDED_QUEUED_WRITE_BYTES = -1;

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
                    idleTimeoutMillis,
                    maxActiveStreams,
                    maxQueuedWriteBytes
            );
        }
    }

    /**
     * Bridge constructor for callers written before the stream-admission cap became configurable.
     *
     * <p>Applies {@link #DEFAULT_MAX_ACTIVE_STREAMS}, which is the value the scheduler enforced
     * unconditionally before it had a key — so a caller that does not pass one gets exactly the
     * behaviour it had.
     *
     * @param mode              operational mode
     * @param bindAddress       listener bind address
     * @param port              listener port
     * @param reactorCount      carrier reactor threads
     * @param certPath          TLS certificate path, or {@code null}
     * @param keyPath           TLS private key path, or {@code null}
     * @param maxConnections    concurrent connection ceiling
     * @param idleTimeoutMillis idle timeout in milliseconds
     * @since 0.12
     */
    public TransportConfig(TransportMode mode,
                           String bindAddress,
                           int port,
                           int reactorCount,
                           String certPath,
                           String keyPath,
                           int maxConnections,
                           long idleTimeoutMillis) {
        this(mode, bindAddress, port, reactorCount, certPath, keyPath, maxConnections,
                idleTimeoutMillis, DEFAULT_MAX_ACTIVE_STREAMS, DEFAULT_MAX_QUEUED_WRITE_BYTES);
    }

    /**
     * Bridge constructor for callers written before the outbound write bound became configurable.
     *
     * <p>Applies {@link #DEFAULT_MAX_QUEUED_WRITE_BYTES}, so a stream built from such a
     * configuration is protected against a peer that stops reading.
     *
     * @param mode              operational mode
     * @param bindAddress       listener bind address
     * @param port              listener port
     * @param reactorCount      carrier reactor threads
     * @param certPath          TLS certificate path, or {@code null}
     * @param keyPath           TLS private key path, or {@code null}
     * @param maxConnections    concurrent connection ceiling
     * @param idleTimeoutMillis idle timeout in milliseconds
     * @param maxActiveStreams  stream-admission ceiling
     * @since 0.13
     */
    public TransportConfig(TransportMode mode,
                           String bindAddress,
                           int port,
                           int reactorCount,
                           String certPath,
                           String keyPath,
                           int maxConnections,
                           long idleTimeoutMillis,
                           int maxActiveStreams) {
        this(mode, bindAddress, port, reactorCount, certPath, keyPath, maxConnections,
                idleTimeoutMillis, maxActiveStreams, DEFAULT_MAX_QUEUED_WRITE_BYTES);
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
                "idleTimeoutMillis=" + idleTimeoutMillis + ", " +
                "maxActiveStreams=" + maxActiveStreams + ", " +
                "maxQueuedWriteBytes=" + maxQueuedWriteBytes + ']';
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
     *   <li>{@code maxActiveStreams} — {@link #UNBOUNDED_ACTIVE_STREAMS} (no ceiling;
     *       moot here, since DISABLED admits nothing regardless)</li>
     *   <li>{@code maxQueuedWriteBytes} — {@link #UNBOUNDED_QUEUED_WRITE_BYTES} (moot here, since
     *       DISABLED opens no stream)</li>
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
                -1,
                UNBOUNDED_ACTIVE_STREAMS,
                UNBOUNDED_QUEUED_WRITE_BYTES
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
                30_000,
                DEFAULT_MAX_ACTIVE_STREAMS,
                DEFAULT_MAX_QUEUED_WRITE_BYTES
        );
    }
}
