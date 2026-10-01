/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.security;

import java.util.Objects;

/**
 * SPI: Immutable result of a successful authentication operation.
 *
 * <p>The two halves always travel together: a successful
 * {@link SecurityProvider#authenticate(eu.exeris.kernel.spi.memory.LoanedBuffer)} yields both, and
 * the transport edge binds them into their respective {@code ScopedValue} slots for the request.
 *
 * {@snippet lang="java" :
 * AuthenticationResult result = securityProvider.authenticate(tokenBuffer);
 * ScopedValue
 *     .where(KernelProviders.PRINCIPAL_CONTEXT, result.principal())
 *     .where(KernelProviders.STORAGE_CONTEXT,   result.storage())
 *     .run(() -> handleRequest());
 * }
 *
 * @param principal the authenticated identity (never {@code null})
 * @param storage   the tenant-isolation descriptor for persistence (never {@code null})
 *
 * @apiNote No identity operation ({@code ==}, {@code synchronized},
 *          {@code System.identityHashCode()}) is permitted on an instance: all fields are
 *          interface references and the record is ready for {@code value record} migration
 *          (JEP 401), which would make identity meaningless.
 * @since 0.5
 * @see SecurityProvider
 * @see PrincipalContext
 * @see StorageContext
 */
public record AuthenticationResult(
        PrincipalContext principal,
        StorageContext storage
) {
    /**
     * Compact constructor — rejects a half-built result rather than binding one.
     *
     * @throws NullPointerException if {@code principal} or {@code storage} is {@code null}
     */
    public AuthenticationResult {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(storage, "storage must not be null");
    }
}

