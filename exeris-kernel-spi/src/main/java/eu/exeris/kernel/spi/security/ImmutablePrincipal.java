/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.security;

import eu.exeris.kernel.spi.util.SpiDiagnostics;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Canonical, deeply-immutable implementation of {@link PrincipalContext}.
 *
 * <h2>Why UUID, not String?</h2>
 * <p>{@link UUID} is exactly 128 bits — two {@code long} primitives.
 * In the Enterprise tier the off-heap parser extracts the principal
 * via two {@code MemorySegment.get(JAVA_LONG, offset)} calls — zero
 * heap allocation. When Valhalla (JEP 401) lands, UUID becomes a
 * primitive value type scalarised into CPU registers. A {@code String}
 * would force a heap copy per request, destroying zero-GC.
 *
 * <h2>UUIDv7 — B-Tree Friendly</h2>
 * <p>All identifiers SHOULD be UUIDv7 (time-ordered). Sequential
 * inserts hit the tail of the B-Tree index — no page splits, no
 * random I/O. Performance comparable to auto-increment {@code BIGINT}
 * while retaining distributed key generation safety.
 *
 * <h2>Roles vs Scopes</h2>
 * <ul>
 *   <li><b>Roles</b> — "who is the principal?" (identity-level)</li>
 *   <li><b>Scopes</b> — "what can this token do?" (token-level,
 *       OAuth2/OIDC)</li>
 * </ul>
 *
 * <p><b>Allocation:</b> allocates (one record plus an immutable copy of {@code roles} and
 * {@code scopes} at construction, via {@link Set#copyOf(java.util.Collection)}); the accessors then
 * hand back the stored references without allocating further
 * <p><b>Thread confinement:</b> any thread — deeply immutable, so one instance is safe to publish
 * to every virtual thread that inherits the {@code PRINCIPAL_CONTEXT} binding
 * <p><b>Ownership:</b> nothing to release — the record outlives no resource, and the caller's
 * {@code roles} / {@code scopes} collections stay the caller's because they are copied, not adopted
 *
 * @param principalId UUIDv7 principal identifier (never {@code null})
 * @param tenantId    UUIDv7 tenant (empty for tenant-less / system)
 * @param roles       immutable role set (never {@code null})
 * @param scopes      immutable OAuth2 scope set (never {@code null})
 *
 * @apiNote No identity operation ({@code ==}, {@code synchronized},
 *          {@code System.identityHashCode()}) is permitted on an instance: all components are
 *          value-safe and the record is ready for {@code value record} migration (JEP 401), which
 *          would make identity meaningless.
 * @since 0.5
 * @see PrincipalContext
 */
public record ImmutablePrincipal(
        UUID principalId,
        Optional<UUID> tenantId,
        Set<String> roles,
        Set<String> scopes
) implements PrincipalContext {


    /**
     * Compact constructor — rejects a null component and copies {@code roles} and {@code scopes}
     * so a later mutation of the caller's collections cannot reach this principal.
     *
     * @throws NullPointerException if any component is {@code null}
     */
    public ImmutablePrincipal {
        Objects.requireNonNull(principalId, "principalId must not be null");
        Objects.requireNonNull(tenantId, "tenantId Optional must not be null");
        Objects.requireNonNull(roles, "roles must not be null");
        Objects.requireNonNull(scopes, "scopes must not be null");
        roles = Set.copyOf(roles);
        scopes = Set.copyOf(scopes);
    }

    // =================================================================
    // Factories — B2B (tenant-scoped)
    // =================================================================

    /**
     * Factory for a tenant-scoped principal with roles and scopes.
     *
     * @param principalId UUIDv7 principal identifier
     * @param tenantId    UUIDv7 tenant identifier
     * @param roles       role names
     * @param scopes      OAuth2 scopes
     * @return immutable principal
     */
    public static ImmutablePrincipal ofTenant(
            UUID principalId,
            UUID tenantId,
            Set<String> roles,
            Set<String> scopes) {
        return new ImmutablePrincipal(
                principalId, Optional.of(tenantId), roles, scopes);
    }

    /**
     * Factory for a tenant-scoped principal (roles only, no scopes).
     *
     * @param principalId UUIDv7 principal identifier
     * @param tenantId    UUIDv7 tenant identifier
     * @param roles       role names
     * @return immutable principal with empty scopes
     */
    public static ImmutablePrincipal ofTenant(
            UUID principalId,
            UUID tenantId,
            Set<String> roles) {
        return new ImmutablePrincipal(
                principalId, Optional.of(tenantId), roles, Set.of());
    }

    // =================================================================
    // Factories — M2M / B2C (tenant-less, scope-driven)
    // =================================================================

    /**
     * Factory for a tenant-less principal driven by OAuth2 scopes.
     *
     * <p>Typical for M2M tokens ({@code client_credentials} grant)
     * where {@code tenantId} is irrelevant and authorization is
     * determined entirely by the token's scopes. The M2M client
     * MUST still be registered with a UUIDv7 in the kernel's
     * identity registry.
     *
     * @param clientId UUIDv7 for the M2M client
     * @param scopes   OAuth2 scopes granted to the token
     * @return immutable principal with empty tenantId and roles
     */
    public static ImmutablePrincipal ofScopes(
            UUID clientId, Set<String> scopes) {
        return new ImmutablePrincipal(
                clientId, Optional.empty(), Set.of(), scopes);
    }

    // =================================================================
    // Factories — System
    // =================================================================

    /**
     * Factory for system-level operations (bootstrap, migrations).
     *
     * @param principalId UUIDv7 system principal identifier
     * @param roles       system role names
     * @return immutable principal with empty tenantId and scopes
     */
    public static ImmutablePrincipal system(
            UUID principalId, Set<String> roles) {
        return new ImmutablePrincipal(
                principalId, Optional.empty(), roles, Set.of());
    }

    @Override
    public String toString() {
        return "Principal{id=" + maskUuid(principalId)
                + ", tenant=" + tenantId
                        .map(ImmutablePrincipal::maskUuid).orElse("[global]")
                + ", roles=" + roles.size()
                + ", scopes=" + scopes.size() + "}";
    }

    /**
     * Masks a UUID for safe log output, so {@link #toString()} never publishes a full identifier —
     * delegates to the canonical {@link SpiDiagnostics#maskUuid(UUID)} shared across all SPI
     * identity carriers.
     *
     * <p>Example: {@code 01945a3b-f2c1-7...} → {@code 01945a3b~***}
     */
    private static String maskUuid(UUID uuid) {
        return SpiDiagnostics.maskUuid(uuid);
    }
}

