/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.security;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * SPI: Immutable identity representation propagated via {@link ScopedValue} (JEP 506).
 *
 * <h2>Universal Identity Model</h2>
 * <p>This interface supports <em>both</em> multi-tenant SaaS (B2B) and
 * tenant-less API systems (B2C, M2M, partner APIs). The design separates
 * two orthogonal authorization dimensions:
 * <ul>
 *   <li><b>Roles</b> — <em>who</em> the principal is
 *       (e.g., {@code ROLE_ADMIN}, {@code ROLE_USER}).
 *       Typically derived from the identity provider's user profile.</li>
 *   <li><b>Scopes</b> — <em>what this token can do</em>
 *       (e.g., {@code read:orders}, {@code repo:write}).
 *       Defined by OAuth2/OIDC token grants. A scope restricts
 *       the token's power, even if the principal's role would
 *       otherwise allow the operation.</li>
 * </ul>
 *
 * <h2>Tenant Optionality</h2>
 * <p>{@link #tenantId()} returns {@link Optional#empty()} when the system
 * has no concept of tenants (B2C, M2M, open APIs). In that case the
 * paired {@link StorageContext} is {@code GLOBAL} (no RLS injection)
 * and authorization relies entirely on {@link #scopes()} and
 * {@link #roles()}.
 *
 * <h2>The Wall (Domain Isolation)</h2>
 * <p>This interface is the <em>only</em> identity abstraction visible
 * to kernel subsystems. No subsystem may import {@code SecurityContext},
 * {@code User}, or any concrete token-parsing class.
 *
 * <h2>Why UUID, not String?</h2>
 * <p>A {@link UUID} is exactly 128 bits (two {@code long} primitives).
 * In the Enterprise tier, the off-heap token parser extracts the
 * principal identity via two
 * {@code MemorySegment.get(ValueLayout.JAVA_LONG, offset)} calls —
 * <b>zero heap allocation</b>. When Project Valhalla (JEP 401) lands,
 * {@code UUID} will become a primitive value type scalarised into two
 * CPU registers. A {@code String principalId} would force a heap
 * allocation and a byte-copy for <em>every single request</em>,
 * destroying the zero-GC contract.
 *
 * <p>External identity systems that use string-based IDs (Auth0, SAML)
 * MUST map to internal UUIDv7 identifiers at the API Gateway edge,
 * before the request enters the kernel.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Implementations SHOULD be {@code record}-based (deeply immutable,
 * no identity ops). Ready for {@code value record} migration (JEP 401).
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>All accessors MUST be O(1) — no lazy loading, no DB calls.</li>
 *   <li>All returned {@code Set<String>} are immutable.</li>
 *   <li>Implementations MUST be thread-safe (deep immutability).</li>
 *   <li>All UUIDs SHOULD be v7 (time-ordered) for B-Tree-friendly
 *       database indexing — sequential inserts, no page splits.</li>
 * </ul>
 *
 * @since 0.5.0
 * @see eu.exeris.kernel.spi.context.KernelProviders#PRINCIPAL_CONTEXT
 * @see StorageContext
 */
public interface PrincipalContext {

    // =====================================================================
    // Identity
    // =====================================================================

    /**
     * The authenticated principal's unique identifier (UUIDv7).
     *
     * <p>For JWT tokens this is the {@code sub} claim parsed as UUID.
     * For system operations use a well-known system UUID constant.
     *
     * <p><b>Zero-GC path:</b> UUID is 128 bits — two {@code long}
     * primitives extracted directly from the off-heap token buffer
     * via {@code MemorySegment.get(JAVA_LONG, offset)}.
     *
     * @return principal UUID, never {@code null}
     */
    UUID principalId();

    /**
     * The tenant to which the principal belongs (UUIDv7).
     *
     * <p>Returns {@link Optional#empty()} when:
     * <ul>
     *   <li>The system has no multi-tenant concept (B2C, M2M).</li>
     *   <li>The operation is cross-tenant (bootstrap, migrations).</li>
     * </ul>
     *
     * <p>When empty, the paired {@link StorageContext} is
     * {@link ImmutableStorageContext#GLOBAL} — no RLS injection.
     *
     * @return tenant UUID or empty for tenant-less / system scope
     */
    Optional<UUID> tenantId();

    // =====================================================================
    // Roles — "Who is the principal?"
    // =====================================================================

    /**
     * Immutable set of role identifiers assigned to the principal.
     *
     * <p>Roles answer the question <em>"who is this user?"</em>
     * (e.g., {@code ROLE_ADMIN}, {@code ROLE_USER}).
     * Case-sensitive. The returned set is immutable.
     *
     * @return role names, never {@code null}, may be empty
     */
    Set<String> roles();

    /**
     * Quick check for a single role.
     *
     * @param role role name to check (case-sensitive)
     * @return {@code true} if the principal holds the role
     */
    default boolean hasRole(String role) {
        return roles().contains(role);
    }

    /**
     * Quick check for any of the given roles.
     *
     * @param checkRoles role names to check
     * @return {@code true} if the principal holds at least one
     */
    default boolean hasAnyRole(String... checkRoles) {
        Set<String> owned = roles();
        for (String r : checkRoles) {
            if (owned.contains(r)) {
                return true;
            }
        }
        return false;
    }

    // =====================================================================
    // Scopes — "What can this token do?"
    // =====================================================================

    /**
     * Immutable set of OAuth2/OIDC scopes granted to the token.
     *
     * <p>Scopes answer the question <em>"what is this token allowed
     * to do?"</em> (e.g., {@code read:orders}, {@code write:users},
     * {@code repo:write}). A scope restricts the token's effective
     * power — even if the principal's role would otherwise permit
     * the operation, the token can only act within its scopes.
     *
     * <p>For systems without OAuth2 scopes this returns an empty set,
     * and authorization falls back to {@link #roles()} only.
     *
     * @return scope identifiers, never {@code null}, may be empty
     */
    Set<String> scopes();

    /**
     * Quick check for a single OAuth2 scope.
     *
     * @param scope scope identifier (e.g., {@code "read:orders"})
     * @return {@code true} if the token carries the scope
     */
    default boolean hasScope(String scope) {
        return scopes().contains(scope);
    }

    /**
     * Quick check for any of the given scopes.
     *
     * @param checkScopes scope identifiers to check
     * @return {@code true} if the token carries at least one
     */
    default boolean hasAnyScope(String... checkScopes) {
        Set<String> owned = scopes();
        for (String s : checkScopes) {
            if (owned.contains(s)) {
                return true;
            }
        }
        return false;
    }
}

