/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * <h2>Tier Behaviour</h2>
 * <table>
 *   <caption>How each tier satisfies the engine's connection and health contracts</caption>
 *   <tr><th>Method</th><th>Community</th><th>Enterprise</th></tr>
 *   <tr><td>{@link #openConnection()}</td><td>JDBC from HikariCP</td><td>PG wire protocol from slab pool</td></tr>
 *   <tr><td>{@link #openConnection(StorageContext)}</td><td>Per-tenant HikariCP</td><td>Per-tenant RLS pool</td></tr>
 *   <tr><td>{@link #healthCheckDetailed()}</td><td>JDBC ping</td><td>Native SELECT 1</td></tr>
 * </table>
 *
 * <p><b>Allocation:</b> allocates (one {@link PersistenceConnection} handle per
 * {@code openConnection}, one {@link EngineStats} per {@link #stats()} and one
 * {@link PersistenceHealthStatus} per {@link #healthCheckDetailed()} — monitoring and
 * checkout paths, not the per-row hot path).
 * <p><b>Thread confinement:</b> any thread — the engine is thread-safe and shared across all
 * virtual threads; the {@link PersistenceConnection} instances it hands out are not, and each
 * belongs to exactly one thread.
 * <p><b>Ownership:</b> the caller of {@code openConnection} closes the returned connection,
 * which returns it to the pool; whoever created the engine closes it via {@link #close()},
 * draining every pool.
 *
 * @since 0.5
 * @see PersistenceProvider
 * @see PersistenceConnection
 */
public interface PersistenceEngine extends AutoCloseable {

    /**
     * Opens a new connection under the ambient {@link StorageContext}.
     *
     * <p>Closing the returned connection returns the underlying resource to the pool.
     *
     * @return pooled connection, configured for the ambient isolation context; caller must close
     * @throws PersistenceProviderException
     *         {@value eu.exeris.kernel.spi.exceptions.KernelErrorCodes#EX_PERS_5002} when no
     *         connection becomes available within the configured acquisition timeout, or
     *         {@value eu.exeris.kernel.spi.exceptions.KernelErrorCodes#EX_PERS_5006} when a
     *         registered {@link ConnectionInterceptor} fails to configure the connection
     * @implSpec <b>Isolation obligation (since 0.12).</b> An implementation MUST resolve the
     *           caller's ambient storage context — {@code KernelProviders.storageContextOrSystem()}
     *           — and configure the connection for it exactly as
     *           {@link #openConnection(StorageContext)} would. With no context bound that is the
     *           system context, whose isolation key is absent, and the result is the shared
     *           default pool.
     *           <p>The obligation is not cosmetic, and returning an untouched pooled connection
     *           does not satisfy it. Session-scoped settings survive checkin, so a connection that
     *           skips isolation setup carries the <em>previous borrower's</em>: an implementation
     *           treating this overload as "no context" hands one tenant a session configured for
     *           another. The same rule keeps one request on one connection — an implementation
     *           that keys a per-request session differently here than in the context overload
     *           forces a second acquire per request.
     * @apiNote Close the connection through try-with-resources; on a route that declares itself
     *          long-running the handle is owning, so a missed close is a pool leak rather than a
     *          no-op.
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
     * @param storageContext the tenant-isolation descriptor resolved by the Security edge;
     *                       never {@code null}
     * @return pooled connection configured for the given isolation context
     * @throws PersistenceProviderException
     *         {@value eu.exeris.kernel.spi.exceptions.KernelErrorCodes#EX_PERS_5002} when no
     *         connection becomes available within the configured acquisition timeout, or
     *         {@value eu.exeris.kernel.spi.exceptions.KernelErrorCodes#EX_PERS_5006} when a
     *         registered {@link ConnectionInterceptor} fails, or when a
     *         {@link StorageContext.IsolationStrategy#DEDICATED} context names a datasource key
     *         absent from {@link PersistenceConfig#dedicatedDataSources()}
     * @implSpec With per-tenant pooling disabled, an implementation MAY delegate to
     *           {@link #openConnection()} and issue a parameterised
     *           {@code SET LOCAL exeris.tenant_id} command using the isolation key from the
     *           context. Whichever route it takes, the session keys named on
     *           {@link ConnectionInterceptor} are published on every strategy, so that a
     *           connection never reaches a caller carrying the previous borrower's scope.
     */
    PersistenceConnection openConnection(StorageContext storageContext);


    /**
     * Performs a lightweight health check and returns a structured result.
     *
     * <p>Measures the round-trip latency of a {@code SELECT 1} (or equivalent) query
     * and emits a JFR health event.
     *
     * @return structured health status carrying the liveness verdict, a failure reason when
     *         unhealthy, and the measured round-trip latency in nanoseconds; never {@code null}
     * @implSpec An implementation MUST measure the latency it reports with a real
     *           {@code SELECT 1} (or equivalent) round trip rather than returning a constant.
     * @apiNote This is a cold-path probe — one record is allocated per invocation. Call it from
     *          a health endpoint or a monitoring task, never per request.
     * @since 0.5
     */
    PersistenceHealthStatus healthCheckDetailed();

    /**
     * Registers a {@link ConnectionInterceptor} to be invoked on every connection
     * checkout from {@link #openConnection(eu.exeris.kernel.spi.security.StorageContext)}.
     *
     * @param interceptor the interceptor to add; must not be {@code null}
     * @throws NullPointerException  if {@code interceptor} is {@code null}
     * @throws IllegalStateException if called after the first connection has been opened
     * @implSpec Interceptors are invoked in registration order. An implementation MAY reject
     *           registration with {@link IllegalStateException} once the first connection has
     *           been opened, since a registry that changes under a live pool would apply a
     *           different isolation setup to connections drawn from the same pool.
     * @apiNote Call this during bootstrap, before the engine is bound into
     *          {@link eu.exeris.kernel.spi.context.KernelProviders#PERSISTENCE_ENGINE};
     *          registering against a live engine is an error.
     * @since 0.5
     */
    void registerInterceptor(ConnectionInterceptor interceptor);

    /**
     * Returns a point-in-time snapshot of pool occupancy and lifetime connection counters.
     *
     * @return immutable snapshot of pool occupancy and lifetime counters, taken at the moment of
     *         the call; never {@code null}
     */
    EngineStats stats();

    /**
     * Answers whether this engine can take on one more request without the caller risking an
     * unbounded wait for a connection.
     *
     * <p>The HTTP dispatcher calls this before binding a request session, and answers a refusal
     * with {@code 503 Service Unavailable} and {@code Retry-After: 1}. The <em>exact</em> shed
     * trigger is implementation- and configuration-defined: shedding on a forming queue is a
     * tunable tier policy, not a universal contract (ADR-035).
     *
     * @return {@code true} if this engine can accept a new request without violating
     *         No Waste Compute latency guarantees; {@code false} if the pool is saturated or
     *         admission would cause unacceptable latency
     * @throws PersistenceProviderException if the engine is shutting down or in an error state
     * @implSpec Three invariants are required of every tier: available capacity admits; a closed
     *           engine sheds — by returning {@code false} or by throwing; and the decision is
     *           non-blocking and consistent for an unchanged engine state. Beyond those, an
     *           implementation SHOULD shed when admitting new work would exceed the No Waste
     *           Compute latency bound (≤5 ms p50), that is, under a queue deep enough that the
     *           expected acquire wait is no longer bounded, and SHOULD keep the probe
     *           allocation-light, since it sits on the request path.
     * @implNote The Community engine admits while pending acquires stay within a pool-size-scaled
     *           allowance ({@code persistence.admission.queueDepthAllowanceRatio}) and sheds once
     *           the queue exceeds it, so a full pool with a shallow queue is admitted and the
     *           request queues briefly on the connection-acquire path; an allowance ratio of
     *           {@code 0} selects the strict "shed on first queued acquire / ≥90% saturation"
     *           machine instead. The Enterprise engine backs off exponentially against native
     *           driver telemetry (io_uring SQE availability, for instance) and may shed
     *           predictively, before absolute saturation, to hold fairness. The Community engine
     *           also allocates one short-lived snapshot record on every call to this method;
     *           that allocation is expected, but not JFR-verified, to be scalarised away by
     *           escape analysis rather than guaranteed as zero heap allocation across tiers.
     * @see EngineStats#activeConnections()
     */
    boolean canServiceRequest();

    /**
     * Shuts down the engine, draining all pools and releasing native resources.
     *
     * <p>After this call, connection-opening methods and state-changing operations may
     * reject work, typically by throwing {@link IllegalStateException} or a
     * provider-specific exception. Idempotent — multiple calls are safe.
     *
     * @implSpec This contract does not require every method to throw after shutdown: an admission
     *           probe such as {@link #canServiceRequest()} may instead return {@code false} to
     *           report that the engine can no longer accept work.
     */
    @Override
    void close();
}
