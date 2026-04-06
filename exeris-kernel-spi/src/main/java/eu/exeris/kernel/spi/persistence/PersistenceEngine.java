/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 *   <tr><td>{@link #healthCheckDetailed()}</td><td>JDBC ping</td><td>Native SELECT 1</td></tr>
 * </table>
 *
 * @see PersistenceProvider
 * @see PersistenceConnection
 * @since 0.5.0
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
     * {@link #openConnection()} and issue a parameterised {@code SET LOCAL exeris.tenant_id}
     * command using the isolation key from the context.
     *
     * @param storageContext the tenant-isolation descriptor resolved by the Security edge;
     *                       never {@code null}
     * @return pooled connection configured for the given isolation context
     * @throws PersistenceProviderException if a connection cannot be obtained
     */
    PersistenceConnection openConnection(StorageContext storageContext);


    /**
     * Performs a lightweight health check and returns a structured result.
     *
     * <p>Measures the round-trip latency of a {@code SELECT 1} (or equivalent) query
     * and emits a JFR health event. Returns a {@link PersistenceHealthStatus} record
     * with {@code healthy}, {@code message}, and {@code latencyNanos} fields.
     *
     * <p>Implementations MUST override this method to provide accurate latency
     * measurement via a {@code SELECT 1} (or equivalent) round-trip query.
     *
     * @return structured health status; never {@code null}
     * @since 0.5.0
     */
    PersistenceHealthStatus healthCheckDetailed();

    /**
     * Returns the immutable capability descriptor for this engine instance.
     *
     * <p>Used by {@code KernelBootstrap} to populate JFR bootstrap events and
     * emit operator warnings (e.g., when native protocol is unavailable).
     * TCK tests use this to gate zero-alloc assertions against the Community tier.
     *
     * <p>Implementations SHOULD return a pre-built constant (e.g.,
     * {@link PersistenceEngineCapabilities#DEFAULT}) — not a freshly allocated
     * record on every call.
     *
     * @return immutable capabilities descriptor; never {@code null}
     * @since 0.5.0
     */
    PersistenceEngineCapabilities capabilities();

    /**
     * Registers a {@link ConnectionInterceptor} to be invoked on every connection
     * checkout from {@link #openConnection(eu.exeris.kernel.spi.security.StorageContext)}.
     *
     * <h2>Registration Contract</h2>
     * <p>This method MUST be called during bootstrap, before the engine is bound
     * into {@link eu.exeris.kernel.spi.context.KernelProviders#PERSISTENCE_ENGINE}.
     * Calling it after the engine is live is an error — implementations MAY throw
     * {@link IllegalStateException} if called after the first connection is opened.
     *
     * <p>Interceptors are invoked in registration order.
     *
     * @param interceptor the interceptor to add; must not be {@code null}
     * @throws NullPointerException  if {@code interceptor} is {@code null}
     * @throws IllegalStateException if called after the first connection has been opened
     * @since 0.5.0
     */
    void registerInterceptor(ConnectionInterceptor interceptor);

    /**
     * Returns engine-level statistics for monitoring.
     *
     * @return immutable snapshot of pool and connection metrics
     */
    EngineStats stats();

    /**
     * Query whether this engine can service a new request without thread starvation.
     *
     * <p>This method is called by HTTP dispatcher to implement admission control and prevent
     * thread park storms when the underlying connection pool is saturated. Implementations
     * MUST return {@code false} (or throw) immediately if the pool cannot accept new work
     * within the No Waste Compute latency bound (≤5ms p50).
     *
     * <p>Per ADR-010, this is a SPI-level gating mechanism to preserve fairness and prevent
     * cascading thread starvation (ThreadPark storms) observed when HTTP sender is faster than
     * persistence layer can consume.
     *
     * <p><b>Semantics (Tier-specific)</b>:
     * <ul>
     *   <li><b>Community</b>: Returns {@code false} if idle connections are zero and at least one
     *       acquire is pending (queue forming), or if active connections are at or above 90% of
     *       the configured maximum. Returns {@code true} otherwise.
     *   <li><b>Enterprise</b>: Implements exponential backoff with native driver telemetry
     *       (e.g., io_uring SQE availability). May return {@code false} predictively before
     *       absolute saturation to maintain fairness.
     * </ul>
     *
     * @return {@code true} if this engine can accept a new request without violating
     *         No Waste Compute latency guarantees; {@code false} if pool is saturated or
     *         admission would cause unacceptable latency.
     * @throws PersistenceProviderException if engine is shutting down or in error state
     *
     * @see EngineStats#activeConnections()
     */
    boolean canServiceRequest();

    /**
     * Shuts down the engine, draining all pools and releasing native resources.
     *
     * <p>After this call, connection-opening methods and state-changing operations may
     * reject work, typically by throwing {@link IllegalStateException} or a
     * provider-specific exception.
     *
     * <p>This contract does not require all methods to throw after shutdown.
     * Admission probes such as {@link #canServiceRequest()} may instead return
     * {@code false} to report that the engine can no longer accept work.
     * Idempotent — multiple calls are safe.
     *
     */
    @Override
    void close();
}
