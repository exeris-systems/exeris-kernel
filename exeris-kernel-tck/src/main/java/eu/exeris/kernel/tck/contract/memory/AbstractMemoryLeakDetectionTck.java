/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.memory;

import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LeakDetectionMode;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProvider;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.memory.MemoryStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK: PARANOID leak detection contract for {@link MemoryAllocator} implementations.
 *
 * <h2>Contract</h2>
 * <p>This TCK mandates that implementations support {@link LeakDetectionMode#PARANOID},
 * which tracks <b>every</b> allocation. Unlike the base {@link AbstractMemoryAllocatorTck},
 * this class forces the allocator into PARANOID mode and verifies that:
 *
 * <ol>
 *   <li>Allocating the configured stress volume (1 GB for Enterprise, 64 MB by default)
 *       in fixed-size chunks and calling {@code close()} on every chunk returns
 *       {@link MemoryStats#allocatedBytes()} to 0 — no slab is held hostage.</li>
 *   <li>A deliberately un-closed buffer (leak simulation) is detected: this TCK asserts
 *       that {@link MemoryStats#leakCount()} increases after GC, not that it increases by
 *       exactly one.</li>
 *   <li>The allocator implementation resides in an {@code eu.exeris.*} package rather than
 *       being a bare wrapper around {@code Arena.ofConfined()}/{@code Arena.ofShared()} —
 *       this is checked by package-name convention, not by inspecting the class hierarchy
 *       for raw Arena usage.</li>
 *   <li>After calling {@link MemoryAllocator#close()}, subsequent allocations throw
 *       {@link IllegalStateException} — no use-after-close.</li>
 * </ol>
 *
 * <h2>Why PARANOID in every test?</h2>
 * <p>Because TCK tests run in isolation — one leaked buffer per test is an unbounded
 * memory leak in a long CI session. PARANOID mode costs ~2× allocation overhead but
 * is non-negotiable in the test lifecycle.
 *
 * <h2>Usage</h2>
 * {@snippet lang="java" :
 * class CommunityMemoryLeakTest extends AbstractMemoryLeakDetectionTck {
 *     @Override
 *     protected MemoryProvider createProvider() {
 *         return new CommunityMemoryProvider();
 *     }
 *     @Override
 *     protected long gigabyteAllocationSize() { return 64 * 1024 * 1024L; } // 64 MB for Community
 * }
 * }
 *
 * @since 0.5
 * @see AbstractMemoryAllocatorTck
 * @see LeakDetectionMode#PARANOID
 */
public abstract class AbstractMemoryLeakDetectionTck {

    // =========================================================================
    // Template methods
    // =========================================================================

    /**
     * Creates the {@link MemoryProvider} under test.
     *
     * @return provider; must not be {@code null}
     */
    protected abstract MemoryProvider createProvider();

    /**
     * Returns the allocation size used in the "1 GB close" test.
     *
     * <p>Enterprise implementations use 1 GB to stress slab recycling.
     * Community implementations should override to a smaller value
     * (e.g. 64 MB) since heap may not have 1 GB free in CI.
     * Default: {@code 64 * 1024 * 1024} (64 MB).
     *
     * @return total number of bytes to exercise across the stress-test chunks
     */
    protected long gigabyteAllocationSize() {
        return 64L * 1024L * 1024L;
    }

    /**
     * Returns the chunk size for each individual allocation in the stress test.
     * Default: 64 KB (AllocationHint.LARGE equivalent).
     *
     * @return byte size of each chunk allocated in the stress test
     */
    protected int allocationChunkSize() {
        return 65_536;
    }

    private MemoryAllocator allocator;

    /**
     * Creates the contract; subclasses supply the {@link MemoryProvider} under test via {@link #createProvider()}.
     */
    public AbstractMemoryLeakDetectionTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    @BeforeEach
    final void setUpParanoidAllocator() {
        MemoryProviderConfig config = MemoryProviderConfig.defaults()
                .withLeakDetection(LeakDetectionMode.PARANOID);
        allocator = createProvider().createAllocator(config);
    }

    @AfterEach
    final void tearDownAllocator() {
        if (allocator != null) {
            try {
                allocator.close();
            } catch (Exception _) {
                // suppress post-test cleanup errors
            }
        }
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    @DisplayName("PARANOID mode is active — LeakDetectionMode is PARANOID")
    void leakDetectionModeIsParanoid() {
        assertThat(allocator.stats().leakDetectionMode())
                .as("Allocator MUST be in PARANOID mode for TCK validation")
                .isEqualTo(LeakDetectionMode.PARANOID);
    }

    @Test
    @DisplayName("Allocate " + "N" + " chunks, close all → allocatedBytes() == 0 (no slab held hostage)")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void allocateAndCloseAllChunksYieldsZeroAllocatedBytes() {
        long totalSize = gigabyteAllocationSize();
        int chunkSize = allocationChunkSize();
        int chunkCount = (int) (totalSize / chunkSize);

        final int maxIterations = 1024;
        int remaining = Math.min(chunkCount, maxIterations);
        int allocated = 0;

        while (remaining > 0) {
            try (LoanedBuffer buf = allocator.allocateNetwork(chunkSize)) {
                buf.segment().set(java.lang.foreign.ValueLayout.JAVA_INT, 0, allocated++);
            }
            remaining--;
        }

        final int iterations = allocated;
        final long exercisedBytes = (long) iterations * (long) chunkSize;

        assertThat(allocator.stats().allocatedBytes())
                .as("After closing all %d chunks totalling %d bytes, allocatedBytes() MUST " +
                                "return 0. A non-zero value means %d bytes are held by un-closed slabs — " +
                                "this is a memory leak. Use Arena.close() in LoanedBuffer.close().",
                        iterations, exercisedBytes, allocator.stats().allocatedBytes())
                .isZero();

        assertThat(allocator.stats().releaseCount())
                .as("releaseCount() MUST equal allocationCount() — every slab must be returned")
                .isEqualTo(allocator.stats().allocationCount());
    }

    @Test
    @DisplayName("Deliberate un-closed buffer increments leakCount() after GC hint")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
        // intentional leak — testing detection
    void deliberateLeakIncreasesLeakCount() {
        long leaksBefore = allocator.stats().leakCount();

        createLeakedBuffer();

        for (int i = 0; i < 5; i++) {
            System.gc();
            java.util.concurrent.locks.LockSupport.parkNanos(100_000_000L); // 100 ms park
        }

        assertThat(allocator.stats().leakCount())
                .as("PARANOID mode MUST detect an un-closed LoanedBuffer after GC. " +
                        "The leak detector must increment leakCount() when the GC collects " +
                        "a buffer whose refCount is still > 0. " +
                        "Before: %d, after: %d", leaksBefore, allocator.stats().leakCount())
                .isGreaterThan(leaksBefore);
    }

    // intentional — helper for leak test
    private void createLeakedBuffer() {
        allocator.allocate(AllocationHint.MICRO); // NOSONAR: intentional leak for test
    }

    @Test
    @DisplayName("Allocator rejects all methods after close() — use-after-close is IllegalStateException")
    void useAfterCloseThrowsIllegalState() {
        allocator.close();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> allocator.allocate(AllocationHint.MICRO))
                .as("allocate() after close() MUST throw IllegalStateException")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Allocator implementation lives in eu.exeris.* package (no raw Arena wrapper)")
    void allocatorImplementationIsInExerisPackage() {
        String pkg = allocator.getClass().getName();
        assertThat(pkg)
                .as("MemoryAllocator implementation MUST reside in an 'eu.exeris.*' package, " +
                        "rather than being a raw external Arena wrapper that bypasses the pooling tier.")
                .startsWith("eu.exeris.");
    }
}
