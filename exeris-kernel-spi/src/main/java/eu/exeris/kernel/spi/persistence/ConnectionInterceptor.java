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
