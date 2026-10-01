/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.persistence;

import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.security.StorageContext;

/**
 * SPI: Pluggable interceptor for connection initialisation — the "Plug-and-Play Isolation" hook.
 *
 * <h2>The Agnostic Data Principle</h2>
 * <p>This interface is the <b>only</b> bridge between the Persistence and Security subsystems.
 * The Persistence Core registers interceptors (e.g., an RLS injector, a schema switcher)
 * that are invoked on every connection checkout. The engine itself remains identity-blind —
 * it does not know what the isolation key means at the business level.
 *
 * <h2>Isolation Strategies</h2>
 * <table>
 *   <caption>What each isolation strategy adds on connection acquisition</caption>
 *   <tr><th>Strategy</th><th>Interceptor action</th></tr>
 *   <tr><td>SHARED (RLS)</td>
 *       <td>{@code SET LOCAL exeris.tenant_id = ?} via parameterised statement</td></tr>
 *   <tr><td>SEPARATED_SCHEMA</td>
 *       <td>{@code SET search_path TO [schema]}</td></tr>
 *   <tr><td>DEDICATED</td>
 *       <td>No-op — routing handled at pool level by {@link PersistenceEngine}</td></tr>
 * </table>
 *
 * <h2>Registration</h2>
 * <p>Interceptors are registered by the {@code exeris-kernel-core} bootstrapper into
 * the {@code ConnectionInterceptorRegistry}. The Persistence engine calls each registered
 * interceptor in order during {@link PersistenceEngine#openConnection(StorageContext)}.
 *
 * <p><b>Thread confinement:</b> owner thread — an interceptor runs on the thread that
 * acquired the connection, before that connection is handed to the caller.
 * <p><b>Ownership:</b> the engine owns the connection passed to
 * {@link #onConnectionAcquired}; an interceptor MUST NOT close it, and the engine discards
 * rather than pools a connection whose interceptor threw.
 *
 * @implSpec Implementations MUST NOT import any JDBC, HikariCP, or driver-specific class —
 *           they operate purely via {@link PersistenceConnection} and {@link StorageContext}.
 *           When initialisation fails (for example, the {@code SET} command is rejected by
 *           the database), an implementation MUST throw {@link PersistenceProviderException}
 *           with error code {@value eu.exeris.kernel.spi.exceptions.KernelErrorCodes#EX_PERS_5006};
 *           the engine then discards the connection and propagates the exception to the caller.
 * @since 0.5
 * @see PersistenceEngine#openConnection(StorageContext)
 * @see PersistenceConnection
 * @see StorageContext
 */
@FunctionalInterface
public interface ConnectionInterceptor {

    /**
     * PostgreSQL session setting carrying the isolation key of the current request scope, published
     * on connection acquisition and read by a deployment's RLS policy as
     * {@code current_setting('exeris.tenant_id', true)}.
     *
     * <p><b>Why this is a constant.</b> The name is a contract between code the kernel ships and SQL
     * the kernel does not — the policy lives in the deployment's own migrations, and the kernel
     * cannot introspect it. A policy naming a key the runtime never publishes fails in the worst
     * possible way: every read returns zero rows and every write is refused, with nothing pointing at
     * the characters responsible.
     *
     * @apiNote A generator or a migration tool emitting the policy SQL should reference this constant
     *          rather than retyping the string, so that the two sides cannot drift apart silently.
     * @since 0.12
     */
    String SESSION_KEY_TENANT_ID = "exeris.tenant_id";

    /**
     * PostgreSQL session setting carrying the shared scope of the current request scope (ADR-012
     * §4b), published alongside {@link #SESSION_KEY_TENANT_ID} on every isolation strategy so a
     * policy can widen reads while keeping writes pinned to the owner.
     *
     * <p>Published unconditionally — as the empty string when the context declares no scope —
     * because a session-scoped setting survives connection reuse, so a key left unpublished is a
     * key inherited from the previous borrower.
     *
     * @since 0.12
     */
    String SESSION_KEY_SHARED_SCOPE = "exeris.shared_scope";

    /**
     * Invoked immediately after a connection is checked out from the pool and before
     * it is returned to the caller.
     *
     * <p><b>Example — RLS injection (SHARED strategy):</b>
     * {@snippet lang="java" :
     * StorageContext sc = KernelProviders.STORAGE_CONTEXT.get();
     * sc.isolationKey().ifPresent(key -> {
     *     try (PersistenceStatement stmt =
     *              connection.prepare("SET LOCAL exeris.tenant_id = $1")) {
     *         stmt.bindString(0, key).executeUpdate();
     *     }
     * });
     * }
     *
     * @param connection     the freshly acquired connection; MUST NOT be closed by the interceptor
     * @param storageContext the isolation descriptor for the current request scope;
     *                       MUST NOT be {@code null}
     * @throws PersistenceProviderException {@value eu.exeris.kernel.spi.exceptions.KernelErrorCodes#EX_PERS_5006}
     *                                      if the connection cannot be initialised for the given context
     * @implNote The reference Community RLS interceptor completes in O(1) round trips per
     *           strategy and performs no I/O beyond the session-key/schema-setting SQL; other
     *           implementations should aim for the same shape, but neither that timing bound nor
     *           idempotency is enforced by the TCK.
     */
    void onConnectionAcquired(PersistenceConnection connection, StorageContext storageContext);
}
