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
 *   <caption>Backing store each allocation method draws from, per tier</caption>
 *   <tr><th>Method</th><th>Community (Heap/Arena)</th><th>Enterprise (mmap/io_uring)</th></tr>
 *   <tr><td>{@link #allocate(AllocationHint)}</td><td>Pooled heap</td><td>Slab from GlobalArbiter</td></tr>
 *   <tr><td>{@link #allocateNetwork(int)}</td><td>ElasticCluster</td><td>FixedCluster (io_uring registered)</td></tr>
 *   <tr><td>{@link #allocateCarrierSlab(int)}</td><td>ArenaPool</td><td>Per-carrier mmapped region</td></tr>
 *   <tr><td>{@link #allocateInfrastructure(long)}</td><td>SharedArena</td><td>Partitioned from Arbiter</td></tr>
 * </table>
 *
 * <h2>Lifecycle</h2>
 * <p>An allocator is created once by {@link MemoryProvider#createAllocator} during bootstrap
 * and serves the whole runtime until it is closed at shutdown.
 *
 * <p><b>Allocation:</b> allocates — one {@link LoanedBuffer} handle per allocation call and
 * one {@link MemoryStats} record per {@link #stats()} call; the buffers' backing storage is
 * pooled, so an allocation on the hot path never reserves fresh memory from the OS.
 * <p><b>Thread confinement:</b> virtual-thread-safe — every method may be invoked
 * concurrently; the {@code carrierIndex} of {@link #allocateCarrierSlab(int)} is a
 * pool-selection hint, not a confinement of the buffer it returns.
 * <p><b>Ownership:</b> the caller owns each returned {@link LoanedBuffer} and releases it via
 * {@code close()}; the allocator itself is owned by the subsystem that created it, which is
 * expected to release it from its own {@code stop()}. That release is only reached if the
 * owning subsystem correctly reports {@link eu.exeris.kernel.spi.bootstrap.Subsystem#isRunning()}
 * as {@code true} once it holds the allocator, since the orchestrator calls {@code stop()} only
 * for a subsystem whose {@code isRunning()} is true.
 *
 * @implSpec All methods must be safe for concurrent invocation from virtual threads. When a
 *           pool is exhausted an implementation must fail with
 *           {@link MemoryExhaustedException} ({@code EX-MEM-1001}) rather than grow the pool.
 *           After {@link #close()}, every allocation method must throw
 *           {@link IllegalStateException} rather than hand out a buffer over released memory.
 * @since 0.5
 * @see MemoryProvider
 * @see LoanedBuffer
 */
public interface MemoryAllocator extends AutoCloseable {

    // =========================================================================
    // Semantic "T-Shirt Size" allocation  (preferred hot-path API)
    // =========================================================================

    /**
     * Allocates a buffer of at least {@link AllocationHint#sizeBytes()} bytes from the pool
     * bucket the active tier maps that hint to.
     *
     * <p>This is the <strong>recommended hot-path API</strong>. Business logic should never
     * hardcode byte sizes; hints decouple call sites from tier-specific pool sizes.
     *
     * @param hint semantic size hint
     * @return a loaned buffer with reference count 1; the caller must close it, preferably
     *         via try-with-resources
     * @throws MemoryExhaustedException if the allocation cannot be satisfied
     *                                  ({@code EX-MEM-1001})
     * @throws IllegalStateException    if this allocator has been closed
     */
    LoanedBuffer allocate(AllocationHint hint);

    // =========================================================================
    // Network allocation (transport hot-path)
    // =========================================================================

    /**
     * Allocates a buffer for a transport frame whose payload is expected to be about
     * {@code estimatedBytes} long.
     *
     * <p>Which storage backs the buffer — heap pool or off-heap slab — is chosen by the
     * implementation from that estimate and stays hidden from the caller; only
     * {@link LoanedBuffer#capacity()} is binding.
     *
     * @param estimatedBytes estimated payload size in bytes (used as a pool-selection hint)
     * @return a loaned buffer with reference count 1; the caller must close it
     * @throws MemoryExhaustedException if the allocation cannot be satisfied
     *                                  ({@code EX-MEM-1001})
     * @throws IllegalStateException    if this allocator has been closed
     * @implNote The tier split is threshold-driven: below
     *           {@link MemoryProviderConfig#networkOffHeapThreshold()} the buffer comes from
     *           the heap pool, at or above it from an off-heap slab whose cost amortises over
     *           large frames.
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
     * @return a loaned buffer from the carrier-affine slab pool, with reference count 1;
     *         the caller must close it
     * @throws MemoryExhaustedException if no slab is available in the carrier's pool
     *                                  ({@code EX-MEM-1001})
     * @throws IllegalStateException    if this allocator has been closed
     * @implNote The Community allocator keeps one shared pool: it ignores
     *           {@code carrierIndex} and returns a
     *           {@link AllocationHint#STREAMING_CHUNK}-sized buffer.
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
     * @return a loaned buffer wrapping the infrastructure segment, with reference count 1;
     *         the caller must close it
     * @throws MemoryExhaustedException if the off-heap tier is exhausted
     *                                  ({@code EX-MEM-1001})
     * @throws IllegalStateException    if this allocator has been closed
     * @implNote The Community allocator rejects a non-positive {@code sizeBytes} with
     *           {@link IllegalArgumentException} before consulting the pool.
     */
    LoanedBuffer allocateInfrastructure(long sizeBytes);

    // =========================================================================
    // Diagnostics
    // =========================================================================

    /**
     * Returns a point-in-time snapshot of this allocator's byte budget, live-buffer counts
     * and leak counter.
     *
     * @return current allocation metrics; never {@code null}
     * @apiNote This is the diagnostic and JFR path. It allocates a {@link MemoryStats}
     *          record per call, so read it from a telemetry or maintenance path — never
     *          from a hot allocation loop.
     */
    MemoryStats stats();

    // =========================================================================
    // Background maintenance (low-frequency, NEVER resizes the pool)
    // =========================================================================

    /**
     * Performs low-frequency background housekeeping on this allocator: it samples pool
     * occupancy for telemetry and may zero pooled buffers that were returned without being
     * cleared, but it never changes how much memory the allocator holds.
     *
     * @implSpec An implementation must not call {@code mmap}, {@code munmap}, or any other OS
     *           syscall that adds or removes pages — the memory budget is fixed once, at
     *           bootstrap, and held for the lifetime of the JVM. Resizing after bootstrap
     *           causes page faults, destroys sub-millisecond latencies and breaks the Zero-GC
     *           contract. When slabs run out the allocator fails with
     *           {@link MemoryExhaustedException} ({@code EX-MEM-1001}); it never quietly asks
     *           the OS for more. Telemetry sampling may read free-slab counts in O(n) — that
     *           cost is acceptable here because this method is off the hot path — and emits
     *           through {@code KernelProviders.TELEMETRY_SINKS}.
     * @apiNote Call this only from the kernel's background maintenance virtual thread, on a
     *          cadence of seconds — never from a transport event loop or a carrier thread,
     *          where its O(n) scan would be paid per request.
     * @implNote The default implementation does nothing; the kernel's maintenance task invokes
     *           it every 10 seconds, and the Enterprise allocator overrides it to emit
     *           slab-pool metrics and to run deferred zeroing.
     */
    default void performMaintenance() {
        // no-op by default — Enterprise override emits slab-pool metrics to TelemetrySinks
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /**
     * Releases every pool and arena held by this allocator, ending the validity of any
     * segment still loaned out.
     *
     * @implSpec Must be idempotent, and after the first call every allocation method must
     *           throw {@link IllegalStateException}.
     * @apiNote Called once by the bootstrapper at shutdown, after the drain sequence has
     *          given in-flight buffers their chance to close. A buffer still held past that
     *          point is not protected: its backing segment is gone.
     */
    @Override
    void close();
}
