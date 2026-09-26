/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.memory;

import eu.exeris.kernel.spi.exceptions.memory.MemoryBootstrapException;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProvider;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;

/**
 * Community: {@link MemoryProvider} that creates {@link CommunityMemoryAllocator}
 * instances backed by a shard-based arena pool ({@link CommunityArenaShardPool}) — one
 * shared {@code Arena} per shard, reused across allocations via size-class free queues.
 *
 * <h2>Open-Core Positioning</h2>
 * <p>This is the <b>free-tier</b> provider. It supports standard TCP/TLS workloads
 * via OpenSSL (in the Community Crypto module), but:
 * <ul>
 *   <li>No {@code GlobalMemoryArbiter} — no single global mmap allocation at startup.</li>
 *   <li>No io_uring buffer registration — arenas are not pre-registered with the kernel.</li>
 *   <li>No per-carrier NUMA partitioning — carrier-slab is a no-op hint.</li>
 *   <li>No QUIC — this tier's crypto provider,
 *       {@link eu.exeris.kernel.community.crypto.CommunityKernelCryptoProvider}, reports
 *       {@link eu.exeris.kernel.spi.crypto.KernelCryptoProvider#supportsQuic() supportsQuic()}
 *       = {@code false}.</li>
 * </ul>
 *
 * <h2>Discovery</h2>
 * <p>Registered via {@code META-INF/services/eu.exeris.kernel.spi.memory.MemoryProvider}.
 * Returns {@link #priority()} = 0, the Community slot in the SPI's documented convention
 * (0=Community, 100=Enterprise); no Enterprise {@link MemoryProvider} implementation exists
 * in this repository.
 *
 * @since 0.5
 */
public final class CommunityMemoryProvider implements MemoryProvider {

    /**
     * Constructs the provider that {@link java.util.ServiceLoader} instantiates to resolve the
     * Community {@link MemoryProvider}, per this module's registration under
     * {@code META-INF/services/eu.exeris.kernel.spi.memory.MemoryProvider}.
     */
    public CommunityMemoryProvider() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Creates a {@link CommunityMemoryAllocator} for {@code config}, validating that the
     * configuration matches what this tier supports before construction.
     *
     * @param config provider-specific configuration; must not be {@code null}
     * @return a fully initialised {@link CommunityMemoryAllocator}
     * @throws MemoryBootstrapException ({@code EX-BOOT-0004}) if {@code config} is
     *                                   {@code null}, or if it requests a fixed off-heap
     *                                   budget or a non-default network threshold that
     *                                   this tier does not support
     */
    @Override
    public MemoryAllocator createAllocator(MemoryProviderConfig config) {
        if (config == null) {
            throw new MemoryBootstrapException(providerName(), new NullPointerException("config"));
        }
        try {
            return new CommunityMemoryAllocator(config);
        } catch (IllegalArgumentException e) {
            throw new MemoryBootstrapException(providerName(), e);
        }
    }

    /**
     * Returns {@code "ExerisCommunity/ArenaAllocator"}, this provider's stable display
     * name.
     *
     * @return provider name
     */
    @Override
    public String providerName() {
        return "ExerisCommunity/ArenaAllocator";
    }

    /**
     * Returns {@code 0}, the Community slot in {@link MemoryProvider#priority()}'s
     * documented convention (0=Community, 100=Enterprise). A registered
     * {@link MemoryProvider} with a higher priority would be selected instead per that
     * contract, but no such implementation exists anywhere in this repository.
     *
     * @return {@code 0}
     */
    @Override
    public int priority() {
        return 0;
    }
}


