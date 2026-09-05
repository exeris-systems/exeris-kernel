/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.memory;

import eu.exeris.kernel.spi.exceptions.memory.MemoryBootstrapException;

/**
 * SPI: Factory for creating {@link MemoryAllocator} instances.
 *
 * <p>Discovered via {@link java.util.ServiceLoader}. Each tier (Community/Enterprise)
 * provides exactly one binding. The {@code exeris-kernel-core} bootstrapper loads the
 * highest-priority provider and calls {@link #createAllocator} once during startup.
 *
 * <h2>The Wall (Open-Core)</h2>
 * <ul>
 *   <li><b>Community binding</b> (free): Heap pool + Panama Arena cluster.</li>
 *   <li><b>Enterprise binding</b> (secret sauce): GlobalArbiter + io_uring-registered slabs
 *       + per-carrier mmapped regions. This binding lives in {@code exeris-kernel-enterprise}
 *       and must <em>never</em> be referenced from this SPI.</li>
 * </ul>
 *
 * <h2>Usage (bootstrap)</h2>
 * {@snippet lang="java" :
 * MemoryProvider provider = ServiceLoader.load(MemoryProvider.class)
 *     .findFirst()
 *     .orElseThrow(() -> new MemoryBootstrapException("No MemoryProvider found"));
 *
 * MemoryAllocator allocator = provider.createAllocator(config);
 * ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator).run(kernel::start);
 * }
 *
 * <p><b>Allocation:</b> allocates — {@link #createAllocator} may reserve the off-heap budget
 * named by {@link MemoryProviderConfig#totalOffHeapBytes()} in one bootstrap-time
 * reservation; nothing on this interface runs on a hot path.
 * <p><b>Thread confinement:</b> owner thread — the bootstrap thread calls
 * {@link #createAllocator} once, and that call may block on syscalls.
 * <p><b>Ownership:</b> the caller owns the returned {@link MemoryAllocator} and is expected to
 * close it during its own shutdown path, which is what releases the reservation. Whether that
 * close actually happens depends on the caller — a
 * {@link eu.exeris.kernel.spi.bootstrap.Subsystem} — correctly reporting
 * {@link eu.exeris.kernel.spi.bootstrap.Subsystem#isRunning() isRunning()} as {@code true} once
 * it holds the allocator, since the orchestrator's shutdown only reaches a subsystem's
 * {@code stop()} when {@code isRunning()} is true.
 *
 * @implSpec A provider must return a fully initialised allocator or throw — never a
 *           half-built one.
 * @since 0.5
 * @see MemoryAllocator
 * @see MemoryProviderConfig
 */
public interface MemoryProvider {

    /**
     * Creates and initialises the runtime's one {@link MemoryAllocator}, reserving whatever
     * off-heap memory {@code config} asks for before returning.
     *
     * <p>This is a blocking call: it may perform off-heap reservations or mmap syscalls.
     *
     * @param config provider-specific configuration
     * @return fully initialised allocator ready for allocation
     * @throws MemoryBootstrapException if the allocator cannot be created — insufficient
     *                                  off-heap memory, a missing native library, a refused
     *                                  mmap ({@code EX-BOOT-0004})
     * @apiNote Call it from the bootstrap thread. It must not run on a virtual thread that
     *          is expected not to block, and it is not a lazy initialiser: by the time it
     *          returns, the budget is reserved.
     */
    MemoryAllocator createAllocator(MemoryProviderConfig config);

    /**
     * Returns the human-readable identity of this provider, used to attribute bootstrap JFR
     * events and {@code EX-BOOT-0004} failures to a tier — for example
     * {@code "ExerisEnterprise/GlobalArbiter"}.
     *
     * @return provider name
     * @implSpec Must be a stable string constant: diagnostics correlate on it, so it must not
     *           vary between calls or embed runtime state.
     */
    String providerName();

    /**
     * Returns the rank this provider claims when several are on the classpath; the highest
     * rank wins, which is how the Enterprise binding displaces the Community one.
     *
     * @return priority; community implementations should return {@code 0},
     *         enterprise implementations {@code 100}
     * @implSpec The default returns {@code 0}, the Community rank. An override must return a
     *           constant, not a value derived from runtime state: provider selection happens
     *           once, at bootstrap, and a rank that moves afterwards changes nothing.
     */
    default int priority() {
        return 0;
    }
}
