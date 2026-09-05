/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.memory;

import eu.exeris.kernel.spi.exceptions.memory.MemoryExhaustedException;

/**
 * SPI: Unified, pluggable memory-allocation facade — the single entry-point through
 * which <em>all</em> kernel subsystems acquire {@link LoanedBuffer} instances.
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>This interface is <strong>implementation-agnostic</strong>: it does not reference
 * {@code io_uring}, {@code Arena.ofConfined()}, {@code ByteBuffer}, {@code sun.misc.Unsafe},
 * or any community/enterprise driver. Callers are decoupled from the allocation strategy.
 *
 * <h2>Allocation Tiers (Community vs Enterprise)</h2>
 * <table>
 *   <tr><th>Method</th><th>Community (Heap/Arena)</th><th>Enterprise (mmap/io_uring)</th></tr>
 *   <tr><td>{@link #allocate(AllocationHint)}</td><td>Pooled heap</td><td>Slab from GlobalArbiter</td></tr>
 *   <tr><td>{@link #allocateNetwork(int)}</td><td>ElasticCluster</td><td>FixedCluster (io_uring registered)</td></tr>
 *   <tr><td>{@link #allocateCarrierSlab(int)}</td><td>ArenaPool</td><td>Per-carrier mmapped region</td></tr>
 *   <tr><td>{@link #allocateInfrastructure(long)}</td><td>SharedArena</td><td>Partitioned from Arbiter</td></tr>
 * </table>
 *
 * <h2>Thread Safety</h2>
 * <p>All methods MUST be safe for concurrent invocation from Virtual Threads.
 * The {@link #allocateCarrierSlab(int)} method provides an affinity-hint: the caller
 * supplies the carrier-index so the implementation can avoid cross-NUMA contention.
 *
 * <h2>Lifecycle</h2>
 * <p>Created once by {@link MemoryProvider#createAllocator} during bootstrap.
 * After {@link #close()}, all allocation methods throw {@link IllegalStateException}.
 *
 * @see MemoryProvider
 * @see LoanedBuffer
 * @since 0.5
 */
public interface MemoryAllocator extends AutoCloseable {

    // =========================================================================
    // Semantic "T-Shirt Size" allocation  (preferred hot-path API)
    // =========================================================================

    /**
     * Allocates a buffer using a semantic size hint.
     *
     * <p>This is the <strong>recommended hot-path API</strong>. Business logic should never
     * hardcode byte sizes; hints decouple call sites from tier-specific pool sizes.
     *
     * @param hint semantic size hint
     * @return loaned buffer; caller MUST close via try-with-resources
     * @throws MemoryExhaustedException if the allocation cannot be satisfied
     * @throws IllegalStateException    if this allocator has been closed
     */
    LoanedBuffer allocate(AllocationHint hint);

    // =========================================================================
    // Network allocation (transport hot-path)
    // =========================================================================

    /**
     * Allocates a network buffer sized by an estimated payload in bytes.
     *
     * <p><b>Tier selection contract</b> (implementation detail — hidden from callers):
     * <ul>
     *   <li>Size &lt; configured threshold → Heap pool (~50 ns, no lock)</li>
     *   <li>Size &ge; configured threshold → Off-Heap slab (amortised for large frames)</li>
     * </ul>
     *
     * @param estimatedBytes estimated payload size in bytes (used as a pool-selection hint)
     * @return loaned buffer
     * @throws MemoryExhaustedException if the allocation cannot be satisfied
     * @throws IllegalStateException    if this allocator has been closed
     */
    LoanedBuffer allocateNetwork(int estimatedBytes);

    // =========================================================================
    // CarrierLoop slab allocation (per-carrier affinity)
    // =========================================================================

    /**
     * Allocates a buffer from the slab pool that is affine to the given carrier index.
     *
     * <p>This method is intended for use inside a <em>CarrierLoop</em> — the tight
     * event-processing loop that runs on a platform (carrier) thread servicing many
     * Virtual Threads. By supplying a {@code carrierIndex}, the implementation can select
     * a NUMA-local or CPU-affine pool to avoid cross-carrier cache-line bouncing.
     *
     * <p><b>Virtual-Thread safety:</b> the returned buffer is ref-counted and fully safe
     * to pass across virtual-thread boundaries. The carrier index is only a <em>hint</em>
     * to guide pool selection — it does not pin the buffer to that carrier.
     *
     * @param carrierIndex zero-based index of the calling carrier thread
     *                     (obtained from {@code KernelProviders.CARRIER_INDEX})
     * @return loaned buffer from the carrier-affine slab pool
     * @throws MemoryExhaustedException if no slab is available in the carrier's pool
     * @throws IllegalStateException    if this allocator has been closed
     */
    LoanedBuffer allocateCarrierSlab(int carrierIndex);

    // =========================================================================
    // Infrastructure allocation (kernel-internal, non-transport)
    // =========================================================================

    /**
     * Allocates an infrastructure block for kernel-internal operations.
     *
     * <p>Use cases: protocol-state buffers, sockaddr holders, projection-cache slabs,
     * TLS session buffers. Never use for application-level data.
     *
     * <p>The backing strategy (temporary arena, global-arbiter partition, or dedicated
     * mmap region) is an implementation detail transparent to this SPI.
     *
     * @param sizeBytes requested block size in bytes
     * @return loaned buffer wrapping the infrastructure segment
     * @throws MemoryExhaustedException if the off-heap tier is exhausted
     * @throws IllegalStateException    if this allocator has been closed
     */
    LoanedBuffer allocateInfrastructure(long sizeBytes);

    // =========================================================================
    // Diagnostics
    // =========================================================================

    /**
     * Returns a point-in-time snapshot of memory statistics.
     *
     * <p>This is a diagnostic / JFR path — not for use in hot allocation loops.
     *
     * @return current allocation metrics
     */
    MemoryStats stats();

    // =========================================================================
    // Background maintenance (low-frequency, NEVER resizes the pool)
    // =========================================================================

    /**
     * Performs low-frequency background maintenance on this allocator.
     *
     * <h2>What maintenance IS</h2>
     * <ul>
     *   <li><b>Telemetry sampling</b>: reads free-slab counts (O(n) — acceptable
     *       off hot-path) and emits metrics to {@code KernelProviders.TELEMETRY_SINKS}.</li>
     *   <li><b>Deferred zeroing (Enterprise optional)</b>: if buffers are not zeroed on
     *       return to the pool (to save CPU cycles on the hot-path), maintenance zeroes
     *       them in the background.</li>
     * </ul>
     *
     * <h2>What maintenance is NOT</h2>
     * <p>This method MUST NOT call {@code mmap}, {@code munmap}, or any OS syscall that
     * adds or removes pages. The Enterprise tier makes a single, irrevocable allocation
     * at bootstrap and holds it for the JVM lifetime. Resizing post-bootstrap causes
     * Page Faults, destroys sub-millisecond latencies, and breaks the Zero-GC contract.
     *
     * <h2>Entropy Contract</h2>
     * <p><em>"If you are out of slabs, throw {@link eu.exeris.kernel.spi.exceptions.memory.MemoryExhaustedException}.
     * Do not silently ask the OS for more. You either planned for the load, or you did not."</em>
     *
     * <h2>Invocation</h2>
     * <p>Called by the kernel background maintenance Virtual Thread — typically every
     * 10 seconds. Never call from the io_uring event loop or any carrier thread.
     */
    default void performMaintenance() {
        // no-op by default — Enterprise override emits slab-pool metrics to TelemetrySinks
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /**
     * Releases all resources held by this allocator.
     * <p>Must be idempotent. After {@code close()}, all allocation methods throw
     * {@link IllegalStateException}.
     */
    @Override
    void close();
}
