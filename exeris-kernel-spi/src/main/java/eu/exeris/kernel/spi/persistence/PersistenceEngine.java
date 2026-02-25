/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.persistence;

import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.security.StorageContext;

/**
 * SPI: Manages {@link PersistenceConnection} lifecycle, pooling, and query execution.
 *
 * <h2>Responsibility</h2>
 * <p>A {@code PersistenceEngine} is the central orchestrator created once during bootstrap.
 * It owns:
 * <ul>
 *   <li>Connection pool(s) — shared and per-tenant</li>
 *   <li>Transport lifecycle (blocking TCP / io_uring)</li>
 *   <li>Auth provider for SCRAM/SASL handshakes</li>
 *   <li>Health checks and pool monitoring</li>
 * </ul>
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>This interface does NOT reference {@code HikariCP}, {@code NativePgConnectionPool},
 * {@code io_uring}, or any driver-specific class. It operates purely on SPI contracts.
 *
 * <h2>Thread Safety</h2>
 * <p>The engine itself is thread-safe and shared across all virtual threads.
 * Individual {@link PersistenceConnection} instances are NOT thread-safe — one per VT.
 *
 * <h2>Tier Behaviour</h2>
 * <table>
 *   <tr><th>Method</th><th>Community</th><th>Enterprise</th></tr>
 *   <tr><td>{@link #openConnection()}</td><td>JDBC from HikariCP</td><td>PG wire protocol from slab pool</td></tr>
 *   <tr><td>{@link #openConnection(StorageContext)}</td><td>Per-tenant HikariCP</td><td>Per-tenant RLS pool</td></tr>
 *   <tr><td>{@link #healthCheck()}</td><td>JDBC ping</td><td>Native SELECT 1</td></tr>
 * </table>
 *
 * @since 0.5.0
 * @see PersistenceProvider
 * @see PersistenceConnection
 */
public interface PersistenceEngine extends AutoCloseable {

    /**
     * Opens a new connection from the shared (default) pool.
     *
     * <p>The returned connection MUST be closed via try-with-resources.
     * Closing returns the underlying resource to the pool.
     *
     * @return pooled connection; caller must close
     * @throws PersistenceProviderException if a connection cannot be obtained
     */
    PersistenceConnection openConnection();

    /**
     * Opens a connection from the pool for the given isolation context.
     *
     * <p>This method is <b>identity-blind</b> — it does not accept a raw tenant ID
     * string. The caller must provide a fully-resolved {@link StorageContext} as
     * produced by the Security subsystem during token validation. The engine uses the
     * context's {@link StorageContext#strategy()} and {@link StorageContext#isolationKey()}
     * to route to the appropriate pool and inject RLS/schema parameters without any
     * knowledge of what the isolation key means at the business level.
     *
     * <p>If per-tenant pooling is disabled, this method MAY delegate to
     * {@link #openConnection()} and issue a parameterised {@code SET app.tenant_id}
     * command using the isolation key from the context.
     *
     * @param storageContext the tenant-isolation descriptor resolved by the Security edge;
     *                       never {@code null}
     * @return pooled connection configured for the given isolation context
     * @throws PersistenceProviderException if a connection cannot be obtained
     */
    PersistenceConnection openConnection(StorageContext storageContext);

    /**
     * Performs a lightweight health check against the backing database.
     *
     * @return {@code true} if the database is reachable and responsive
     */
    boolean healthCheck();

    /**
     * Returns engine-level statistics for monitoring.
     *
     * @return immutable snapshot of pool and connection metrics
     */
    EngineStats stats();

    /**
     * Shuts down the engine, draining all pools and releasing native resources.
     *
     * <p>After this call, all methods throw {@link IllegalStateException}.
     * Idempotent — multiple calls are safe.
     */
    @Override
    void close();
}

