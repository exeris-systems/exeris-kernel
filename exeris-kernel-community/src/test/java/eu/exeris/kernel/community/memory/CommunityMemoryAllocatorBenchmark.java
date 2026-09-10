/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.memory;

import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.tck.perf.AbstractMemoryAllocatorBenchmark;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

/**
 * JMH Benchmark: {@link CommunityMemoryAllocator} throughput vs. heap baseline.
 *
 * <h2>What is measured</h2>
 * <ol>
 *   <li><b>{@code heapBaseline}</b> (inherited) — raw {@code new byte[64]}: TLAB fast-path,
 *       no GC guarantee, establishes the floor for any allocator comparison.</li>
 *   <li><b>{@code acquireAndReleaseSmallBuffer}</b> (inherited) — {@link AllocationHint#MICRO}
 *       RAII loop through the SUT allocator (the allocator under test).</li>
 *   <li><b>{@code communityAllocatorBaseline}</b> — {@link AllocationHint#SMALL} (4 096 bytes)
 *       RAII loop through a dedicated Community allocator instance.
 *       Community {@code Arena.ofAuto()} path (via {@code CommunityArenaBuffers})
 *       at the next slab tier above MICRO.</li>
 * </ol>
 *
 * <h2>Interpretation</h2>
 * <table>
 *   <caption>Expected relative costs (Community tier, ZGC, steady-state)</caption>
 *   <tr><th>Benchmark</th><th>Expected cost</th><th>Bottleneck</th></tr>
 *   <tr><td>{@code heapBaseline}</td><td>~5–15 ns</td><td>TLAB bump-pointer</td></tr>
 *   <tr><td>{@code acquireAndReleaseSmallBuffer} (MICRO)</td><td>~200–800 ns</td><td>Panama arena setup/teardown ({@code Arena.ofAuto()})</td></tr>
 *   <tr><td>{@code communityAllocatorBaseline} (SMALL)</td><td>~200–800 ns</td><td>Panama arena setup/teardown ({@code Arena.ofAuto()})</td></tr>
 * </table>
 * <p>Enterprise tier replaces arena overhead with a VarHandle CAS free-list, targeting &lt;50 ns.
 *
 * <h2>SLO</h2>
 * <ul>
 *   <li>Community allocator throughput: {@code ≥ 500 000 ops/s} (Arena overhead bounded).</li>
 *   <li>Heap baseline must be {@code ≥ 10×} faster than Community allocator.</li>
 * </ul>
 *
 * @since 0.5.0
 */
public class CommunityMemoryAllocatorBenchmark extends AbstractMemoryAllocatorBenchmark {

    /**
     * Dedicated Community allocator instance for the {@link #communityAllocatorBaseline}
     * benchmark. Kept separate from the inherited {@link #allocator} (SUT) so that the
     * SMALL-tier baseline is cleanly isolated from the MICRO-tier hot-path measurement.
     */
    private MemoryAllocator communityAllocator;

    @Override
    protected MemoryAllocator createAllocator() {
        return new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    }

    /**
     * Trial-level setup for the Community SMALL-tier baseline allocator.
     * Executed after the parent {@link #setupAllocator()} — both allocators are
     * fully initialised before any benchmark iteration begins.
     */
    @Setup(Level.Trial)
    public void setupCommunityAllocator() {
        this.communityAllocator = new CommunityMemoryProvider()
                .createAllocator(MemoryProviderConfig.defaults());
    }

    /**
     * Trial-level teardown for the Community baseline allocator.
     * The null-check guards against partial initialisation failures.
     */
    @TearDown(Level.Trial)
    public void tearDownCommunityAllocator() {
        if (communityAllocator != null) {
            communityAllocator.close();
        }
    }

    /**
     * Community SMALL-tier baseline — measures the RAII acquire→use→release loop for
     * {@link AllocationHint#SMALL} (4 096 bytes) through the Community allocator.
     *
     * <p>Unlike {@code acquireAndReleaseSmallBuffer} which targets the {@link AllocationHint#MICRO}
     * tier, this benchmark exercises the next slab size to reveal how allocation cost scales
    * with buffer size in the Community {@code Arena.ofAuto()} ({@code CommunityArenaBuffers}) implementation.
     * The {@link Blackhole#consume} prevents the JIT from eliminating the entire
     * allocate/close pair as dead code.
     *
     * @param bh JMH blackhole — prevents dead-code elimination of the buffer reference
     */
    @Benchmark
    public void communityAllocatorBaseline(Blackhole bh) {
        try (LoanedBuffer buf = communityAllocator.allocate(AllocationHint.SMALL)) {
            bh.consume(buf);
        }
    }
}
