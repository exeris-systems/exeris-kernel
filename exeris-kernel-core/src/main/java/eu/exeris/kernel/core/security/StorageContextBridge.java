/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.security;

import eu.exeris.kernel.core.security.jfr.SecurityJfrEvents;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.security.PrincipalContextMissingException;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.security.PrincipalContext;
import eu.exeris.kernel.spi.security.StorageContext;

/**
 * Core: Isolation Bridge — derives a {@link StorageContext} from the active
 * {@link PrincipalContext} within the current {@link java.lang.ScopedValue} scope.
 *
 * <h2>Responsibility ("The Invisible Wall")</h2>
 * <p>The Persistence subsystem must never import Security classes.
 * {@code StorageContextBridge} is the <em>one allowed crossing point</em>
 * between the Security and Persistence planes. It reads
 * {@link KernelProviders#PRINCIPAL_CONTEXT} and produces a
 * {@link StorageContext} that the Persistence subsystem can safely consume
 * without any knowledge of authentication or tokens.
 *
 * <h2>Derivation Rules</h2>
 * <ol>
 *   <li>If the active principal carries a {@code tenantId()}, derive a
 *       {@link ImmutableStorageContext#shared(String)} context using
 *       the tenant UUID as the isolation key — the default SHARED/RLS path.</li>
 *   <li>If the principal has no {@code tenantId()}, return the pre-allocated
 *       {@link ImmutableStorageContext#GLOBAL} singleton — zero allocation,
 *       no RLS injection.</li>
 * </ol>
 *
 * <p>For advanced isolation modes ({@link StorageContext.IsolationStrategy#SEPARATED_SCHEMA},
 * {@link StorageContext.IsolationStrategy#DEDICATED}), the correct
 * {@link StorageContext} is produced directly by the {@link eu.exeris.kernel.spi.security.SecurityProvider}
 * during authentication and bound by the Security Interceptor. This bridge
 * serves the common SHARED/RLS case and the tenant-less global path.
 *
 * <h2>SHARED-only path</h2>
 * <p>This bridge derives only SHARED contexts. For
 * {@link StorageContext.IsolationStrategy#SEPARATED_SCHEMA} and
 * {@link StorageContext.IsolationStrategy#DEDICATED} strategies the
 * {@link StorageContext} is produced exclusively by
 * {@link eu.exeris.kernel.spi.security.SecurityProvider#authenticate} and
 * bound by the Security Interceptor. Callers that require a non-SHARED
 * context MUST NOT call this bridge.
 *
 * <h2>JFR-First</h2>
 * <p>Every derivation emits a {@code StorageContextDerived} JFR event.
 *
 * <h2>The Wall</h2>
 * <p>No JWT library, no token parsing, no database call in this class.
 * Pure SPI-to-SPI translation.
 *
 * @since 0.5.0
 * @see eu.exeris.kernel.core.security.SecurityInterceptor
 * @see StorageContext
 * @see ImmutableStorageContext
 */
public final class StorageContextBridge {

    private StorageContextBridge() {}

    /**
     * Derives a {@link StorageContext} from the currently-bound
     * {@link PrincipalContext} in the active scope.
     *
     * <h2>Derivation logic</h2>
     * <ul>
     *   <li>Tenant present → {@link ImmutableStorageContext#shared(long, long)}</li>
     *   <li>No tenant     → {@link ImmutableStorageContext#GLOBAL} (singleton, zero-alloc)</li>
     * </ul>
     *
     * @return derived {@link StorageContext}; never {@code null}
     * @throws eu.exeris.kernel.spi.exceptions.security.PrincipalContextMissingException
     *         if no {@link PrincipalContext} is bound in the current scope
     */
    public static StorageContext deriveFromActivePrincipal() {
        PrincipalContext principal = KernelProviders.principal();
        return derive(principal);
    }

    /**
     * Derives a {@link StorageContext} from the given {@link PrincipalContext}.
     *
     * <p>This overload is provided for callers that already hold a reference
     * to the principal and want to avoid the {@code ScopedValue.get()} call overhead.
     *
     * @param principal the authenticated principal; must not be {@code null}
     * @return derived {@link StorageContext}; never {@code null}
     */
    public static StorageContext derive(PrincipalContext principal) {
        if (principal == null) {
            throw new PrincipalContextMissingException();
        }

        StorageContext result = principal.tenantId()
                .map(tenantId -> (StorageContext) ImmutableStorageContext.shared(
                        tenantId.getMostSignificantBits(),
                        tenantId.getLeastSignificantBits()))
                .orElse(ImmutableStorageContext.GLOBAL);

        SecurityJfrEvents.emitStorageContextDerived(
                result.strategy().name(),
                result.isolationKey().isPresent()
        );

        return result;
    }
}



