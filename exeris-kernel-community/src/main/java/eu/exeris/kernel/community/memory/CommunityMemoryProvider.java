/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.memory;

import eu.exeris.kernel.spi.exceptions.memory.MemoryBootstrapException;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProvider;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;

/**
 * Community: {@link MemoryProvider} that creates syscall-based {@link MemoryAllocator}
 * instances using per-buffer {@code Arena.ofAuto()} allocations.
 *
 * <h2>Open-Core Positioning</h2>
 * <p>This is the <b>free-tier</b> provider. It supports standard TCP/TLS workloads
 * via OpenSSL (in the Community Crypto module), but:
 * <ul>
 *   <li>No {@code GlobalMemoryArbiter} — no single global mmap allocation at startup.</li>
 *   <li>No io_uring buffer registration — arenas are not pre-registered with the kernel.</li>
 *   <li>No per-carrier NUMA partitioning — carrier-slab is a no-op hint.</li>
 *   <li>No QUIC — transport subsystems fall back to TCP when
 *       {@link eu.exeris.kernel.spi.crypto.KernelCryptoProvider#supportsQuic()} returns {@code false}.</li>
 * </ul>
 *
 * <h2>Discovery</h2>
 * <p>Registered via {@code META-INF/services/eu.exeris.kernel.spi.memory.MemoryProvider}.
 * Returns {@link #priority()} = 0; Enterprise provider (priority 100) wins when both are present.
 *
 * @since 0.5.0
 */
public final class CommunityMemoryProvider implements MemoryProvider {


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

    @Override
    public String providerName() {
        return "ExerisCommunity/ArenaAllocator";
    }

    @Override
    public int priority() {
        return 0; // Enterprise wins with 100
    }
}


