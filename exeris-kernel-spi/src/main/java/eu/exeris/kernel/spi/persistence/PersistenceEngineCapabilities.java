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
 *   <tr><td>{@link #supportsIoUring()}</td><td>{@code false}</td><td>{@code true}</td></tr>
 *   <tr><td>{@link #supportsPerTenantPools()}</td><td>{@code false}</td><td>{@code true}</td></tr>
 *   <tr><td>{@link #transportName()}</td><td>{@code "BlockingTCP"}</td><td>{@code "io_uring/multishot"}</td></tr>
 * </table>
 *
 * @param supportsNativeProtocol {@code true} if this engine uses the PostgreSQL wire protocol
 *                               directly (PG Native), bypassing JDBC entirely.
 * @param supportsZeroCopyRows   {@code true} if {@link QueryResult#row()} reads directly from
 *                               off-heap {@link eu.exeris.kernel.spi.memory.LoanedBuffer} —
 *                               zero heap allocation per row iteration.
 * @param supportsIoUring        {@code true} if the transport layer uses Linux io_uring
 *                               (multishot recvmsg, provided buffer groups).
 * @param supportsPerTenantPools {@code true} if the engine maintains per-tenant connection
 *                               pools for full connection-level RLS isolation.
 * @param transportName          Stable display name of the transport layer, used in JFR events
 *                               and diagnostic output (e.g., {@code "BlockingTCP"},
 *                               {@code "io_uring/multishot"}).
 * @param providerId             Stable provider identifier (mirrors
 *                               {@link PersistenceProvider#providerId()}).
 *
 * @since 0.5.0
 * @see PersistenceEngine
 * @see PersistenceProvider
 */
public record PersistenceEngineCapabilities(
        boolean supportsNativeProtocol,
        boolean supportsZeroCopyRows,
        boolean supportsIoUring,
        boolean supportsPerTenantPools,
        String  transportName,
        String  providerId
) {

    // CHECKSTYLE.OFF: DeclarationOrder — static constants in records must follow components list
    /**
     * Pre-built capabilities descriptor for the <strong>PostgreSQL Community</strong> tier engine.
     *
     * <p>All advanced flags are {@code false}. Transport is blocking TCP.
     * Use this constant in the PostgreSQL community engine implementation
     * (e.g., {@code PostgresCommunityPersistenceEngine}) to avoid repeated object
     * construction.
     *
     * <p>Other persistence providers MUST construct their own
     * {@link PersistenceEngineCapabilities} instances with an engine-specific
     * {@link #providerId()} instead of reusing this constant.
     */
    public static final PersistenceEngineCapabilities POSTGRES_COMMUNITY = new PersistenceEngineCapabilities(
            false, false, false, false,
            "BlockingTCP",
            "postgres-community"
    );

    /**
     * Pre-built capabilities descriptor for the <strong>PostgreSQL Enterprise</strong> tier engine.
     *
     * <p>All advanced flags are {@code true}. Transport is io_uring.
     * The PostgreSQL enterprise engine returns this constant from its
     * {@code capabilities()} method.
     *
     * <p>Other persistence providers MUST construct their own
     * {@link PersistenceEngineCapabilities} instances with an engine-specific
     * {@link #providerId()} instead of reusing this constant.
     */
    public static final PersistenceEngineCapabilities POSTGRES_ENTERPRISE = new PersistenceEngineCapabilities(
            true, true, true, true,
            "io_uring/multishot",
            "postgres-enterprise"
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

