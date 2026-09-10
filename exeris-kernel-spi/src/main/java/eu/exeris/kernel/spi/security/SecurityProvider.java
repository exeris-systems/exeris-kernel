/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.security;

import eu.exeris.kernel.spi.memory.LoanedBuffer;

/**
 * SPI: Top-level entry point for the Security subsystem, discovered via {@link java.util.ServiceLoader}.
 *
 * <h2>ServiceLoader Contract</h2>
 * <p>Exactly one binding serves a running kernel: the discovered provider with the highest
 * {@link #priority()} wins, and the losers are never consulted. That provider authenticates every
 * credential arriving at the transport edge.
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>This interface has <em>zero knowledge</em> of JWT libraries, BouncyCastle,
 * io_uring, or any concrete implementation. It is a pure contract.
 *
 * <h2>Lifecycle</h2>
 * {@snippet :
 * ServiceLoader.load(SecurityProvider.class)
 *     → select max priority()
 *     → authenticate(rawToken)
 *     → bind PrincipalContext + StorageContext via ScopedValue
 *     → kernel handles request
 * }
 *
 * <p><b>Allocation:</b> allocates (one {@link AuthenticationResult} per successful
 * {@link #authenticate} call; whether the raw token itself is decoded on the heap is
 * binding-specific — see that method)
 * <p><b>Thread confinement:</b> virtual-thread-safe — one instance serves every virtual thread for
 * the lifetime of the kernel
 * <p><b>Ownership:</b> the caller owns the {@link LoanedBuffer} it passes to {@link #authenticate}
 * and releases it; a provider neither closes nor retains it beyond that call
 *
 * @implSpec Implementations MUST be thread-safe: a single instance is shared across all virtual
 *           threads for the lifetime of the kernel.
 * @implNote The Community binding parses standard JWTs with simple role mapping and record-based
 *           {@link PrincipalContext} / {@link StorageContext} carriers, reading the
 *           {@link LoanedBuffer} into a heap {@code byte[]}. The Enterprise binding validates the
 *           token off-heap against the buffer's segment — zero-GC role resolution and a slab-bound
 *           storage context integrated with the kernel memory arbiter.
 * @since 0.5
 * @see PrincipalContext
 * @see StorageContext
 * @see eu.exeris.kernel.spi.context.KernelProviders#PRINCIPAL_CONTEXT
 */
public interface SecurityProvider {

    /**
     * Stable identifier for this security backend, used to name the selected provider in
     * diagnostics and to pin it in configuration (e.g. {@code "jwt-community"},
     * {@code "jwt-enterprise"}).
     *
     * @return stable provider identifier; never {@code null}
     */
    String providerId();

    /**
     * Human-readable name carried into diagnostics and JFR events, naming the tier and the
     * validation library behind it (e.g. {@code "ExerisCommunity/JWT-JJWT"}).
     *
     * @return human-readable name; never {@code null}
     */
    String providerName();

    /**
     * Selection priority when several providers are on the classpath: the highest value is bound
     * and the rest are discarded.
     *
     * @return priority; default {@code 0}
     * @implSpec A Community binding MUST return {@code 0} and an Enterprise binding MUST return
     *           {@code 100}, so an Enterprise overlay always displaces the Community default
     *           (open-core tier convention).
     */
    default int priority() {
        return 0;
    }

    /**
     * Authenticates a raw token carried in a {@link LoanedBuffer}, yielding the identity and the
     * tenant-isolation descriptor the kernel binds for the request.
     *
     * <p>The transport layer delivers the raw credential (e.g. JWT bytes) inside a
     * {@link LoanedBuffer} backed by an off-heap {@code MemorySegment}, and the buffer stays the
     * caller's to release. Any validation failure is a terminal deny, signalled by throwing.
     *
     * @param rawToken the raw token inside a loaned buffer, owned by the caller
     * @return the authenticated principal paired with the storage context to bind for this
     *         request; never {@code null}
     * @throws eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException
     *         {@code EX-SEC-2002} — the token is invalid, expired, or revoked
     * @implSpec The caller retains ownership of the buffer: an implementation MUST NOT close it and
     *           MUST NOT retain it beyond the scope of this call.
     * @implNote The Community path copies the segment to a heap {@code byte[]} once and decodes
     *           with a standard JWT library — an allocation bounded by token size, typically
     *           &lt; 2 KB. The Enterprise path parses directly from the off-heap segment, resolving
     *           claims by offset arithmetic on the {@code MemorySegment} with no intermediate
     *           {@code String} or {@code Map} objects.
     */
    AuthenticationResult authenticate(LoanedBuffer rawToken);

    /**
     * Creates the {@link StorageContext} for system-level operations that run outside any user
     * authentication scope (bootstrap, migrations, outbox polling).
     *
     * @return a context carrying no isolation key, so the Persistence layer injects no RLS
     *         predicate; never {@code null}
     */
    StorageContext systemStorageContext();
}


