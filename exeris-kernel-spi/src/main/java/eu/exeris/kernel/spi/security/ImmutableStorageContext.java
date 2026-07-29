/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 * @param isolationKey   the tenant isolation key for RLS (empty for system scope)
 * @param strategy       the physical isolation strategy
 * @param schemaName     schema name for SEPARATED_SCHEMA (empty otherwise)
 * @param dataSourceKey  datasource key for DEDICATED (empty otherwise)
 * @param sharedScopeKey shared-scope partition (empty for the tenant-private default); orthogonal to
 *                       {@code strategy} — see {@link StorageContext#sharedScopeKey()}
 * @param attributes     opaque {@code String→String} interceptor metadata; never {@code null}
 *
 * @since 0.5.0
 * @see StorageContext
 */
public record ImmutableStorageContext(
        Optional<String> isolationKey,
        IsolationStrategy strategy,
        Optional<String> schemaName,
        Optional<String> dataSourceKey,
        Optional<String> sharedScopeKey,
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
                    Optional.empty(),
                    Map.of());

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

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
     *
     * <p>{@code sharedScopeKey} is <b>orthogonal</b> and adds no strategy exclusion — it composes with
     * all three (ADR-012 §4b.1). It carries one invariant of its own: it MUST NOT be present without an
     * {@code isolationKey}, because the shared-scope write predicate pins {@code owner = isolationKey}
     * and has nothing to pin to otherwise. This is what makes {@link #GLOBAL} (system scope, no
     * isolation key) structurally incapable of carrying a shared scope.
     */
    public ImmutableStorageContext {
        Objects.requireNonNull(isolationKey, "isolationKey Optional must not be null");
        Objects.requireNonNull(strategy, "strategy must not be null");
        Objects.requireNonNull(schemaName, "schemaName Optional must not be null");
        Objects.requireNonNull(dataSourceKey, "dataSourceKey Optional must not be null");
        Objects.requireNonNull(sharedScopeKey, "sharedScopeKey Optional must not be null");
        Objects.requireNonNull(attributes, "attributes must not be null");
        attributes = Map.copyOf(attributes);

        if (sharedScopeKey.isPresent() && isolationKey.isEmpty()) {
            throw new IllegalArgumentException(
                    "sharedScopeKey requires an isolationKey to pin owner-scoped writes to");
        }

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
                Optional.empty(),
                Map.of());
    }

    /**
     * Factory for the default SHARED/RLS mode from raw UUID bits — zero {@code UUID.toString()} allocation.
     *
     * <p>Produces an isolation key in canonical UUID string format
     * ({@code xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx}) by encoding the two
     * {@code long} components via {@link #uuidBitsToString(long, long)} using manual
     * nibble extraction into a pre-allocated {@code char[]} backed by the {@code HEX_DIGITS}
     * lookup table, without creating an intermediate {@link java.util.UUID} object.
     *
     * @param idMost  {@link java.util.UUID#getMostSignificantBits()}
     * @param idLeast {@link java.util.UUID#getLeastSignificantBits()}
     * @return shared-mode storage context; never {@code null}
     */
    public static ImmutableStorageContext shared(long idMost, long idLeast) {
        String isolationKey = uuidBitsToString(idMost, idLeast);
        return new ImmutableStorageContext(
                Optional.of(isolationKey),
                IsolationStrategy.SHARED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Map.of());
    }

    /**
     * Encodes two {@code long} UUID components into canonical UUID string format
     * without allocating a {@link java.util.UUID} object.
     *
     * <p>Format: {@code xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx} (RFC 4122, lowercase hex, zero-padded).
     *
     * @param msb most-significant bits
     * @param lsb least-significant bits
     * @return canonical UUID string; never {@code null}
     */
    private static String uuidBitsToString(long msb, long lsb) {
        char[] buf = new char[36];
        formatHex(buf, 0,  msb >>> 32, 8);
        buf[8] = '-';
        formatHex(buf, 9,  (msb >>> 16) & 0xFFFFL, 4);
        buf[13] = '-';
        formatHex(buf, 14, msb & 0xFFFFL, 4);
        buf[18] = '-';
        formatHex(buf, 19, (lsb >>> 48) & 0xFFFFL, 4);
        buf[23] = '-';
        formatHex(buf, 24, lsb & 0xFFFFFFFFFFFFL, 12);
        return new String(buf);
    }

    private static void formatHex(char[] buf, int offset, long value, int digits) {
        long remaining = value;
        for (int i = digits - 1; i >= 0; i--) {
            buf[offset + i] = HEX_DIGITS[(int) (remaining & 0xF)];
            remaining >>>= 4;
        }
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
                Optional.empty(),
                Map.of());
    }

    /**
     * Factory for separated-schema mode with interceptor attributes.
     *
     * @param tenantId   the tenant UUID used as isolation key
     * @param schemaName the PostgreSQL schema name
     * @param attributes opaque {@code String→String} interceptor metadata (e.g. audit label,
     *                   trace ID, feature flags); copied defensively via {@link Map#copyOf}
     * @return schema-isolated storage context with attributes
     */
    public static ImmutableStorageContext separatedSchema(
            String tenantId, String schemaName, Map<String, String> attributes) {
        return new ImmutableStorageContext(
                Optional.of(tenantId),
                IsolationStrategy.SEPARATED_SCHEMA,
                Optional.of(schemaName),
                Optional.empty(),
                Optional.empty(),
                attributes);
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
                Optional.empty(),
                Map.of());
    }

    /**
     * Factory for dedicated database mode with interceptor attributes.
     *
     * @param tenantId      the tenant UUID used as isolation key
     * @param dataSourceKey the routing key for the dedicated datasource
     * @param attributes    opaque {@code String→String} interceptor metadata (e.g. audit label,
     *                      trace ID, feature flags); copied defensively via {@link Map#copyOf}
     * @return dedicated-db storage context with attributes
     */
    public static ImmutableStorageContext dedicated(
            String tenantId, String dataSourceKey, Map<String, String> attributes) {
        return new ImmutableStorageContext(
                Optional.of(tenantId),
                IsolationStrategy.DEDICATED,
                Optional.empty(),
                Optional.of(dataSourceKey),
                Optional.empty(),
                attributes);
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

    /**
     * Returns a copy of this context participating in the given shared-scope partition.
     *
     * <p>Deliberately a single composer rather than a shared-scope variant of each factory: the
     * shared-scope dimension is <b>orthogonal</b> to physical placement (ADR-012 §4b.1), so it composes
     * with {@code SHARED}, {@code SEPARATED_SCHEMA}, and {@code DEDICATED} alike. Adding it as a
     * parameter to every factory would multiply the surface while implying the two axes are related.
     *
     * <p>Reads widen to the partition; writes stay pinned to this context's {@link #isolationKey()} as
     * the owner. Requires an {@code isolationKey} to be present — see the compact constructor.
     *
     * @param sharedScopeKey the shared-scope partition key; never {@code null}
     * @return a new context identical to this one but carrying the shared scope; never {@code null}
     * @throws IllegalArgumentException if this context has no {@link #isolationKey()}
     * @since 0.11.0
     */
    public ImmutableStorageContext withSharedScope(String sharedScopeKey) {
        Objects.requireNonNull(sharedScopeKey, "sharedScopeKey must not be null");
        return new ImmutableStorageContext(
                isolationKey, strategy, schemaName, dataSourceKey,
                Optional.of(sharedScopeKey), attributes);
    }
}

