/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.security;

import java.util.Objects;

/**
 * SPI: Immutable result of a successful authentication operation.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Standard {@code record} — all fields are interface references (no identity ops).
 * Ready for {@code value record} migration (JEP 401) once toolchain supports it.
 * No identity operations ({@code ==}, {@code synchronized}, {@code System.identityHashCode()})
 * are permitted on instances of this type.
 *
 * <h2>Contract</h2>
 * <p>After successful {@link SecurityProvider#authenticate(eu.exeris.kernel.spi.memory.LoanedBuffer)},
 * the kernel bootstrapper binds both contexts into their respective {@code ScopedValue} slots:
 * <pre>{@code
 * AuthenticationResult result = securityProvider.authenticate(tokenBuffer);
 * ScopedValue
 *     .where(KernelProviders.PRINCIPAL_CONTEXT, result.principal())
 *     .where(KernelProviders.STORAGE_CONTEXT,   result.storage())
 *     .run(() -> handleRequest());
 * }</pre>
 *
 * @param principal the authenticated identity (never {@code null})
 * @param storage   the tenant-isolation descriptor for persistence (never {@code null})
 *
 * @since 0.5.0
 * @see SecurityProvider
 * @see PrincipalContext
 * @see StorageContext
 */
public record AuthenticationResult(
        PrincipalContext principal,
        StorageContext storage
) {
    /**
     * Compact constructor — fail-fast validation.
     */
    public AuthenticationResult {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(storage, "storage must not be null");
    }
}

