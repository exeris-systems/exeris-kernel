/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.tck.perf;

import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

/**
 * TCK JMH Benchmark: Memory Allocator Throughput.
 *
 * <h2>What is measured</h2>
 * <p>The end-to-end latency of the RAII allocation loop:
 * <ol>
 *   <li>{@link MemoryAllocator#allocate(AllocationHint)} — acquire a micro-slab slot
 *       ({@link AllocationHint#MICRO}, 512 bytes)</li>
 *   <li>{@link LoanedBuffer#segment()} — materialise the Panama FFM {@link java.lang.foreign.MemorySegment}</li>
 *   <li>{@link LoanedBuffer#close()} — return the slot to the pool via VarHandle CAS</li>
 * </ol>
 *
 * <h2>Why this matters</h2>
 * <p>Community implementations back {@code LoanedBuffer} with heap {@code byte[]},
 * meaning every acquire is a GC-eligible {@code new} and every close is a no-op.
 * Enterprise implementations use tiered off-heap slab pools (256 B / 4 KB / 64 KB)
 * and return memory via an {@code AtomicLong} free-list CAS. This benchmark
 * exposes the gap between the two allocation strategies and verifies that the
 * Enterprise path generates zero heap allocations in the steady state.
 *
 * <h2>Small-slab target</h2>
 * <p>{@link AllocationHint#MICRO} (512 bytes) is deliberately chosen to target the smallest
 * slab tier. Benchmarking the micro tier exercises the CAS-intensive pool path most
 * aggressively because acquisition frequency is highest at this size in real workloads
 * (event descriptors, small frame headers).
 *
 * <h2>Implementing this benchmark</h2>
 * <pre>{@code
 * public class MyEnterpriseAllocatorBenchmark
 *         extends AbstractMemoryAllocatorBenchmark {
 *
 *     \@Override
 *     protected MemoryAllocator createAllocator() {
 *         // Return a fully initialised off-heap slab allocator
 *         return new EnterpriseMemoryAllocator(arenaConfig);
 *     }
 * }
 * }</pre>
 *
 * @since 0.5.0
 * @see AbstractExerisBenchmark
 */
public abstract class AbstractMemoryAllocatorBenchmark extends AbstractExerisBenchmark {

    /**
     * Small-slab hint — targets the {@link AllocationHint#MICRO} tier (512 bytes).
     * Benchmarking the micro tier exercises the CAS-intensive pool path most
     * aggressively because acquisition frequency is highest for this size in real
     * workloads (event descriptors, small frame headers, ACK frames).
     */
    private static final AllocationHint SMALL_SLAB = AllocationHint.MICRO;

    /** The allocator under test — initialised once per trial. */
    protected MemoryAllocator allocator;

    /**
     * Implementations must return a fully initialised {@link MemoryAllocator}
     * (off-heap slabs registered, VarHandle free-lists warm) before the first
     * benchmark iteration begins.
     *
     * @return non-null, ready-to-use allocator
     */
    protected abstract MemoryAllocator createAllocator();

    /**
     * Trial-level setup: materialises the allocator so that the benchmark measurement
     * window exercises only steady-state allocation, not initialisation overhead.
     */
    @Setup(Level.Trial)
    public void setupAllocator() {
        this.allocator = createAllocator();
    }

    /**
     * Trial-level teardown: closes the allocator to release native off-heap slabs
     * and prevent resource leaks across JMH trials and forks. The null-check makes
     * this safe even when a trial's {@link #setupAllocator()} threw before assignment.
     */
    @TearDown(Level.Trial)
    public void tearDownAllocator() {
        if (allocator != null) {
            allocator.close();
        }
    }

    /**
     * Hot-path benchmark — measures the RAII acquire→use→release loop for the
     * {@link AllocationHint#MICRO} (512-byte) slab tier.
     *
     * <p>The {@link LoanedBuffer#segment()} call forces the implementation to materialise
     * the Panama {@link java.lang.foreign.MemorySegment} view (or return the cached
     * segment field). The {@link Blackhole#consume} prevents the JIT from eliminating
     * the segment reference as dead code. The try-with-resources block guarantees that
     * {@link LoanedBuffer#close()} is called exactly once, returning the slab slot to
     * the pool and exercising the VarHandle CAS return path (Enterprise).
     *
     * @param bh JMH blackhole — prevents dead-code elimination of the segment access
     */
    @Benchmark
    public void acquireAndReleaseSmallBuffer(Blackhole bh) {
        // Measure the RAII loop: acquire → materialise segment → release.
        // try-with-resources ensures close() is called even on exception paths,
        // keeping the allocator's slab pool in a consistent state across iterations.
        try (LoanedBuffer buffer = allocator.allocate(SMALL_SLAB)) {
            // Force segment materialisation — prevents JIT from hoisting the
            // entire allocate/close pair out of the loop as dead code.
            bh.consume(buffer.segment());
        }
    }
}





