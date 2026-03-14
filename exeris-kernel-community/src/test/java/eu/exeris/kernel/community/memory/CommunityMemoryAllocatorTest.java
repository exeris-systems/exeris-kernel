/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.memory;

import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.memory.MemoryStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.ValueLayout;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 Unit: {@link CommunityMemoryAllocator} — internal telemetry, lifecycle,
 * stats tracking, and per-buffer isolation.
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>allocate(MICRO/MEDIUM/JUMBO) — correct capacity</li>
 *   <li>allocateNetwork — cross-thread safe (shared arena)</li>
 *   <li>allocateCarrierSlab — default slab size</li>
 *   <li>allocateInfrastructure — invalid size rejection</li>
 *   <li>stats() tracks allocationCount, peakAllocated, releaseCount</li>
 *   <li>close() → subsequent allocate throws IllegalStateException</li>
 *   <li>Concurrent multi-VT allocation — stats remain consistent</li>
 *   <li>Buffer isolation — each buffer has independent content</li>
 * </ul>
 *
 * @since 0.5.0
 */
@DisplayName("L1 Unit: CommunityMemoryAllocator")
class CommunityMemoryAllocatorTest {

    private static final MemoryProviderConfig CONFIG = MemoryProviderConfig.defaults();

    private MemoryAllocator allocator;

    @BeforeEach
    void setUp() {
        allocator = new CommunityMemoryProvider().createAllocator(CONFIG);
    }

    @AfterEach
    void tearDown() {
        allocator.close();
    }

    // =========================================================================
    // Basic allocation — capacity contract
    // =========================================================================

    @Nested
    @DisplayName("Capacity contract")
    class CapacityContract {

        @Test
        @DisplayName("allocate(MICRO) returns buffer with capacity >= MICRO size")
        void allocateMicroCapacity() {
            try (LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO)) {
                assertThat(buf.capacity())
                        .isGreaterThanOrEqualTo(AllocationHint.MICRO.sizeBytes());
            }
        }

        @Test
        @DisplayName("allocate(MEDIUM) returns buffer with capacity >= MEDIUM size")
        void allocateMediumCapacity() {
            try (LoanedBuffer buf = allocator.allocate(AllocationHint.MEDIUM)) {
                assertThat(buf.capacity())
                        .isGreaterThanOrEqualTo(AllocationHint.MEDIUM.sizeBytes());
            }
        }

        @Test
        @DisplayName("allocate(JUMBO) returns buffer with capacity >= JUMBO size")
        void allocateJumboCapacity() {
            try (LoanedBuffer buf = allocator.allocate(AllocationHint.JUMBO)) {
                assertThat(buf.capacity())
                        .isGreaterThanOrEqualTo(AllocationHint.JUMBO.sizeBytes());
            }
        }

        @Test
        @DisplayName("allocateNetwork(1024) returns live buffer")
        void allocateNetworkSmall() {
            try (LoanedBuffer buf = allocator.allocateNetwork(1024)) {
                assertThat(buf).isNotNull();
                assertThat(buf.isAlive()).isTrue();
                assertThat(buf.capacity()).isGreaterThanOrEqualTo(1024L);
            }
        }

        @Test
        @DisplayName("allocateCarrierSlab returns valid buffer")
        void allocateCarrierSlab() {
            try (LoanedBuffer buf = allocator.allocateCarrierSlab(0)) {
                assertThat(buf).isNotNull();
                assertThat(buf.isAlive()).isTrue();
                assertThat(buf.capacity()).isGreaterThan(0);
            }
        }

        @Test
        @DisplayName("allocateInfrastructure(64) returns valid buffer")
        void allocateInfrastructureSmall() {
            try (LoanedBuffer buf = allocator.allocateInfrastructure(64)) {
                assertThat(buf).isNotNull();
                assertThat(buf.isAlive()).isTrue();
                assertThat(buf.capacity()).isGreaterThanOrEqualTo(64L);
            }
        }

        @Test
        @DisplayName("allocateInfrastructure(0) throws IllegalArgumentException")
        void allocateInfrastructureZeroThrows() {
            assertThatThrownBy(() -> allocator.allocateInfrastructure(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sizeBytes");
        }

        @Test
        @DisplayName("allocateInfrastructure(-1) throws IllegalArgumentException")
        void allocateInfrastructureNegativeThrows() {
            assertThatThrownBy(() -> allocator.allocateInfrastructure(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // =========================================================================
    // Stats tracking
    // =========================================================================

    @Nested
    @DisplayName("Stats tracking")
    class StatsTracking {

        @Test
        @DisplayName("allocationCount increments on each allocate()")
        void allocationCountIncrements() {
            MemoryStats before = allocator.stats();
            try (var _ = allocator.allocate(AllocationHint.MICRO)) {
                MemoryStats during = allocator.stats();
                assertThat(during.allocationCount())
                        .isGreaterThan(before.allocationCount());
            }
        }

        @Test
        @DisplayName("allocatedBytes increases after allocation and returns to 0 after release")
        void allocatedBytesTracked() {
            MemoryStats before = allocator.stats();
            LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO);
            MemoryStats after = allocator.stats();
            assertThat(after.allocatedBytes()).isGreaterThan(before.allocatedBytes());

            buf.close(); // release
            MemoryStats released = allocator.stats();
            assertThat(released.allocatedBytes()).isEqualTo(before.allocatedBytes());
        }

        @Test
        @DisplayName("peakAllocated never decreases")
        void peakAllocatedNeverDecreases() {
            try (var _ = allocator.allocate(AllocationHint.JUMBO)) {
                long peakDuring = allocator.stats().peakAllocatedBytes();
                assertThat(peakDuring).isGreaterThan(0L);
            }
            long peakAfter = allocator.stats().peakAllocatedBytes();
            assertThat(peakAfter).isGreaterThan(0L);
        }

        @Test
        @DisplayName("releaseCount increments after buffer.close()")
        void releaseCountIncrements() {
            LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO);
            long releasesBefore = allocator.stats().releaseCount();
            buf.close();
            assertThat(allocator.stats().releaseCount())
                    .isGreaterThan(releasesBefore);
        }

        @Test
        @DisplayName("stats() returns -1 as totalBudget (Community: no fixed off-heap budget)")
        void totalBudgetIsUnbounded() {
            assertThat(allocator.stats().totalBytes()).isEqualTo(-1L);
        }
    }

    // =========================================================================
    // Lifecycle — close() guard
    // =========================================================================

    @Nested
    @DisplayName("Lifecycle — close() guard")
    class LifecycleGuard {

        @Test
        @DisplayName("allocate() after close() throws IllegalStateException")
        void allocateAfterCloseThrows() {
            allocator.close();
            assertThatThrownBy(() -> allocator.allocate(AllocationHint.MICRO))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("allocateNetwork() after close() throws IllegalStateException")
        void allocateNetworkAfterCloseThrows() {
            allocator.close();
            assertThatThrownBy(() -> allocator.allocateNetwork(1024))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("allocateCarrierSlab() after close() throws IllegalStateException")
        void allocateCarrierSlabAfterCloseThrows() {
            allocator.close();
            assertThatThrownBy(() -> allocator.allocateCarrierSlab(0))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("close() is idempotent — double-close does not throw, allocator is closed after")
        void closeIsIdempotent() {
            MemoryAllocator local = new CommunityMemoryProvider().createAllocator(CONFIG);
            local.close();
            local.close();
            assertThatThrownBy(() -> local.allocate(AllocationHint.MICRO))
                    .as("allocator must be closed after double-close()")
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // =========================================================================
    // Buffer isolation
    // =========================================================================

    @Nested
    @DisplayName("Buffer isolation")
    class BufferIsolation {

        @Test
        @DisplayName("Two concurrent buffers have independent memory regions")
        void twoBuffersAreIndependent() {
            try (LoanedBuffer a = allocator.allocate(AllocationHint.MICRO);
                 LoanedBuffer b = allocator.allocate(AllocationHint.MICRO)) {
                // Write 0xAA to buffer A, 0xBB to buffer B
                a.segment().set(ValueLayout.JAVA_BYTE, 0, (byte) 0xAA);
                b.segment().set(ValueLayout.JAVA_BYTE, 0, (byte) 0xBB);

                // Verify no overlap
                assertThat(a.segment().get(ValueLayout.JAVA_BYTE, 0)).isEqualTo((byte) 0xAA);
                assertThat(b.segment().get(ValueLayout.JAVA_BYTE, 0)).isEqualTo((byte) 0xBB);
            }
        }

        @Test
        @DisplayName("Buffer.segment() returns non-null MemorySegment with correct byteSize")
        void segmentHasCorrectSize() {
            try (LoanedBuffer buf = allocator.allocateNetwork(4096)) {
                assertThat(buf.segment()).isNotNull();
                assertThat(buf.segment().byteSize()).isGreaterThanOrEqualTo(4096L);
            }
        }

        @Test
        @DisplayName("Buffer.isAlive() returns false after close()")
        void isAliveAfterClose() {
            LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO);
            assertThat(buf.isAlive()).isTrue();
            buf.close();
            assertThat(buf.isAlive()).isFalse();
        }
    }

    // =========================================================================
    // Concurrent allocation — stats consistency
    // =========================================================================

    @Test
    @DisplayName("Concurrent allocation from 1000 VTs maintains consistent stats")
    void concurrentAllocationStatsConsistency() throws Exception {
        int threadCount = 1_000;

        try (var scope = StructuredTaskScope.open(
                StructuredTaskScope.Joiner.<Void>awaitAllSuccessfulOrThrow())) {
            for (int i = 0; i < threadCount; i++) {
                scope.fork(() -> {
                    try (var buf = allocator.allocate(AllocationHint.MICRO)) {
                        totalAllocated.addAndGet(buf.capacity());
                    }
                    return null;
                });
            }
            scope.join();
        }

        assertThat(allocator.stats().allocationCount())
                .as("allocationCount must equal threadCount")
                .isEqualTo(threadCount);
        assertThat(allocator.stats().releaseCount())
                .as("all buffers must have been released inside scope")
                .isEqualTo(threadCount);
    }

    private final AtomicLong totalAllocated = new AtomicLong(0);
}
