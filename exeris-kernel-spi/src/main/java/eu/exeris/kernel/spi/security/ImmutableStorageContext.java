/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.security;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Canonical, deeply-immutable implementation of {@link StorageContext}.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Standard {@code record} — ready for {@code value record} migration (JEP 401).
 * No identity operations permitted on instances of this type.
 *
 * <h2>Usage</h2>
 * <p>Created by {@link SecurityProvider} during token validation and bound into
 * {@code KernelProviders.STORAGE_CONTEXT}. The Persistence layer reads it via
 * {@code KernelProviders.STORAGE_CONTEXT.get().isolationKey()} to inject RLS
 * parameters — zero coupling to Security classes.
 *
 * @param isolationKey  the tenant isolation key for RLS (empty for system scope)
 * @param strategy      the physical isolation strategy
 * @param schemaName    schema name for SEPARATED_SCHEMA (empty otherwise)
 * @param dataSourceKey datasource key for DEDICATED (empty otherwise)
 * @param attributes    opaque {@code String→String} interceptor metadata; never {@code null}
 *
 * @since 0.5.0
 * @see StorageContext
 */
public record ImmutableStorageContext(
        Optional<String> isolationKey,
        IsolationStrategy strategy,
        Optional<String> schemaName,
        Optional<String> dataSourceKey,
        Map<String, String> attributes
) implements StorageContext {

    /**
     * Global storage context — no tenant isolation.
     *
     * <p>Used for tenant-less systems (B2C, M2M, open APIs) and
     * system-level operations (bootstrap, migrations). The Persistence
     * layer receives this as a signal: "do not inject RLS, there is
     * no multi-tenancy here".
     *
     * <p>This is a shared, immutable singleton — zero allocation
     * per request in tenant-less deployments.
     */
    public static final ImmutableStorageContext GLOBAL =
            new ImmutableStorageContext(
                    Optional.empty(),
                    IsolationStrategy.SHARED,
                    Optional.empty(),
                    Optional.empty(),
                    Map.of());

    /**
     * Compact constructor — fail-fast validation with full strategy-exclusive rules.
     *
     * <ul>
     *   <li>{@link IsolationStrategy#SHARED}: {@code schemaName} and {@code dataSourceKey}
     *       MUST both be absent — physical isolation is handled purely via RLS.</li>
     *   <li>{@link IsolationStrategy#SEPARATED_SCHEMA}: {@code schemaName} MUST be present,
     *       {@code dataSourceKey} MUST be absent.</li>
     *   <li>{@link IsolationStrategy#DEDICATED}: {@code dataSourceKey} MUST be present,
     *       {@code schemaName} MUST be absent.</li>
     * </ul>
     */
    public ImmutableStorageContext {
        Objects.requireNonNull(isolationKey, "isolationKey Optional must not be null");
        Objects.requireNonNull(strategy, "strategy must not be null");
        Objects.requireNonNull(schemaName, "schemaName Optional must not be null");
        Objects.requireNonNull(dataSourceKey, "dataSourceKey Optional must not be null");
        Objects.requireNonNull(attributes, "attributes must not be null");
        attributes = Map.copyOf(attributes);

        switch (strategy) {
            case SHARED -> {
                if (schemaName.isPresent()) {
                    throw new IllegalArgumentException(
                            "schemaName must be absent for SHARED strategy, got: " + schemaName.get());
                }
                if (dataSourceKey.isPresent()) {
                    throw new IllegalArgumentException(
                            "dataSourceKey must be absent for SHARED strategy, got: " + dataSourceKey.get());
                }
            }
            case SEPARATED_SCHEMA -> {
                if (schemaName.isEmpty()) {
                    throw new IllegalArgumentException(
                            "schemaName is required for SEPARATED_SCHEMA strategy");
                }
                if (dataSourceKey.isPresent()) {
                    throw new IllegalArgumentException(
                            "dataSourceKey must be absent for SEPARATED_SCHEMA strategy, got: " + dataSourceKey.get());
                }
            }
            case DEDICATED -> {
                if (dataSourceKey.isEmpty()) {
                    throw new IllegalArgumentException(
                            "dataSourceKey is required for DEDICATED strategy");
                }
                if (schemaName.isPresent()) {
                    throw new IllegalArgumentException(
                            "schemaName must be absent for DEDICATED strategy, got: " + schemaName.get());
                }
            }
        }
    }

    /**
     * Factory for the default SHARED/RLS mode.
     *
     * @param tenantId the tenant UUID used as isolation key
     * @return shared-mode storage context
     */
    public static ImmutableStorageContext shared(String tenantId) {
        return new ImmutableStorageContext(
                Optional.of(tenantId),
                IsolationStrategy.SHARED,
                Optional.empty(),
                Optional.empty(),
                Map.of());
    }

    /**
     * Factory for the default SHARED/RLS mode with interceptor attributes.
     *
     * @param tenantId   the tenant UUID used as isolation key
     * @param attributes opaque interceptor metadata
     * @return shared-mode storage context with attributes
     */
    public static ImmutableStorageContext shared(String tenantId, Map<String, String> attributes) {
        return new ImmutableStorageContext(
                Optional.of(tenantId),
                IsolationStrategy.SHARED,
                Optional.empty(),
                Optional.empty(),
                attributes);
    }

    /**
     * Factory for separated-schema mode.
     *
     * @param tenantId   the tenant UUID used as isolation key
     * @param schemaName the PostgreSQL schema name
     * @return schema-isolated storage context
     */
    public static ImmutableStorageContext separatedSchema(String tenantId, String schemaName) {
        return new ImmutableStorageContext(
                Optional.of(tenantId),
                IsolationStrategy.SEPARATED_SCHEMA,
                Optional.of(schemaName),
                Optional.empty(),
                Map.of());
    }

    /**
     * Factory for dedicated database mode.
     *
     * @param tenantId      the tenant UUID used as isolation key
     * @param dataSourceKey the routing key for the dedicated datasource
     * @return dedicated-db storage context
     */
    public static ImmutableStorageContext dedicated(String tenantId, String dataSourceKey) {
        return new ImmutableStorageContext(
                Optional.of(tenantId),
                IsolationStrategy.DEDICATED,
                Optional.empty(),
                Optional.of(dataSourceKey),
                Map.of());
    }

    /**
     * Factory for system-level / tenant-less operations.
     *
     * <p>Returns the shared {@link #GLOBAL} singleton — zero allocation.
     *
     * @return global storage context with no isolation key
     */
    public static ImmutableStorageContext system() {
        return GLOBAL;
    }
}

