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
 *       <td>{@code SELECT set_config('exeris.tenant_id', $1, false)} (session-level)</td></tr>
 *   <tr><td>{@link StorageContext.IsolationStrategy#SEPARATED_SCHEMA}</td>
 *       <td>{@code SET search_path TO &lt;schemaName&gt;, public} (session-level)</td></tr>
 *   <tr><td>{@link StorageContext.IsolationStrategy#DEDICATED}</td>
 *       <td>No-op — routing is handled at the pool level by the engine</td></tr>
 * </table>
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
            "SELECT set_config('exeris.tenant_id', ?, false)";

    private static final String SQL_SET_SCHEMA_PREFIX = "SET search_path TO ";
    private static final String SQL_SET_SCHEMA_SUFFIX = ", public";
    private static final int MAX_IDENTIFIER_LENGTH = 63;

    private static final String INTERCEPTOR_NAME = "RlsConnectionInterceptor";

    private RlsConnectionInterceptor() {
        // singleton — use INSTANCE
    }

    /**
     * Injects the isolation key from the current {@link eu.exeris.kernel.spi.context.KernelProviders#STORAGE_CONTEXT}
     * into the database connection before it is returned to the caller.
     *
     * <p>O(1) per invocation — one SQL round-trip for SHARED/SEPARATED_SCHEMA strategy,
     * zero SQL for DEDICATED strategy.
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
            case SHARED           -> injectTenantId(connection, storageContext);
            case SEPARATED_SCHEMA -> injectSchemaPath(connection, storageContext);
            case DEDICATED        -> { /* no-op — routing already handled at pool level */ }
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
        try (PersistenceStatement stmt = connection.prepare(SQL_SET_TENANT);
             QueryResult _ = stmt.bindString(0, effectiveKey).executeQuery()) {
            // set_config() returns a single row; consume and discard
        } catch (PersistenceProviderException ppe) {
            throw PersistenceProviderException.interceptorInitFailed(
                    INTERCEPTOR_NAME,
                    effectiveKey,
                    ppe);
        }
    }

    private static void injectSchemaPath(PersistenceConnection connection,
                                         StorageContext storageContext) {
        String schemaName = storageContext.schemaName().orElse(null);
        if (schemaName == null || schemaName.isBlank()) {
            throw PersistenceProviderException.interceptorInitFailed(
                    INTERCEPTOR_NAME,
                    storageContext.isolationKey().orElse("[none]"),
                    null);
        }
        if (!isSafeIdentifier(schemaName)) {
            throw PersistenceProviderException.interceptorInitFailed(
                    INTERCEPTOR_NAME,
                    storageContext.isolationKey().orElse("[none]"),
                    null);
        }
        String sql = SQL_SET_SCHEMA_PREFIX + schemaName + SQL_SET_SCHEMA_SUFFIX;
        try {
            connection.executeUpdate(sql);
        } catch (PersistenceProviderException ppe) {
            throw PersistenceProviderException.interceptorInitFailed(
                    INTERCEPTOR_NAME,
                    storageContext.isolationKey().orElse("[none]"),
                    ppe);
        }
    }

    private static boolean isSafeIdentifier(String schemaName) {
        int length = schemaName.length();
        if (length < 1 || length > MAX_IDENTIFIER_LENGTH) {
            return false;
        }
        char first = schemaName.charAt(0);
        if (first < 'a' || first > 'z') {
            return false;
        }
        for (int i = 1; i < length; i++) {
            char currentChar = schemaName.charAt(i);
            boolean isLowercase = currentChar >= 'a' && currentChar <= 'z';
            boolean isDigit = currentChar >= '0' && currentChar <= '9';
            if (!isLowercase && !isDigit && currentChar != '_') {
                return false;
            }
        }
        return true;
    }

}
