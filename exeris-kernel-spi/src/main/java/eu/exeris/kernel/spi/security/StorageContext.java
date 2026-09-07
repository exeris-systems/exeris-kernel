/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.security;

import java.util.Map;
import java.util.Optional;

/**
 * SPI: Tenant-isolation descriptor consumed by the Persistence subsystem.
 *
 * <h2>The Invisible Wall</h2>
 * <p>This interface is what the Persistence layer sees instead of {@code SecurityContext},
 * {@code TenantContext}, or any concrete security class. The Persistence engine
 * reads {@link eu.exeris.kernel.spi.context.KernelProviders#STORAGE_CONTEXT} and uses
 * the returned isolation key/strategy to configure connections — zero coupling to Security.
 *
 * <h2>Isolation Strategies</h2>
 * <table>
 *   <caption>Each isolation strategy, the mechanism the Persistence layer applies for it, and the
 *   deployment shape it targets</caption>
 *   <tr><th>Strategy</th><th>Mechanism</th><th>Use Case</th></tr>
 *   <tr><td>{@link IsolationStrategy#SHARED}</td>
 *       <td>{@code SET LOCAL exeris.tenant_id = ?} (RLS)</td>
 *       <td>Standard SaaS, high-density</td></tr>
 *   <tr><td>{@link IsolationStrategy#SEPARATED_SCHEMA}</td>
 *       <td>{@code SET search_path TO [schema]}</td>
 *       <td>Professional tier, easier migrations</td></tr>
 *   <tr><td>{@link IsolationStrategy#DEDICATED}</td>
 *       <td>Dynamic DataSource routing</td>
 *       <td>Enterprise, maximum physical isolation</td></tr>
 * </table>
 *
 * <p><b>Allocation:</b> zero-alloc on hot path — every accessor hands back an already-resolved
 * value, and the {@link #sharedScopeKey()} and {@link #attributes()} defaults return shared empty
 * constants rather than fresh ones
 * <p><b>Thread confinement:</b> any thread — a context is deeply immutable and is read from every
 * thread that inherits the {@code STORAGE_CONTEXT} binding, forked subtasks included
 * <p><b>Ownership:</b> the Security edge builds it and binds it for the request scope; the
 * Persistence layer only reads it, and there is nothing to release
 *
 * @implSpec All accessors MUST be O(1) — no lazy DB lookups, no I/O, no mutable state — and an
 *           implementation SHOULD be a flat record so it stays deeply immutable across the
 *           {@code ScopedValue} hand-off.
 * @implNote The canonical implementation, {@link ImmutableStorageContext}, is ready for
 *           {@code value record} migration (JEP 401).
 * @since 0.5
 * @see eu.exeris.kernel.spi.context.KernelProviders#STORAGE_CONTEXT
 * @see PrincipalContext
 */
public interface StorageContext {

    /**
     * Isolation strategies supported by the Exeris Kernel.
     *
     * <p>Determines how the Persistence layer physically separates tenant data.
     * The strategy is resolved during token parsing (Security edge) and propagated
     * via {@code ScopedValue} to the Persistence edge.
     */
    enum IsolationStrategy {
        /** Shared schema with Row-Level Security (RLS). */
        SHARED,
        /** Separate schema per tenant within the same database. */
        SEPARATED_SCHEMA,
        /** Dedicated database/server per tenant. */
        DEDICATED
    }

    /**
     * The tenant isolation key used by RLS policies.
     *
     * <p>For {@link IsolationStrategy#SHARED} this is the value set via
     * {@code SET LOCAL exeris.tenant_id = ?}. Returns {@link Optional#empty()}
     * only for global/system operations that bypass RLS.
     *
     * @return isolation key string or empty for system-scope
     */
    Optional<String> isolationKey();

    /**
     * The physical isolation strategy the Persistence layer applies when it configures a
     * connection for this request.
     *
     * @return the isolation strategy; never {@code null}
     */
    IsolationStrategy strategy();

    /**
     * Schema name for {@link IsolationStrategy#SEPARATED_SCHEMA} mode.
     *
     * @return the schema the connection switches to via {@code SET search_path}, or empty under
     *         any other strategy
     */
    Optional<String> schemaName();

    /**
     * DataSource routing key for {@link IsolationStrategy#DEDICATED} mode.
     *
     * @return the key selecting the tenant's dedicated datasource, or empty under any other
     *         strategy
     */
    Optional<String> dataSourceKey();

    /**
     * The shared-scope partition this request participates in, if any.
     *
     * <p><b>An orthogonal dimension, not a fourth strategy.</b> {@link #strategy()} answers
     * <i>where rows physically live</i>; this answers <i>who may read a given row</i>. The two are
     * independent: a shared dataset is valid under {@link IsolationStrategy#SHARED},
     * {@link IsolationStrategy#SEPARATED_SCHEMA}, and {@link IsolationStrategy#DEDICATED} alike
     * (ADR-012 §4b).
     *
     * <p><b>Presence is the mode.</b> There is deliberately no separate visibility-mode accessor to
     * reconcile this against:
     * <ul>
     *   <li><b>empty</b> — tenant-private. Rows are visible only to {@link #isolationKey()}. This is
     *       the default.</li>
     *   <li><b>present</b> — reads widen to the shared partition; writes stay pinned to
     *       {@link #isolationKey()} as the owner, so a tenant can never create or mutate another owner's
     *       row (ADR-012 §4b.4).</li>
     * </ul>
     *
     * <p>Like every other accessor here this is an <em>outcome</em>, not a mechanism: no RLS predicate,
     * policy object, or driver type crosses this interface.
     *
     * @return the shared-scope partition key, or empty for the tenant-private default
     * @implSpec A context carrying a shared scope MUST also carry an {@link #isolationKey()}: the
     *           owner-scoped write predicate has nothing to pin to otherwise. An implementation
     *           MUST reject a shared scope presented without one.
     * @since 0.11
     */
    default Optional<String> sharedScopeKey() {
        return Optional.empty();
    }

    /**
     * Opaque key-value attributes for passing interceptor metadata.
     *
     * <p>Allows callers to attach additional context that
     * {@link eu.exeris.kernel.spi.persistence.ConnectionInterceptor} implementations
     * may inspect. Examples: audit user label, request trace ID, feature flags.
     *
     * <p>Value type is restricted to {@link String} to enforce the "Invisible Wall" —
     * it is impossible to accidentally smuggle identity or security objects
     * (e.g., {@code PrincipalContext}) across the persistence boundary.
     *
     * @return immutable {@code String→String} attribute map; never {@code null}
     * @implSpec An implementation MUST return an unmodifiable map and MUST NOT return
     *           {@code null} — use {@link Map#of()} for an empty context.
     */
    default Map<String, String> attributes() {
        return Map.of();
    }
}

