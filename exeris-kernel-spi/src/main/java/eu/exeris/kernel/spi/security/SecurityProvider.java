/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.security;

import eu.exeris.kernel.spi.memory.LoanedBuffer;

/**
 * SPI: Top-level entry point for the Security subsystem, discovered via {@link java.util.ServiceLoader}.
 *
 * <h2>ServiceLoader Contract</h2>
 * <p>Each tier (Community/Enterprise) provides exactly one binding:
 * <ul>
 *   <li><b>Community</b> ({@code priority() = 0}): Standard JWT parsing,
 *       simple role mapping, record-based {@link PrincipalContext} and
 *       {@link StorageContext}. Reads from {@link LoanedBuffer} into a
 *       heap {@code byte[]} internally.</li>
 *   <li><b>Enterprise</b> ({@code priority() = 100}): Off-heap token
 *       validation directly from the {@link LoanedBuffer} segment —
 *       zero-GC role resolution, slab-bound storage context integrated
 *       with {@code GlobalMemoryArbiter}.</li>
 * </ul>
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>This interface has <em>zero knowledge</em> of JWT libraries, BouncyCastle,
 * io_uring, or any concrete implementation. It is a pure contract.
 *
 * <h2>Lifecycle</h2>
 * <pre>
 *  ServiceLoader.load(SecurityProvider.class)
 *      → select max priority()
 *      → authenticate(rawToken)
 *      → bind PrincipalContext + StorageContext via ScopedValue
 *      → kernel handles request
 * </pre>
 *
 * <h2>Thread Safety</h2>
 * <p>Implementations MUST be thread-safe. A single instance is shared across all
 * virtual threads for the lifetime of the kernel.
 *
 * @since 0.5.0
 * @see PrincipalContext
 * @see StorageContext
 * @see eu.exeris.kernel.spi.context.KernelProviders#PRINCIPAL_CONTEXT
 */
public interface SecurityProvider {

    /**
     * Unique identifier for this security backend
     * (e.g., {@code "jwt-community"}, {@code "jwt-enterprise"}).
     *
     * @return stable provider identifier
     */
    String providerId();

    /**
     * Display name for diagnostics and JFR events
     * (e.g., {@code "ExerisCommunity/JWT-JJWT"}).
     *
     * @return human-readable name
     */
    String providerName();

    /**
     * Selection priority when multiple providers are on the classpath.
     *
     * <p>Higher value wins. Community MUST return {@code 0};
     * Enterprise MUST return {@code 100}.
     *
     * @return priority; default {@code 0}
     */
    default int priority() {
        return 0;
    }

    /**
     * Authenticates a raw token carried in a {@link LoanedBuffer}.
     *
     * <h2>Zero-Copy Contract</h2>
     * <p>The transport layer delivers the raw token (e.g., JWT bytes) inside
     * a {@link LoanedBuffer} backed by an off-heap {@code MemorySegment}.
     * The caller retains ownership of the buffer — the provider MUST NOT
     * close or retain it beyond the scope of this call.
     *
     * <h2>Community path</h2>
     * <p>Copies the segment to a heap {@code byte[]} once, then decodes
     * using a standard JWT library. Acceptable allocation for the free
     * tier — bounded by token size (typically &lt; 2 KB).
     *
     * <h2>Enterprise path</h2>
     * <p>Parses the token directly from the off-heap segment — zero
     * intermediate heap allocations, zero {@code String}/{@code Map}
     * objects. Claims are resolved via offset arithmetic on the
     * {@code MemorySegment}.
     *
     * @param rawToken the raw token inside a loaned buffer
     * @return authentication result with principal and storage context
     * @throws eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException
     *         if the token is invalid, expired, or revoked
     */
    AuthenticationResult authenticate(LoanedBuffer rawToken);

    /**
     * Creates a {@link StorageContext} for system-level operations that run
     * outside of a user authentication scope (bootstrap, migrations, outbox polling).
     *
     * @return system-level storage context with no tenant isolation
     */
    StorageContext systemStorageContext();
}


