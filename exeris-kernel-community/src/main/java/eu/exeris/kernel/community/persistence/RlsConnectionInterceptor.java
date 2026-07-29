/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.persistence.ConnectionInterceptor;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceStatement;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.kernel.spi.security.StorageContext;

/**
 * Community: Row-Level Security injector via {@link ConnectionInterceptor} SPI.
 *
 * <h2>Replaces Legacy {@code RlsDataSource} / {@code RlsConnectionCustomizer}</h2>
 * <p>The legacy stack used {@code ThreadLocal<UUID>} (BANNED — JEP 506 violation)
 * to propagate the tenant ID. This interceptor reads the isolation key from
 * {@link eu.exeris.kernel.spi.context.KernelProviders#STORAGE_CONTEXT} — a {@code ScopedValue} that is
 * correctly inherited by all virtual threads in the scope.
 *
 * <h2>Isolation Strategy Routing</h2>
 * <table>
 *   <tr><th>Strategy</th><th>SQL issued</th></tr>
 *   <tr><td>{@link StorageContext.IsolationStrategy#SHARED}</td>
 *       <td>{@code set_config('exeris.tenant_id', $1, false)} and
 *           {@code set_config('exeris.shared_scope', $2, false)} in one statement</td></tr>
 *   <tr><td>{@link StorageContext.IsolationStrategy#SEPARATED_SCHEMA}</td>
 *       <td>{@code SET search_path TO &lt;schemaName&gt;, public}, then the shared-scope setting</td></tr>
 *   <tr><td>{@link StorageContext.IsolationStrategy#DEDICATED}</td>
 *       <td>Routing is handled at the pool level by the engine; only the shared-scope setting is issued</td></tr>
 * </table>
 *
 * <h2>Shared Scope — Row Visibility (ADR-012 §4b)</h2>
 * <p>{@code exeris.shared_scope} is published alongside the tenant key on every strategy, because
 * row-visibility is orthogonal to physical placement. The interceptor only <em>publishes</em> the
 * setting; whether reads actually widen is decided by the deployment's own RLS policy, which the kernel
 * does not ship and cannot introspect. A conforming policy widens the read predicate and leaves the write
 * predicate pinned to the owner:
 *
 * <pre>{@code
 * CREATE POLICY tenant_isolation ON <table>
 *   USING (tenant_id = current_setting('exeris.tenant_id', true)
 *          OR (NULLIF(current_setting('exeris.shared_scope', true), '') IS NOT NULL
 *              AND shared_scope = current_setting('exeris.shared_scope', true)))
 *   WITH CHECK (tenant_id = current_setting('exeris.tenant_id', true));
 * }</pre>
 *
 * <p>Note that {@code WITH CHECK} is unchanged from the tenant-private policy — owner-scoped write is
 * what the existing clause already expresses, so widening reads does not require relaxing writes. A
 * tenant can read its partition-mates' rows and still only ever write its own.
 *
 * <h2>The Agnostic Data Principle</h2>
 * <p>This interceptor is <b>identity-blind</b> — it never imports
 * {@code SecurityContext}, {@code TenantContext}, or any business-level
 * principal. It only reads the opaque {@code isolationKey()} from the
 * already-resolved {@link StorageContext}.
 *
 * <h2>SPI Compliance (The Wall)</h2>
 * <p>No reference to JDBC, HikariCP, io_uring, or any Enterprise class.
 * Operates purely through {@link PersistenceConnection#prepare(String)} and
 * {@link PersistenceStatement}.
 *
 * <h2>VT Safety</h2>
 * <p>Uses {@link eu.exeris.kernel.spi.context.KernelProviders#STORAGE_CONTEXT} (ScopedValue, JEP 506) —
 * zero {@code ThreadLocal}, zero VT pinning risk.
 *
 * @since 0.5.0
 * @see ConnectionInterceptor
 * @see StorageContext
 * @see eu.exeris.kernel.spi.context.KernelProviders#STORAGE_CONTEXT
 */
public final class RlsConnectionInterceptor implements ConnectionInterceptor {

    /**
     * Singleton instance — this interceptor is stateless and can be shared
     * across all virtual threads. Register once during bootstrap.
     */
    public static final RlsConnectionInterceptor INSTANCE = new RlsConnectionInterceptor();

    /**
     * SQL for SHARED strategy — injects tenant ID as a session-level variable.
     * PostgreSQL RLS policies read {@code current_setting('exeris.tenant_id')}.
     *
     * <p>Uses parameterised bind to prevent SQL injection (isolationKey is untrusted data).
     */
    private static final String SQL_SET_TENANT =
            "SELECT set_config('exeris.tenant_id', ?, false), set_config('exeris.shared_scope', ?, false)";

    /**
     * Shared-scope publication for the strategies whose own injection cannot carry it
     * ({@code SEPARATED_SCHEMA}, {@code DEDICATED}). One extra round-trip; see
     * {@link #injectSharedScope} for why it is unconditional.
     */
    private static final String SQL_SET_SHARED_SCOPE =
            "SELECT set_config('exeris.shared_scope', ?, false)";

    private static final String SQL_SET_SCHEMA_PREFIX = "SET search_path TO ";
    private static final String SQL_SET_SCHEMA_SUFFIX = ", public";

    private static final String INTERCEPTOR_NAME = "RlsConnectionInterceptor";
    private static final String ISOLATION_KEY_NONE = "[none]";

    private RlsConnectionInterceptor() {
        // singleton — use INSTANCE
    }

    /**
     * Injects the isolation key from the current {@link eu.exeris.kernel.spi.context.KernelProviders#STORAGE_CONTEXT}
     * into the database connection before it is returned to the caller.
     *
     * <p>O(1) per invocation. SHARED costs one round-trip (tenant key and shared scope are published by
     * the same statement). SEPARATED_SCHEMA and DEDICATED each cost one more than before this contract
     * gained a shared scope: the setting is orthogonal to physical placement, so it cannot ride their
     * strategy-specific injection, and it cannot be skipped when absent without leaving a stale value on
     * a pooled connection — see {@link #injectSharedScope}.
     *
     * @param connection     the freshly acquired connection; must not be closed
     * @param storageContext the isolation descriptor resolved by the Security edge
     * @throws PersistenceProviderException with {@code EX-PERS-5006} if RLS injection fails
     */
    @Override
    public void onConnectionAcquired(PersistenceConnection connection,
                                     StorageContext storageContext) {
        StorageContext.IsolationStrategy strategy = storageContext.strategy();

        switch (strategy) {
            // SHARED publishes both settings in one round-trip — the statement already existed.
            case SHARED           -> injectTenantId(connection, storageContext);
            case SEPARATED_SCHEMA -> {
                injectSchemaPath(connection, storageContext);
                injectSharedScope(connection, storageContext);
            }
            // Pool-level routing still needs no tenant injection, but the shared-scope setting is
            // orthogonal to placement and must be published (or cleared) here too.
            case DEDICATED        -> injectSharedScope(connection, storageContext);
        }
    }

    // =========================================================================
    // Private injection helpers
    // =========================================================================

    private static void injectTenantId(PersistenceConnection connection,
                                       StorageContext storageContext) {
        String isolationKey = storageContext.isolationKey().orElse(null);
        // For global/system context use "" to clear any prior tenant from the pooled connection.
        // set_config(..., false) is session-level, so prior tenant values survive pool recycle.
        String effectiveKey = (isolationKey == null || isolationKey.isBlank()) ? "" : isolationKey;
        executeSetConfig(connection, SQL_SET_TENANT, effectiveKey,
                effectiveKey, effectiveSharedScope(storageContext));
    }

    /**
     * Runs a {@code set_config} statement, binding {@code values} positionally.
     *
     * <p>Shared by the tenant and shared-scope paths — the prepare/bind/execute/translate skeleton is
     * identical and only the statement and its bindings differ.
     *
     * @param diagnosticKey the isolation key reported in a failure, never a claim or scope value
     */
    private static void executeSetConfig(PersistenceConnection connection,
                                         String sql,
                                         String diagnosticKey,
                                         String... values) {
        try (PersistenceStatement stmt = connection.prepare(sql)) {
            for (int i = 0; i < values.length; i++) {
                stmt.bindString(i, values[i]);
            }
            try (QueryResult _ = stmt.executeQuery()) {
                // set_config() returns a single row; consume and discard
            }
        } catch (PersistenceProviderException ppe) {
            throw PersistenceProviderException.interceptorInitFailed(INTERCEPTOR_NAME, diagnosticKey, ppe);
        }
    }

    /**
     * Publishes {@code exeris.shared_scope} for the strategies whose own injection cannot carry it.
     *
     * <p><b>Unconditional by design.</b> It would be cheaper to skip the round-trip when the context
     * declares no shared scope, and that would be a fail-open bug: {@code set_config(..., false)} is
     * <em>session</em>-scoped, so on a pooled connection the previous request's shared scope survives
     * into the next one. A request that never asked to participate in a shared partition would then have
     * its reads widened into it — the same pool-recycle hazard the tenant key already guards against by
     * always writing a value. Absence must be published as {@code ""}, not left unpublished.
     */
    private static void injectSharedScope(PersistenceConnection connection,
                                          StorageContext storageContext) {
        executeSetConfig(connection, SQL_SET_SHARED_SCOPE,
                storageContext.isolationKey().orElse(ISOLATION_KEY_NONE),
                effectiveSharedScope(storageContext));
    }

    /**
     * The shared-scope value to publish: the declared partition, or {@code ""} to clear a stale one
     * left on a recycled connection. Never {@code null}.
     */
    private static String effectiveSharedScope(StorageContext storageContext) {
        String sharedScope = storageContext.sharedScopeKey().orElse(null);
        return (sharedScope == null || sharedScope.isBlank()) ? "" : sharedScope;
    }

    private static void injectSchemaPath(PersistenceConnection connection,
                                         StorageContext storageContext) {
        String schemaName = storageContext.schemaName().orElse(null);
        if (schemaName == null || schemaName.isBlank()) {
            throw PersistenceProviderException.interceptorInitFailed(
                    INTERCEPTOR_NAME,
                    storageContext.isolationKey().orElse(ISOLATION_KEY_NONE),
                    null);
        }
        if (!PostgresIdentifier.isSafe(schemaName)) {
            throw PersistenceProviderException.interceptorInitFailed(
                    INTERCEPTOR_NAME,
                    storageContext.isolationKey().orElse(ISOLATION_KEY_NONE),
                    null);
        }
        String sql = SQL_SET_SCHEMA_PREFIX + schemaName + SQL_SET_SCHEMA_SUFFIX;
        try {
            connection.executeUpdate(sql);
        } catch (PersistenceProviderException ppe) {
            throw PersistenceProviderException.interceptorInitFailed(
                    INTERCEPTOR_NAME,
                    storageContext.isolationKey().orElse(ISOLATION_KEY_NONE),
                    ppe);
        }
    }


}
