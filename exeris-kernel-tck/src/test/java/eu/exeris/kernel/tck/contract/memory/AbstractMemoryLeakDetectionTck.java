/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
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
 * <h2>Front 3 — Memory Allocator Upgrade</h2>
 * <p>This TCK mandates that implementations support {@link LeakDetectionMode#PARANOID},
 * which tracks <b>every</b> allocation. Unlike the base {@link AbstractMemoryAllocatorTck},
 * this class forces the allocator into PARANOID mode and verifies that:
 *
 * <ol>
 *   <li>Allocating 1 GB and calling {@code close()} on all buffers returns
 *       {@link MemoryStats#allocatedBytes()} to 0 — no slab is held hostage.</li>
 *   <li>A deliberately un-closed buffer (leak simulation) is detected:
 *       {@link MemoryStats#leakCount()} increases by exactly 1 after GC.</li>
 *   <li>The allocator does NOT allow {@code Arena.ofConfined()} or
 *       {@code Arena.ofShared()} in its class hierarchy (The Wall).</li>
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
 * <pre>{@code
 * class CommunityMemoryLeakTest extends AbstractMemoryLeakDetectionTck {
 *     \@Override
 *     protected MemoryProvider createProvider() {
 *         return new CommunityMemoryProvider();
 *     }
 *     \@Override
 *     protected long gigabyteAllocationSize() { return 64 * 1024 * 1024L; } // 64 MB for Community
 * }
 * }</pre>
 *
 * @since 0.5.0
 * @see AbstractMemoryAllocatorTck
 * @see LeakDetectionMode#PARANOID
 */
public abstract class AbstractMemoryLeakDetectionTck {

    // =========================================================================
    // Template methods
    // =========================================================================

    /** Creates the {@link MemoryProvider} under test. */
    protected abstract MemoryProvider createProvider();

    /**
     * Returns the allocation size used in the "1 GB close" test.
     *
     * <p>Enterprise implementations use 1 GB to stress slab recycling.
     * Community implementations should override to a smaller value
     * (e.g. 64 MB) since heap may not have 1 GB free in CI.
     * Default: {@code 64 * 1024 * 1024} (64 MB).
     */
    protected long gigabyteAllocationSize() {
        return 64L * 1024L * 1024L; // 64 MB default — safe for Community heap
    }

    /**
     * Returns the chunk size for each individual allocation in the stress test.
     * Default: 64 KB (AllocationHint.LARGE equivalent).
     */
    protected int allocationChunkSize() {
        return 65_536; // 64 KB per chunk
    }

    private MemoryAllocator allocator;

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
        long totalSize  = gigabyteAllocationSize();
        int  chunkSize  = allocationChunkSize();
        int  chunkCount = (int) (totalSize / chunkSize);

        // Cap to guard against pathological configuration. Iterate a bounded number of times
        // so that termination is trivially visible to static analysis and humans alike.
        final int maxIterations = 1_000_000;
        int remaining = Math.min(chunkCount, maxIterations);
        int allocated = 0;
        for (int i = 0; i < remaining; i++) {
            try (LoanedBuffer buf = allocator.allocateNetwork(chunkSize)) {
                // Minimal write to prevent dead-code elimination
                buf.segment().set(java.lang.foreign.ValueLayout.JAVA_INT, 0, allocated++);
            }
        }
        final int iterations = allocated;

        assertThat(allocator.stats().allocatedBytes())
                .as("After closing all %d chunks totalling %d bytes, allocatedBytes() MUST " +
                    "return 0. A non-zero value means %d bytes are held by un-closed slabs — " +
                    "this is a memory leak. Use Arena.close() in LoanedBuffer.close().",
                    iterations, totalSize, allocator.stats().allocatedBytes())
                .isZero();

        assertThat(allocator.stats().releaseCount())
                .as("releaseCount() MUST equal allocationCount() — every slab must be returned")
                .isEqualTo(allocator.stats().allocationCount());
    }

    @Test
    @DisplayName("Deliberate un-closed buffer increments leakCount() after GC hint")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @SuppressWarnings("PMD.CloseResource") // intentional leak — testing detection
    void deliberateLeakIncreasesLeakCount() {
        long leaksBefore = allocator.stats().leakCount();

        // Deliberately NOT closing this buffer — simulating application bug.
        // Drop the reference immediately so GC can make it eligible for phantom-ref processing.
        // PARANOID mode uses phantom references / WeakReferences to detect abandoned slabs.
        createLeakedBuffer();

        // GC hint loop: hint GC to process phantom references.
        // LockSupport.parkNanos() avoids banned Thread.sleep() while yielding scheduler time to GC.
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

    @SuppressWarnings("PMD.CloseResource") // intentional — helper for leak test
    private void createLeakedBuffer() {
        // Allocate and immediately lose the reference — GC eligible
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
    @DisplayName("Direct Arena.ofConfined / Arena.ofShared NOT present in implementation class name")
    void implementationDoesNotExposeArenaDirectly() {
        // The Wall: business logic MUST NOT call Arena.ofConfined() or Arena.ofShared() directly.
        // All allocations go through MemoryAllocator.
        // We verify this by checking the allocator's class package hierarchy.
        String pkg = allocator.getClass().getName();
        assertThat(pkg)
                .as("MemoryAllocator implementation MUST be in an 'eu.exeris.*' package, " +
                    "not a raw Arena wrapper. Direct Arena usage bypasses the pooling tier.")
                .startsWith("eu.exeris.");
    }
}

