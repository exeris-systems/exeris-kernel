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
     * Baseline capabilities descriptor — all capability flags {@code false},
     * blocking TCP transport, provider-neutral identifier.
     *
     * <p>Use this constant when the engine operates over a standard JDBC-style
     * TCP connection with no native protocol, no zero-copy rows, no kernel async
     * transport, and no per-tenant pools. Provider implementations SHOULD return this
     * constant from {@link PersistenceEngine#capabilities()} to guarantee
     * O(1), allocation-free access:
     * <pre>{@code return PersistenceEngineCapabilities.DEFAULT; // O(1), no allocation}</pre>
     *
     * <p>Provider-specific capability constants (e.g., with a branded
     * {@link #providerId()}) belong in the driver module, not in this SPI.
     */
    public static final PersistenceEngineCapabilities DEFAULT = new PersistenceEngineCapabilities(
            false, false, false, false,
            "BlockingTCP",
            "default"
    );

    /**
     * High-performance capabilities descriptor — all capability flags {@code true},
     * native async transport, provider-neutral identifier.
     *
     * <p>Use this constant as a starting-point template when the engine supports
     * all advanced features (native wire protocol, zero-copy rows, kernel async
     * transport, per-tenant pools). Enterprise-tier driver implementations that do not
     * require a branded constant MAY return this instance directly from
     * {@link PersistenceEngine#capabilities()}.
     *
     * <p>Provider-specific capability constants (e.g., with a branded
     * {@link #providerId()}) belong in the driver module, not in this SPI.
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
}
