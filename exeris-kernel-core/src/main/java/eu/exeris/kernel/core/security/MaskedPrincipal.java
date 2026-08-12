/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.security;

import eu.exeris.kernel.spi.security.PrincipalContext;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Core-internal {@link PrincipalContext} decorator that overrides
 * {@link #roleMask()} with a precomputed bitmask while delegating every other
 * accessor to the wrapped principal (ADR-014 §5).
 *
 * <h2>Why a wrapper, not an SPI field</h2>
 * <p>The canonical SPI carrier {@code ImmutablePrincipal} keeps the
 * {@code roleMask()} default of {@code 0L} — adding a mask component to that
 * record would change the canonical SPI shape and force every
 * {@code PrincipalContext} producer to know the build-time bit assignment. The
 * mask is instead resolved at authentication time (when the registry is in
 * scope) and carried by this thin Core decorator, leaving the SPI record
 * untouched.
 *
 * <h2>Lifecycle</h2>
 * <p>Created by {@link SecurityInterceptor} immediately before binding
 * {@code KernelProviders.PRINCIPAL_CONTEXT}, only when the active
 * {@link eu.exeris.kernel.spi.security.RoleRegistry} actually carries
 * {@code @RequiresRole} entry points ({@code methodCount() > 0}). When the
 * registry is empty no wrapper is allocated and the original principal flows
 * through with its {@code 0L} mask.
 *
 * <h2>Valhalla readiness</h2>
 * <p>A {@code record} with value-safe components and no identity operations.
 *
 * @param delegate the wrapped principal (never {@code null})
 * @param roleMask the precomputed role bitmask aligned with the generated registry
 *
 * @since 0.8.0
 * @see PrincipalContext#roleMask()
 * @see SecurityInterceptor
 */
public value record MaskedPrincipal(PrincipalContext delegate, long roleMask) implements PrincipalContext {

    /**
     * Compact constructor — fail-fast on a {@code null} delegate.
     */
    public MaskedPrincipal {
        Objects.requireNonNull(delegate, "delegate must not be null");
    }

    @Override
    public UUID principalId() {
        return delegate.principalId();
    }

    @Override
    public Optional<UUID> tenantId() {
        return delegate.tenantId();
    }

    @Override
    public Set<String> roles() {
        return delegate.roles();
    }

    @Override
    public boolean hasRole(String role) {
        return delegate.hasRole(role);
    }

    @Override
    public boolean hasAnyRole(String... checkRoles) {
        return delegate.hasAnyRole(checkRoles);
    }

    @Override
    public boolean hasAnyRole(String role1) {
        return delegate.hasAnyRole(role1);
    }

    @Override
    public boolean hasAnyRole(String role1, String role2) {
        return delegate.hasAnyRole(role1, role2);
    }

    @Override
    public boolean hasAnyRole(String role1, String role2, String role3) {
        return delegate.hasAnyRole(role1, role2, role3);
    }

    @Override
    public Set<String> scopes() {
        return delegate.scopes();
    }

    @Override
    public boolean hasScope(String scope) {
        return delegate.hasScope(scope);
    }

    @Override
    public boolean hasAnyScope(String... checkScopes) {
        return delegate.hasAnyScope(checkScopes);
    }

    @Override
    public boolean hasAnyScope(String scope1) {
        return delegate.hasAnyScope(scope1);
    }

    @Override
    public boolean hasAnyScope(String scope1, String scope2) {
        return delegate.hasAnyScope(scope1, scope2);
    }

    @Override
    public boolean hasAnyScope(String scope1, String scope2, String scope3) {
        return delegate.hasAnyScope(scope1, scope2, scope3);
    }

    @Override
    public long roleMask() {
        return roleMask;
    }
}
