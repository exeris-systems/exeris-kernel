/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */

/**
 * Exeris Kernel SPI – Security &amp; Context contracts (L1 Citadel).
 *
 * <h2>SPI-First Architecture</h2>
 * <p>This package defines all security and identity abstractions consumed by the
 * kernel bootstrap, transport edge, and persistence layer. No implementation-specific
 * class (JWT library, BouncyCastle, Spring Security) is referenced here — only pure
 * contracts.
 *
 * <h2>Key Types</h2>
 * <ul>
 *   <li>{@link eu.exeris.kernel.spi.security.PrincipalContext} — immutable identity
 *       interface (principalId, tenantId, roles, scopes)</li>
 *   <li>{@link eu.exeris.kernel.spi.security.StorageContext} — tenant-isolation
 *       descriptor for the Persistence layer (isolationKey, strategy)</li>
 *   <li>{@link eu.exeris.kernel.spi.security.SecurityProvider} — {@code ServiceLoader}
 *       entry point for authentication (token → PrincipalContext + StorageContext)</li>
 *   <li>{@link eu.exeris.kernel.spi.security.AuthenticationResult} — immutable result
 *       record pairing principal and storage context</li>
 *   <li>{@link eu.exeris.kernel.spi.security.ImmutablePrincipal} — canonical
 *       record-based PrincipalContext implementation (Valhalla-ready)</li>
 *   <li>{@link eu.exeris.kernel.spi.security.ImmutableStorageContext} — canonical
 *       record-based StorageContext implementation (Valhalla-ready)</li>
 * </ul>
 *
 * <h2>Context Propagation (JEP 506)</h2>
 * <p>Both {@code PrincipalContext} and {@code StorageContext} are propagated via
 * {@link java.lang.ScopedValue} slots defined in
 * {@link eu.exeris.kernel.spi.context.KernelProviders}:
 * <ul>
 *   <li>{@code KernelProviders.PRINCIPAL_CONTEXT} — bound per request</li>
 *   <li>{@code KernelProviders.STORAGE_CONTEXT} — bound per request</li>
 *   <li>{@code KernelProviders.SECURITY_PROVIDER} — bound once at bootstrap</li>
 * </ul>
 *
 * <h2>The Invisible Wall</h2>
 * <p>The Persistence subsystem MUST import only {@code StorageContext} — never
 * {@code PrincipalContext}, {@code SecurityContext}, or any concrete security class.
 * The Transport subsystem uses {@code SecurityProvider} to authenticate tokens and
 * binds both contexts before entering business logic.
 *
 * <h2>Tier Separation</h2>
 * <ul>
 *   <li><b>Community</b>: Heap-based JWT parsing, record-based contexts.
 *       Lives in {@code exeris-kernel-community}.</li>
 *   <li><b>Enterprise</b>: Off-heap token validation, zero-GC role resolution,
 *       slab-bound storage context. Lives in {@code exeris-kernel-enterprise}.</li>
 * </ul>
 *
 * @since 0.5.0
 */
package eu.exeris.kernel.spi.security;

