/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
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
 * <h2>Valhalla Readiness</h2>
 * <p>Implementations SHOULD be flat records. The canonical implementation
 * ({@code ImmutableStorageContext}) is ready for {@code value record} migration.
 *
 * <h2>Zero-Copy Contract</h2>
 * <p>All accessors are O(1). No lazy DB lookups, no mutable state.
 *
 * @since 0.5.0
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
     * The physical isolation strategy for this context.
     *
     * @return isolation strategy, never {@code null}
     */
    IsolationStrategy strategy();

    /**
     * Schema name for {@link IsolationStrategy#SEPARATED_SCHEMA} mode.
     *
     * @return schema name or empty if not applicable
     */
    Optional<String> schemaName();

    /**
     * DataSource routing key for {@link IsolationStrategy#DEDICATED} mode.
     *
     * @return datasource key or empty if not applicable
     */
    Optional<String> dataSourceKey();

    /**
     * Opaque key-value attributes for passing interceptor metadata.
     *
     * <p>Allows callers to attach additional context that
     * {@link eu.exeris.kernel.spi.persistence.ConnectionInterceptor} implementations
     * may inspect. Examples: audit user, request trace ID, feature flags.
     *
     * <p>MUST return an unmodifiable map. MUST NOT return {@code null} —
     * use {@link Map#of()} for an empty context.
     *
     * @return immutable attribute map; never {@code null}
     */
    default Map<String, Object> attributes() {
        return Map.of();
    }
}

