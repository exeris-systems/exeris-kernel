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
 * <h2>SPI Compliance (The Wall)</h2>
 * <p>Interceptor implementations MUST NOT import any JDBC, HikariCP, or driver-specific
 * classes. They operate purely via {@link PersistenceConnection} and {@link StorageContext}.
 *
 * <h2>Error Handling</h2>
 * <p>If initialisation fails (e.g., the {@code SET} command is rejected by the DB),
 * the interceptor MUST throw {@link PersistenceProviderException} with error code
 * {@code EX-PERS-5006} (Interceptor Initialization Error). The engine will then
 * discard the connection and propagate the exception to the caller.
 *
 * @see PersistenceEngine#openConnection(StorageContext)
 * @see PersistenceConnection
 * @see StorageContext
 * @since 0.5.0
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
     * "cannot introspect" it. Both sides therefore used to spell the string by transcription, with no
     * compiler between them, and a policy naming a key the runtime never publishes fails in the worst
     * possible way: every read returns zero rows and every write is refused, with nothing pointing at
     * the five characters responsible. A downstream consumer shipped exactly that (policies reading
     * {@code app.tenant_id}) and only found it once row-level security was actually being enforced.
     * A generator or a migration tool can now reference this instead of retyping it.
     *
     * @since 0.12.0
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
     * @since 0.12.0
     */
    String SESSION_KEY_SHARED_SCOPE = "exeris.shared_scope";

    /**
     * Invoked immediately after a connection is checked out from the pool and before
     * it is returned to the caller.
     *
     * <p>The interceptor MUST be idempotent and MUST complete in O(1) time.
     * It MUST NOT block on external I/O beyond issuing the initialisation SQL.
     *
     * <p><b>Example — RLS injection (SHARED strategy):</b>
     * <pre>{@code
     * StorageContext sc = KernelProviders.STORAGE_CONTEXT.get();
     * sc.isolationKey().ifPresent(key -> {
     *     try (PersistenceStatement stmt =
     *              connection.prepare("SET LOCAL exeris.tenant_id = $1")) {
     *         stmt.bindString(0, key).executeUpdate();
     *     }
     * });
     * }</pre>
     *
     * @param connection     the freshly acquired connection; MUST NOT be closed by the interceptor
     * @param storageContext the isolation descriptor for the current request scope;
     *                       MUST NOT be {@code null}
     * @throws PersistenceProviderException with {@code EX-PERS-5006} if the connection
     *                                      cannot be properly initialised for the given context
     */
    void onConnectionAcquired(PersistenceConnection connection, StorageContext storageContext);
}
