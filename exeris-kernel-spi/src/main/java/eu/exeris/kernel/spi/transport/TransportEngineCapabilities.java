/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.transport;

/**
 * SPI: Immutable capability descriptor for a {@link TransportEngine} instance.
 *
 * <h2>Purpose</h2>
 * <p>Allows the Core module to perform tier-detection and adapt its behaviour
 * without downcasting or importing implementation-specific classes.
 * For example, the Core may check {@link #supportsMultiplexing()} before
 * attempting to open more than one stream per connection.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>All fields are primitives or {@link String}. No identity operations
 * ({@code ==}, {@code synchronized}, {@code System.identityHashCode()}) may
 * be performed on instances. Future migration to {@code value record} (JEP 401)
 * is expected.
 *
 * <h2>O(1) Access</h2>
 * <p>Implementations MUST return a pre-built constant from
 * {@link TransportEngine#capabilities()} — never construct a new instance
 * on every call.
 *
 * @param supportsMultiplexing {@code true} if the engine supports multiple concurrent
 *                             streams per connection (e.g. QUIC 1:N);
 *                             {@code false} for single-stream transports (e.g. TCP 1:1)
 * @param supportsZeroCopy     {@code true} if the engine writes directly into
 *                             pre-registered slab buffers with no intermediate copy
 * @param transportName        human-readable transport protocol name used in JFR events
 *                             and diagnostics (e.g. {@code "StandardTCP"}, {@code "NativeQUIC"})
 * @param providerId           stable identifier of the provider that created this engine
 *                             (e.g. {@code "community"}, {@code "enterprise"})
 *
 * @since 0.5.0
 * @see TransportEngine#capabilities()
 */
public record TransportEngineCapabilities(
        boolean supportsMultiplexing,
        boolean supportsZeroCopy,
        String transportName,
        String providerId
) {

    /**
     * Pre-built constant for standard single-stream transports (Community TCP tier).
     *
     * <p>No multiplexing, no zero-copy slab writes.
     */
    public static final TransportEngineCapabilities STANDARD =
            new TransportEngineCapabilities(false, false, "StandardTCP", "community");

    /**
     * Compact constructor — validates invariants eagerly (fail-fast bootstrap).
     */
    public TransportEngineCapabilities {
        if (transportName == null || transportName.isBlank()) {
            throw new IllegalArgumentException("transportName must not be blank");
        }
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId must not be blank");
        }
    }
}

