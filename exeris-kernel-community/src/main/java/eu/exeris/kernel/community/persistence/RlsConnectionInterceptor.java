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
 *       <td>{@code SET search_path TO &lt;schemaName&gt;, public}, then the same session-key statement</td></tr>
 *   <tr><td>{@link StorageContext.IsolationStrategy#DEDICATED}</td>
 *       <td>Routing is handled at the pool level by the engine; the session-key statement is issued anyway</td></tr>
 * </table>
 *
 * <p><b>Every strategy publishes both session keys.</b> Physical placement decides which connection a
 * request lands on; it does not decide what the previous borrower left in the session. A strategy that
 * skips {@code exeris.tenant_id} because its own isolation does not depend on it still hands the next
 * request whatever tenant the last one published — see {@link #publishSessionKeys}.
 *
 * <h2>Shared Scope — Row Visibility (ADR-012 §4b)</h2>
 * <p>{@code exeris.shared_scope} is published alongside the tenant key on every strategy, because
 * row-visibility is orthogonal to physical placement. The interceptor only <em>publishes</em> the
 * setting; whether reads actually widen is decided by the deployment's own RLS policy, which the kernel
 * does not ship and cannot introspect. A conforming policy widens the read predicate and leaves the write
 * predicate pinned to the owner:
 *
 * <pre>{@code
 * ALTER TABLE <table> ENABLE ROW LEVEL SECURITY;
 * ALTER TABLE <table> FORCE  ROW LEVEL SECURITY;
 *
 * CREATE POLICY tenant_isolation ON <table>
 *   USING (tenant_id = current_setting('exeris.tenant_id', true)
 *          OR (NULLIF(current_setting('exeris.shared_scope', true), '') IS NOT NULL
 *              AND shared_scope = current_setting('exeris.shared_scope', true)))
 *   WITH CHECK (tenant_id = current_setting('exeris.tenant_id', true));
 * }</pre>
 *
 * <p><b>{@code FORCE} is not optional, and leaving it out fails open.</b> PostgreSQL exempts a table's
 * owner from that table's own policies unless the table is forced. A deployment whose application
 * connects as the role owning its tables — the default in every quick-start — therefore gets a policy
 * that is enabled, listed in {@code pg_policies}, and never applied: no error, no warning, other
 * tenants' rows in every read. The three integration tests that hold this contract
 * ({@code CommunityPersistenceTenantIsolationIT}, {@code CommunityPersistenceSharedScopeIT},
 * {@code CommunityPersistenceIsolationLeakTckIT}) all issue both statements <em>and</em> connect as a
 * non-owner role, so what they verify is the property a forced table has. This snippet used to show
 * only the policy, which is the half that does not enforce anything on its own.
 *
 * <p><b>The comparison above is text-to-text, and that assumption is load-bearing.</b>
 * {@code current_setting} returns {@code text} and the tested schema declares {@code tenant_id TEXT},
 * so an unset key arrives as {@code ''}, matches no row, and fails closed. A deployment whose
 * {@code tenant_id} column is {@code uuid} must cast — and the cast turns that same {@code ''} into
 * {@code invalid input syntax for type uuid: ""} on every query against every scoped table, because
 * {@link #publishSessionKeys} publishes the key unconditionally (as {@code ''} when the context
 * declares no tenant) and a session-scoped setting survives connection reuse. Such a policy needs the
 * empty-string guard on the tenant arm too, the way the shared-scope arm already carries it:
 * {@code tenant_id = NULLIF(current_setting('exeris.tenant_id', true), '')::uuid}.
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
     * Publishes both session keys in one round-trip, on every strategy.
     * PostgreSQL RLS policies read {@code current_setting('exeris.tenant_id')}.
     *
     * <p>Uses parameterised bind to prevent SQL injection (isolationKey is untrusted data).
     */
    private static final String SQL_SET_SESSION_KEYS =
            "SELECT set_config('" + SESSION_KEY_TENANT_ID + "', ?, false), "
                    + "set_config('" + SESSION_KEY_SHARED_SCOPE + "', ?, false)";

    private static final String SQL_SET_SCHEMA_PREFIX = "SET search_path TO ";
    private static final String SQL_SET_SCHEMA_SUFFIX = ", public";

    private static final String SQL_RESET_SEARCH_PATH = "RESET search_path";

    private static final String INTERCEPTOR_NAME = "RlsConnectionInterceptor";
    private static final String ISOLATION_KEY_NONE = "[none]";

    private RlsConnectionInterceptor() {
        // singleton — use INSTANCE
    }

    /**
     * Injects the isolation key from the current {@link eu.exeris.kernel.spi.context.KernelProviders#STORAGE_CONTEXT}
     * into the database connection before it is returned to the caller.
     *
     * <p>O(1) per invocation, two round-trips per strategy. Every strategy publishes both session keys
     * in one statement; they differ only in what they do about {@code search_path}, which is the one
     * thing placement actually decides. Nothing here is conditional on the context declaring a value:
     * every setting is session-scoped, so on a pooled connection an unpublished setting is the previous
     * request's, not a default — see {@link #publishSessionKeys} and {@link #resetSearchPath}.
     *
     * @param connection     the freshly acquired connection; must not be closed
     * @param storageContext the isolation descriptor resolved by the Security edge
     * @throws PersistenceProviderException with {@code EX-PERS-5006} if RLS injection fails
     */
    @Override
    public void onConnectionAcquired(PersistenceConnection connection,
                                     StorageContext storageContext) {
        StorageContext.IsolationStrategy strategy = storageContext.strategy();

        // A switch EXPRESSION, so javac requires every constant to be answered. As a statement it did
        // not: a strategy added later would match no arm and fall straight through, publishing
        // neither session key on a pooled connection that still carries the previous request's — the
        // silent cross-tenant read this class was repaired for, reintroduced by an enum edit
        // elsewhere and reported by nothing.
        //
        // The guarantee survives version skew, which a statement's would not have. IsolationStrategy
        // ships in exeris-kernel-spi and this class in exeris-kernel-community, and the two publish
        // independently — so a newer SPI can hand an older driver a constant it was never compiled
        // against. javac emits a synthetic MatchException throw for an exhaustive enum switch
        // expression (verified in the bytecode, not assumed), so that request fails loudly instead
        // of being served with an unpublished tenant key.
        boolean setsOwnSearchPath = switch (strategy) {
            case SEPARATED_SCHEMA -> true;
            case SHARED, DEDICATED -> false;
        };

        if (setsOwnSearchPath) {
            injectSchemaPath(connection, storageContext);
        }
        publishSessionKeys(connection, storageContext);
        if (!setsOwnSearchPath) {
            // Neither SHARED nor DEDICATED sets search_path, so both must undo whatever a
            // SEPARATED_SCHEMA request left behind.
            resetSearchPath(connection, storageContext);
        }
    }

    // =========================================================================
    // Private injection helpers
    // =========================================================================

    /**
     * Publishes {@code exeris.tenant_id} and {@code exeris.shared_scope}, on every strategy.
     *
     * <p><b>Unconditional in both senses</b> — every strategy runs it, and it always binds a value.
     * {@code set_config(..., false)} is <em>session</em>-scoped, so a key left unpublished on a pooled
     * connection is the previous borrower's, not a default. That is why absence is published as
     * {@code ""} rather than skipped, and it is the same reason the non-SHARED strategies cannot opt
     * out: with {@code persistence.perTenantPooling} at its default of {@code false}, a
     * SEPARATED_SCHEMA or DEDICATED request is served from the pool a SHARED request last used. Its
     * own isolation does not depend on {@code exeris.tenant_id}, but any RLS policy on a table it
     * touches still reads whatever is in the session — which would be the previous tenant's key.
     *
     * <p>Publishing both keys together costs nothing: one {@code set_config} statement carries both,
     * so the strategies that previously issued a scope-only statement issue this one instead. Same
     * round-trip count, one fewer way to be wrong.
     */
    private static void publishSessionKeys(PersistenceConnection connection,
                                           StorageContext storageContext) {
        String isolationKey = storageContext.isolationKey().orElse(null);
        String effectiveKey = (isolationKey == null || isolationKey.isBlank()) ? "" : isolationKey;
        executeSetConfig(connection, SQL_SET_SESSION_KEYS,
                effectiveKey.isEmpty() ? ISOLATION_KEY_NONE : effectiveKey,
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
     * The shared-scope value to publish: the declared partition, or {@code ""} to clear a stale one
     * left on a recycled connection. Never {@code null}.
     */
    private static String effectiveSharedScope(StorageContext storageContext) {
        String sharedScope = storageContext.sharedScopeKey().orElse(null);
        return (sharedScope == null || sharedScope.isBlank()) ? "" : sharedScope;
    }

    /**
     * Returns {@code search_path} to the session default for the strategies that never set it.
     *
     * <p><b>Unconditional, for the same reason {@link #publishSessionKeys} is.</b> {@code SET search_path}
     * is session-scoped, and with {@code persistence.perTenantPooling} at its default of {@code false}
     * every non-DEDICATED tenant is served from one pool — so a connection a SEPARATED_SCHEMA request
     * pointed at its own schema is handed, unchanged, to whatever runs next. A SHARED request inheriting
     * it still has its {@code exeris.tenant_id} enforced by RLS, but it is resolving unqualified table
     * names in another tenant's schema, where those policies are not what it thinks they are.
     *
     * <p>{@code RESET} rather than {@code SET search_path TO public}: the inverse of a session-level
     * override is the session default, which a deployment may have set per role or per database. Writing
     * a literal here would silently overrule that for every SHARED request.
     *
     * <p>Costs SHARED one round-trip it did not pay before. That is the same trade the shared-scope
     * setting already makes, and for the same reason — a connection cannot be asked what the last
     * request left on it.
     */
    private static void resetSearchPath(PersistenceConnection connection,
                                        StorageContext storageContext) {
        try {
            connection.executeUpdate(SQL_RESET_SEARCH_PATH);
        } catch (PersistenceProviderException ppe) {
            throw PersistenceProviderException.interceptorInitFailed(
                    INTERCEPTOR_NAME,
                    storageContext.isolationKey().orElse(ISOLATION_KEY_NONE),
                    ppe);
        }
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
