/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.context;

import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProvider;

/**
 * Central {@link ScopedValue} slots for all SPI providers resolved during bootstrap.
 *
 * <h2>Zero Static Singletons (The Wall)</h2>
 * <p>This class replaces the legacy pattern of {@code static} fields on {@code MemoryManager},
 * {@code TelemetryRouter}, etc. There are no mutable singletons, no double-checked locking,
 * and no {@code ThreadLocal} caches. Every subsystem reads its provider from the scoped slot
 * that was bound by the kernel bootstrapper.
 *
 * <h2>Context Propagation Model (JEP 506)</h2>
 * <p>{@code ScopedValue} slots are inherited by every {@link Thread#startVirtualThread virtual thread}
 * spawned within the binding scope. This means a single {@code ScopedValue.where(...).run(...)}
 * in {@code KernelBootstrap} covers the entire lifetime of the kernel — thousands of virtual
 * threads all read the same provider instances with zero synchronisation overhead.
 *
 * <h2>Binding (bootstrap side)</h2>
 * <pre>{@code
 * ScopedValue
     *     .where(KernelProviders.MEMORY_ALLOCATOR, allocator)
     *     .where(KernelProviders.MEMORY_PROVIDER,  provider)
     *     .where(KernelProviders.CARRIER_INDEX, 0)
     *     .run(kernel::startSubsystems);
 * }</pre>
 *
 * <h2>Reading (subsystem / handler side)</h2>
 * <pre>{@code
 * LoanedBuffer buf = KernelProviders.MEMORY_ALLOCATOR.get()
 *     .allocate(AllocationHint.MEDIUM);
 * }</pre>
 *
 * <h2>CarrierLoop affinity</h2>
 * <p>{@link #CARRIER_INDEX} is re-bound per carrier loop iteration so that the
 * carrier-affine slab pool selection in {@link MemoryAllocator#allocateCarrierSlab(int)}
 * requires no argument threading — the index flows via {@code ScopedValue}.
 *
 * @since 0.5.0
 * @see <a href="../../../../../../docs/subsystems/memory.md">memory.md</a>
 */
public final class KernelProviders {

    /**
     * The active {@link MemoryProvider} factory (bound once during bootstrap).
     *
     * <p>Use this slot only in bootstrap code that needs to introspect or reconfigure
     * the provider. Application code should use {@link #MEMORY_ALLOCATOR} directly.
     */
    public static final ScopedValue<MemoryProvider> MEMORY_PROVIDER = ScopedValue.newInstance();

    /**
     * The kernel-wide {@link MemoryAllocator} (created from {@link #MEMORY_PROVIDER}).
     *
     * <p>This is the primary slot for all allocation calls. It is populated once
     * during bootstrap and inherited by every virtual thread in the kernel scope.
     *
     * <h2>Usage</h2>
     * <pre>{@code
     * try (LoanedBuffer buf = KernelProviders.MEMORY_ALLOCATOR.get()
     *         .allocate(AllocationHint.SMALL)) {
     *     // zero-copy processing
     * }
     * }</pre>
     */
    public static final ScopedValue<MemoryAllocator> MEMORY_ALLOCATOR = ScopedValue.newInstance();

    /**
     * Zero-based index of the current carrier thread within the CarrierLoop pool.
     *
     * <p>Re-bound by the CarrierLoop dispatcher on every iteration so that
     * {@link MemoryAllocator#allocateCarrierSlab(int)} can select the NUMA-local
     * slab pool without requiring an explicit argument at every call site.
     *
     * <p>Defaults to {@code 0} if the scope was not set by a CarrierLoop
     * (e.g., during unit tests). Implementations of {@link MemoryAllocator}
     * MUST handle index {@code 0} as a valid, always-present pool.
     */
    public static final ScopedValue<Integer> CARRIER_INDEX = ScopedValue.newInstance();

    private KernelProviders() {
        // Utility class — static ScopedValue slots only, never instantiated.
    }

    /**
     * Returns the current carrier index, or {@code 0} if the value is not bound
     * (e.g., in unit test contexts outside a CarrierLoop scope).
     *
     * @return carrier index ≥ 0
     */
    public static int carrierIndex() {
        return CARRIER_INDEX.orElse(0);
    }

    /**
     * Returns the active {@link MemoryAllocator} from the current scope.
     *
     * @return allocator bound by the kernel bootstrapper
     * @throws java.util.NoSuchElementException if called outside the kernel scope
     */
    public static MemoryAllocator allocator() {
        return MEMORY_ALLOCATOR.get();
    }
}




