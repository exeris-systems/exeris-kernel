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
 * <pre>{@code
 * MemoryProvider provider = ServiceLoader.load(MemoryProvider.class)
 *     .findFirst()
 *     .orElseThrow(() -> new MemoryBootstrapException("No MemoryProvider found"));
 *
 * MemoryAllocator allocator = provider.createAllocator(config);
 * ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator).run(kernel::start);
 * }</pre>
 *
 * @see MemoryAllocator
 * @see MemoryProviderConfig
 * @since 0.5.0
 */
public interface MemoryProvider {

    /**
     * Creates and initialises a new {@link MemoryAllocator} from the given configuration.
     *
     * <p>This is a blocking call; it may perform off-heap reservations or mmap syscalls.
     * It MUST NOT be called on a virtual thread that is expected to be non-blocking.
     *
     * @param config provider-specific configuration
     * @return fully initialised allocator ready for allocation
     * @throws MemoryBootstrapException if the allocator cannot be created (e.g.,
     *                                  insufficient off-heap memory, missing native library)
     */
    MemoryAllocator createAllocator(MemoryProviderConfig config);

    /**
     * Returns the display name of this provider (e.g., {@code "ExerisEnterprise/GlobalArbiter"}).
     *
     * <p>Used in bootstrap JFR events and diagnostics. Must be a stable string constant.
     *
     * @return provider name
     */
    String providerName();

    /**
     * Returns the priority of this provider. Higher value wins when multiple providers
     * are on the classpath (e.g., enterprise overrides community).
     *
     * @return priority; community implementations should return {@code 0},
     * enterprise implementations {@code 100}
     */
    default int priority() {
        return 0;
    }
}
