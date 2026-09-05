/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.memory;

import eu.exeris.kernel.spi.exceptions.memory.MemoryExhaustedException;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK: Abstract base for {@link MemoryStats} pressure-signal contract verification.
 *
 * <h2>Motivation</h2>
 * <p>The Core subsystem ({@code WatermarkManager}, {@code ResourceArbiter}) makes
 * load-shedding decisions based exclusively on the values exposed by
 * {@link MemoryAllocator#stats()}. If an allocator implementation reports incorrect
 * utilization data, the entire load-shedding safety system is blind.
 *
 * <p>This TCK enforces that the pressure signal exposed via {@link MemoryStats} is:
 * <ol>
 *   <li><b>Monotonically growing</b> as buffers are allocated and held.</li>
 *   <li><b>Monotonically shrinking</b> as buffers are closed.</li>
 *   <li><b>Exactly zero</b> after all buffers are released.</li>
 *   <li><b>Bounded by {@code totalBytes}</b> — utilization never exceeds 1.0.</li>
 *   <li><b>Consistent</b> — {@code allocatedBytes + freeBytes == totalBytes}
 *       when the allocator has a fixed budget ({@code totalBytes > 0}).</li>
 * </ol>
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>This class imports ONLY from {@code exeris-kernel-spi}. It has zero knowledge of
 * {@code WatermarkManager}, {@code ResourceArbiter}, or any Core class. It tests the
 * <em>signal</em> that those classes consume, not the consumers themselves.
 *
 * <h2>How to use</h2>
 * <pre>{@code
 * class CommunityPressureSignalTest extends AbstractMemoryPressureSignalTck {
 *     \@Override
 *     protected MemoryProvider createProvider() {
 *         return new CommunityMemoryProvider();
 *     }
 * }
 * }</pre>
 *
 * @see MemoryStats
 * @see MemoryAllocator#stats()
 * @since 0.5
 */
public abstract class AbstractMemoryPressureSignalTck {

    // =========================================================================
    // Template methods
    // =========================================================================

    /**
     * Creates the {@link MemoryProvider} under test.
     *
     * @return provider instance; must not be {@code null}
     */
    protected abstract MemoryProvider createProvider();

    /**
     * Number of buffers to allocate in the monotonic growth test.
     * Enterprise with a fixed slab budget should override to a value within
     * their TRANSPORT_TCP partition capacity.
     *
     * <p>Default: {@code 64}.
     */
    protected int monotonicGrowthBufferCount() {
        return 64;
    }

    /**
     * Returns {@code true} if the allocator exposes a fixed off-heap budget
     * (i.e. {@link MemoryStats#totalBytes()} {@code > 0}).
     *
     * <p>When {@code true}, the consistency invariant
     * {@code allocatedBytes + freeBytes == totalBytes} is verified.
     * Default: {@code false} (Community heap allocator has no fixed budget).
     */
    protected boolean hasFixedBudget() {
        return false;
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    private MemoryAllocator allocator;

    @BeforeEach
    final void setUpAllocator() {
        allocator = createProvider().createAllocator(
                MemoryProviderConfig.defaults().withLeakDetection(LeakDetectionMode.DISABLED));
    }

    @AfterEach
    final void tearDownAllocator() {
        if (allocator != null) {
            allocator.close();
        }
    }

    // =========================================================================
    // L1: Baseline stats contract
    // =========================================================================

    @Nested
    @DisplayName("L1: Baseline stats contract")
    class BaselineStats {

        @Test
        @DisplayName("stats() returns non-null snapshot")
        void statsIsNonNull() {
            assertThat(allocator.stats()).isNotNull();
        }

        @Test
        @DisplayName("allocationCount() starts at 0 before any allocation")
        void allocationCountStartsAtZero() {
            assertThat(allocator.stats().allocationCount()).isZero();
        }

        @Test
        @DisplayName("allocatedBytes() is 0 before any allocation")
        void allocatedBytesStartsAtZero() {
            assertThat(allocator.stats().allocatedBytes()).isZero();
        }

        @Test
        @DisplayName("utilization() in [0.0, 1.0] at all times")
        void utilizationIsInRange() {
            double u = allocator.stats().utilization();
            assertThat(u)
                    .as("utilization() must be in [0.0, 1.0]")
                    .isBetween(0.0, 1.0);
        }

        @Test
        @DisplayName("leakDetectionMode() matches the config supplied at creation")
        void leakDetectionModeMatchesConfig() {
            assertThat(allocator.stats().leakDetectionMode())
                    .isEqualTo(LeakDetectionMode.DISABLED);
        }
    }

    // =========================================================================
    // L2: Pressure signal monotonicity
    // =========================================================================

    @Nested
    @DisplayName("L2: Pressure signal monotonicity")
    class PressureSignalMonotonicity {

        @Test
        @DisplayName("allocatedBytes() grows as buffers are held open")
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        void allocatedBytesGrowsWithHeldBuffers() {
            int count = monotonicGrowthBufferCount();
            List<LoanedBuffer> held = new ArrayList<>(count);
            try {
                long prevAllocated = allocator.stats().allocatedBytes();
                for (int i = 0; i < count; i++) {
                    held.add(allocator.allocate(AllocationHint.MICRO));
                    long currentAllocated = allocator.stats().allocatedBytes();
                    assertThat(currentAllocated)
                            .as("allocatedBytes() MUST be >= previous value after allocation %d", i)
                            .isGreaterThanOrEqualTo(prevAllocated);
                    prevAllocated = currentAllocated;
                }
                assertThat(allocator.stats().allocatedBytes())
                        .as("allocatedBytes() MUST be > 0 while buffers are held")
                        .isGreaterThan(0);
            } finally {
                held.forEach(LoanedBuffer::close);
            }
        }

        @Test
        @DisplayName("allocatedBytes() returns to 0 after all buffers are closed")
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        void allocatedBytesReturnsToZeroAfterRelease() {
            int count = monotonicGrowthBufferCount();
            for (int i = 0; i < count; i++) {
                allocator.allocate(AllocationHint.MICRO).close();
            }
            assertThat(allocator.stats().allocatedBytes())
                    .as("allocatedBytes() MUST be 0 after all buffers are closed — slab leak detected")
                    .isZero();
        }

        @Test
        @DisplayName("allocationCount() increments exactly once per allocate() call")
        void allocationCountIncrementsExactly() {
            int ops = 10;
            long before = allocator.stats().allocationCount();
            for (int i = 0; i < ops; i++) {
                allocator.allocate(AllocationHint.MICRO).close();
            }
            assertThat(allocator.stats().allocationCount())
                    .as("allocationCount() MUST increment by exactly %d", ops)
                    .isEqualTo(before + ops);
        }

        @Test
        @DisplayName("releaseCount() equals allocationCount() after all buffers closed")
        void releaseCountEqualsAllocationCount() {
            int ops = 10;
            for (int i = 0; i < ops; i++) {
                allocator.allocate(AllocationHint.MICRO).close();
            }
            MemoryStats stats = allocator.stats();
            assertThat(stats.releaseCount())
                    .as("releaseCount MUST equal allocationCount — no buffer held hostage")
                    .isEqualTo(stats.allocationCount());
        }
    }

    // =========================================================================
    // L3: Budget consistency (fixed-budget allocators only)
    // =========================================================================

    @Nested
    @DisplayName("L3: Budget consistency (fixed-budget tier)")
    class BudgetConsistency {

        @Test
        @DisplayName("allocatedBytes + freeBytes == totalBytes when budget is fixed")
        void budgetConsistencyInvariant() {
            if (!hasFixedBudget()) {
                return;
            }
            MemoryStats stats = allocator.stats();
            assertThat(stats.allocatedBytes() + stats.freeBytes())
                    .as("allocatedBytes(%d) + freeBytes(%d) MUST equal totalBytes(%d)",
                            stats.allocatedBytes(), stats.freeBytes(), stats.totalBytes())
                    .isEqualTo(stats.totalBytes());
        }

        @Test
        @DisplayName("utilization() == 0.0 when no buffers are held (idle state)")
        void utilizationIsZeroWhenIdle() {
            if (!hasFixedBudget()) {
                return;
            }
            assertThat(allocator.stats().utilization())
                    .as("utilization() MUST be 0.0 when no buffers are held")
                    .isZero();
        }

        @Test
        @DisplayName("utilization() is in (0.0, 1.0] while a buffer is held")
        void utilizationIsNonZeroWhileBufferHeld() {
            if (!hasFixedBudget()) {
                return;
            }
            try (var _ = allocator.allocate(AllocationHint.MICRO)) {
                double u = allocator.stats().utilization();
                assertThat(u)
                        .as("utilization() MUST be > 0.0 while a buffer is held. Got: %f", u)
                        .isGreaterThan(0.0)
                        .isLessThanOrEqualTo(1.0);
            }
        }

        @Test
        @DisplayName("utilization() never exceeds 1.0 under any load")
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        void utilizationNeverExceedsOne() {
            int count = monotonicGrowthBufferCount();
            List<LoanedBuffer> held = new ArrayList<>(count);
            try {
                for (int i = 0; i < count; i++) {
                    try {
                        held.add(allocator.allocate(AllocationHint.MICRO));
                    } catch (MemoryExhaustedException _) {
                        break;
                    }
                    double u = allocator.stats().utilization();
                    assertThat(u)
                            .as("utilization() MUST never exceed 1.0. Got: %f after %d allocations", u, i + 1)
                            .isLessThanOrEqualTo(1.0);
                }
            } finally {
                held.forEach(LoanedBuffer::close);
            }
        }
    }

    // =========================================================================
    // L4: Stats snapshot immutability
    // =========================================================================

    @Nested
    @DisplayName("L4: Stats snapshot immutability")
    class StatsSnapshotImmutability {

        @Test
        @DisplayName("Two calls to stats() return independent snapshots")
        void twoStatsCallsReturnIndependentSnapshots() {
            MemoryStats snap1 = allocator.stats();
            allocator.allocate(AllocationHint.MICRO).close();
            MemoryStats snap2 = allocator.stats();

            assertThat(snap1).isNotSameAs(snap2);
            assertThat(snap2.allocationCount())
                    .as("Second snapshot must reflect the allocation that occurred")
                    .isGreaterThan(snap1.allocationCount());
        }
    }
}
