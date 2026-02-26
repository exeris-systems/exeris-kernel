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

    // CHECKSTYLE.OFF: DeclarationOrder — static constants in records must follow components list

    /**
     * Baseline capabilities template — single-stream, no zero-copy (Community tier baseline).
     *
     * <p><b>Usage:</b> driver implementations SHOULD NOT return this constant directly
     * from {@link TransportEngine#capabilities()} because its {@link #providerId()}
     * is {@code "community"}, which may produce incorrect JFR/diagnostic metadata for
     * custom providers. Instead, build a provider-branded cached constant once at
     * class-load time:
     * <pre>{@code
     * private static final TransportEngineCapabilities CAPS =
     *         TransportEngineCapabilities.STANDARD.withProvider("my-transport-community");
     *
     * public TransportEngineCapabilities capabilities() { return CAPS; }
     * }</pre>
     */
    public static final TransportEngineCapabilities STANDARD =
            new TransportEngineCapabilities(false, false, "StandardTCP", "community");

    /**
     * High-performance capabilities template — multiplexed streams, zero-copy slab writes
     * (Enterprise tier baseline).
     *
     * <p><b>Usage:</b> driver implementations SHOULD NOT return this constant directly
     * from {@link TransportEngine#capabilities()} because its {@link #providerId()}
     * is {@code "enterprise"}, which may produce incorrect JFR/diagnostic metadata for
     * custom providers. Instead, build a provider-branded cached constant once at
     * class-load time:
     * <pre>{@code
     * private static final TransportEngineCapabilities CAPS =
     *         TransportEngineCapabilities.MULTIPLEXED.withProvider("my-transport-enterprise");
     *
     * public TransportEngineCapabilities capabilities() { return CAPS; }
     * }</pre>
     */
    public static final TransportEngineCapabilities MULTIPLEXED =
            new TransportEngineCapabilities(true, true, "NativeMultiplexed", "enterprise");

    // CHECKSTYLE.ON: DeclarationOrder

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

    /**
     * Returns a new descriptor identical to this one but with the given {@code providerId}.
     *
     * <p>Intended for driver modules that want to reuse a standard template
     * ({@link #STANDARD} or {@link #MULTIPLEXED}) while stamping their own
     * provider identifier for accurate JFR and diagnostic output. Call once at
     * class-load time and cache the result — allocation happens only during bootstrap.
     *
     * <pre>{@code
     * private static final TransportEngineCapabilities CAPS =
     *         TransportEngineCapabilities.STANDARD.withProvider("community-nio");
     * }</pre>
     *
     * @param newProviderId stable provider identifier; must not be blank
     * @return new descriptor with all flags/transportName from this instance, new providerId
     * @throws IllegalArgumentException if {@code newProviderId} is blank
     */
    public TransportEngineCapabilities withProvider(String newProviderId) {
        return new TransportEngineCapabilities(
                supportsMultiplexing,
                supportsZeroCopy,
                transportName,
                newProviderId
        );
    }
}

