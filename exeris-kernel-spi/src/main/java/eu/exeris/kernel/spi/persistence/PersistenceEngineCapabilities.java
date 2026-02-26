/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.persistence;

/**
 * SPI: Immutable capability descriptor for a {@link PersistenceEngine} instance.
 *
 * <h2>Why This Exists (Not Software Inflation)</h2>
 * <p>{@code KernelBootstrap} already gates QUIC activation on
 * {@link eu.exeris.kernel.spi.crypto.KernelCryptoProvider#supportsQuic()}.
 * The persistence layer needs the same pattern so that:
 * <ul>
 *   <li>The bootstrapper can emit a fully-populated {@code PersistenceEngineBootstrapEvent}
 *       (JFR) with the correct transport name and tier flags without downcasting.</li>
 *   <li>TCK tests can conditionally skip zero-alloc assertions when running against
 *       the Community tier (which permits per-row allocations).</li>
 *   <li>The {@code KernelBootstrap} WARN path (analogous to the QUIC warn) can alert
 *       operators when the engine does not support native protocol — e.g., when
 *       Enterprise jar is missing from the classpath.</li>
 * </ul>
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Standard {@code record} — all fields are primitives or Strings.
 * No identity operations ({@code ==}, {@code synchronized},
 * {@code System.identityHashCode()}) are permitted on instances.
 * Ready for {@code value record} migration once JEP 401 is mainline.
 *
 * <h2>Tier Expectations</h2>
 * <table>
 *   <tr><th>Capability</th><th>Community</th><th>Enterprise</th></tr>
 *   <tr><td>{@link #supportsNativeProtocol()}</td><td>{@code false}</td><td>{@code true}</td></tr>
 *   <tr><td>{@link #supportsZeroCopyRows()}</td><td>{@code false}</td><td>{@code true}</td></tr>
 *   <tr><td>{@link #supportsKernelAsyncTransport()}</td><td>{@code false}</td><td>{@code true}</td></tr>
 *   <tr><td>{@link #supportsPerTenantPools()}</td><td>{@code false}</td><td>{@code true}</td></tr>
 *   <tr><td>{@link #transportName()}</td><td>{@code "BlockingTCP"}</td><td>{@code "NativeAsync"}</td></tr>
 * </table>
 *
 * @param supportsNativeProtocol       {@code true} if this engine bypasses JDBC entirely and
 *                                     speaks the storage wire protocol directly (PG Native,
 *                                     Cassandra native, etc.).
 * @param supportsZeroCopyRows         {@code true} if {@link QueryResult#row()} reads directly
 *                                     from off-heap {@link eu.exeris.kernel.spi.memory.LoanedBuffer}
 *                                     — zero heap allocation per row iteration.
 * @param supportsKernelAsyncTransport {@code true} if the transport layer uses kernel-level
 *                                     async I/O (e.g. Linux io_uring, Windows IOCP) instead of
 *                                     blocking or NIO threads. Implementation detail stays in
 *                                     the driver — SPI only declares the capability tier.
 * @param supportsPerTenantPools       {@code true} if the engine maintains per-tenant connection
 *                                     pools for full connection-level isolation.
 * @param transportName                Stable display name of the transport tier, used in JFR
 *                                     events and diagnostic output. SPI-level values:
 *                                     {@code "BlockingTCP"} or {@code "NativeAsync"}.
 *                                     Driver modules MAY use a more specific label in their
 *                                     own {@link PersistenceEngineCapabilities} constants.
 * @param providerId                   Stable provider identifier (mirrors
 *                                     {@link PersistenceProvider#providerId()}).
 *
 * @since 0.5.0
 * @see PersistenceEngine
 * @see PersistenceProvider
 */
public record PersistenceEngineCapabilities(
        boolean supportsNativeProtocol,
        boolean supportsZeroCopyRows,
        boolean supportsKernelAsyncTransport,
        boolean supportsPerTenantPools,
        String  transportName,
        String  providerId
) {

    // CHECKSTYLE.OFF: DeclarationOrder — static constants in records must follow components list

    /**
     * Baseline capabilities template — all capability flags {@code false},
     * blocking TCP transport.
     *
     * <p><b>Usage:</b> driver implementations SHOULD NOT return this constant directly
     * from {@link PersistenceEngine#capabilities()} because its {@link #providerId()}
     * is {@code "default"}, which would produce incorrect JFR/diagnostic metadata.
     * Instead, build a provider-branded cached constant once at class-load time:
     * <pre>{@code
     * // In your PersistenceEngine implementation:
     * private static final PersistenceEngineCapabilities CAPS =
     *         PersistenceEngineCapabilities.DEFAULT.withProvider("my-provider-id");
     *
     * public PersistenceEngineCapabilities capabilities() { return CAPS; }
     * }</pre>
     */
    public static final PersistenceEngineCapabilities DEFAULT = new PersistenceEngineCapabilities(
            false, false, false, false,
            "BlockingTCP",
            "default"
    );

    /**
     * High-performance capabilities template — all capability flags {@code true},
     * native async transport.
     *
     * <p><b>Usage:</b> driver implementations SHOULD NOT return this constant directly
     * from {@link PersistenceEngine#capabilities()} because its {@link #providerId()}
     * is {@code "high-performance"}, which would produce incorrect JFR/diagnostic metadata.
     * Instead, build a provider-branded cached constant once at class-load time:
     * <pre>{@code
     * // In your PersistenceEngine implementation:
     * private static final PersistenceEngineCapabilities CAPS =
     *         PersistenceEngineCapabilities.HIGH_PERFORMANCE.withProvider("my-provider-id");
     *
     * public PersistenceEngineCapabilities capabilities() { return CAPS; }
     * }</pre>
     */
    public static final PersistenceEngineCapabilities HIGH_PERFORMANCE = new PersistenceEngineCapabilities(
            true, true, true, true,
            "NativeAsync",
            "high-performance"
    );

    // CHECKSTYLE.ON: DeclarationOrder

    /**
     * Compact constructor — validates required String fields.
     */
    public PersistenceEngineCapabilities {
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
     * ({@link #DEFAULT} or {@link #HIGH_PERFORMANCE}) while stamping their own
     * provider identifier for accurate JFR and diagnostic output. Call once at
     * class-load time and cache the result — allocation happens only during bootstrap.
     *
     * <pre>{@code
     * private static final PersistenceEngineCapabilities CAPS =
     *         PersistenceEngineCapabilities.DEFAULT.withProvider("postgres-community");
     * }</pre>
     *
     * @param newProviderId stable provider identifier; must not be blank
     * @return new descriptor with all flags/transport from this instance, new providerId
     * @throws IllegalArgumentException if {@code newProviderId} is blank
     */
    public PersistenceEngineCapabilities withProvider(String newProviderId) {
        return new PersistenceEngineCapabilities(
                supportsNativeProtocol,
                supportsZeroCopyRows,
                supportsKernelAsyncTransport,
                supportsPerTenantPools,
                transportName,
                newProviderId
        );
    }
}
