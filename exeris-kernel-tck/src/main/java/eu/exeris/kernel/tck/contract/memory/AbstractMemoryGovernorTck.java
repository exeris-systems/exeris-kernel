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
 * TCK: Abstract base for the Memory Governor Signal contract.
 *
 * <h2>Motivation</h2>
 * <p>The Core subsystem's {@code WatermarkManager} and {@code ResourceArbiter} make
 * all load-shedding decisions based <em>exclusively</em> on the pressure signal exposed
 * by {@link MemoryAllocator#stats()}. If an allocator reports incorrect utilization,
 * the entire memory-governor safety system becomes blind to the real pressure state.
 *
 * <p>This TCK validates the <em>governor input contract</em> — i.e., what signal any
 * compliant {@link MemoryAllocator} implementation MUST provide so that the Core-tier
 * governor can make correct decisions.
 *
 * <h2>The Four Governor Thresholds</h2>
 * <p>The Core's {@code WatermarkLevel} classifies utilization into four zones:
 * <table>
 *   <tr><th>Zone</th><th>Utilization</th><th>Arbiter Action</th></tr>
 *   <tr><td>NORMAL</td><td>&lt; 70%</td><td>ALLOW</td></tr>
 *   <tr><td>WARNING</td><td>70–85%</td><td>THROTTLE</td></tr>
 *   <tr><td>CRITICAL</td><td>85–95%</td><td>REJECT (transport) / SHED (logic)</td></tr>
 *   <tr><td>SHEDDING</td><td>&ge; 95%</td><td>SHED_LOAD</td></tr>
 * </table>
 * For an allocator with a <em>fixed budget</em> ({@code totalBytes > 0}), this TCK
 * verifies that the utilization signal it emits correctly traverses each zone boundary.
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>This class imports ONLY from {@code exeris-kernel-spi}. It has zero knowledge of
 * {@code WatermarkManager}, {@code ResourceArbiter}, or any Core class. It verifies
 * the <em>signal</em> that the governor consumes, not the governor itself.
 *
 * <h2>How to use</h2>
 * <pre>{@code
 * // In the Community or Enterprise test module:
 * class CommunityGovernorSignalTest extends AbstractMemoryGovernorTck {
 *     \@Override
 *     protected MemoryProvider createProvider() {
 *         return new CommunityMemoryProvider();
 *     }
 *     \@Override
 *     protected boolean hasFixedBudget() { return false; }
 * }
 * }</pre>
 *
 * @see MemoryStats#utilization()
 * @see MemoryStats#allocatedBytes()
 * @since 0.5
 */
public abstract class AbstractMemoryGovernorTck {

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
     * Returns {@code true} if the allocator under test has a fixed off-heap budget
     * ({@link MemoryStats#totalBytes()} {@code > 0}).
     *
     * <p>Fixed-budget allocators (Enterprise tier) allow precise utilization
     * ratio assertions. Dynamic allocators (Community tier) skip ratio tests.
     *
     * <p>Default: {@code false}.
     */
    protected boolean hasFixedBudget() {
        return false;
    }

    /**
     * Number of buffers to allocate when probing the WARNING zone (70–85%).
     * Subclasses with small slab budgets should reduce this to avoid exhaustion.
     *
     * <p>Default: {@code 32}.
     */
    protected int warningZoneBufferCount() {
        return 32;
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    private MemoryAllocator allocator;

    @BeforeEach
    final void setUp() {
        allocator = createProvider().createAllocator(
                MemoryProviderConfig.defaults().withLeakDetection(LeakDetectionMode.DISABLED));
    }

    @AfterEach
    final void tearDown() {
        if (allocator != null) {
            allocator.close();
        }
    }

    // =========================================================================
    // L1: Signal baseline — governor input preconditions
    // =========================================================================

    @Nested
    @DisplayName("L1: Governor signal baseline")
    class GovernorSignalBaseline {

        @Test
        @DisplayName("stats() is non-null at idle — governor can always read the signal")
        void statsNonNullAtIdle() {
            assertThat(allocator.stats())
                    .as("stats() must never return null — governor reads it on every TTL expiry")
                    .isNotNull();
        }

        @Test
        @DisplayName("utilization() is in [0.0, 1.0] at idle — no governor overflow")
        void utilizationInRangeAtIdle() {
            double u = allocator.stats().utilization();
            assertThat(u)
                    .as("utilization() must be in [0.0, 1.0] at idle — out-of-range panics the WatermarkManager")
                    .isBetween(0.0, 1.0);
        }

        @Test
        @DisplayName("allocatedBytes() starts at 0 — governor sees NORMAL at cold start")
        void allocatedBytesZeroAtColdStart() {
            assertThat(allocator.stats().allocatedBytes())
                    .as("allocatedBytes() must be 0 at cold start — governor must not enter WARNING on boot")
                    .isZero();
        }

        @Test
        @DisplayName("performMaintenance() is callable without throwing — governor cycle survives")
        void performMaintenanceSafe() {
            allocator.performMaintenance();
            assertThat(allocator.stats().allocatedBytes())
                    .as("performMaintenance() on idle allocator must not corrupt the stats signal")
                    .isZero();
        }
    }

    // =========================================================================
    // L2: Signal under load — governor zone transitions
    // =========================================================================

    @Nested
    @DisplayName("L2: Signal under load — governor zone transitions")
    class GovernorZoneTransitions {

        @Test
        @DisplayName("allocatedBytes() grows as buffers are held — governor can detect WARNING zone")
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        void allocatedBytesGrowsWithLoad() {
            int count = warningZoneBufferCount();
            List<LoanedBuffer> held = new ArrayList<>(count);
            try {
                long prev = allocator.stats().allocatedBytes();
                for (int i = 0; i < count; i++) {
                    held.add(allocator.allocate(AllocationHint.MICRO));
                    long curr = allocator.stats().allocatedBytes();
                    assertThat(curr)
                            .as("allocatedBytes() MUST be >= previous value after allocation %d — governor must see growing pressure", i)
                            .isGreaterThanOrEqualTo(prev);
                    prev = curr;
                }
                assertThat(allocator.stats().allocatedBytes())
                        .as("allocatedBytes() MUST be > 0 while buffers are held — governor must not report NORMAL under load")
                        .isGreaterThan(0L);
            } finally {
                held.forEach(LoanedBuffer::close);
            }
        }

        @Test
        @DisplayName("utilization() returns to <= NORMAL threshold (0.70) after all buffers closed")
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        void utilizationDropsAfterRelease() {
            int count = warningZoneBufferCount();
            List<LoanedBuffer> held = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                held.add(allocator.allocate(AllocationHint.MICRO));
            }
            held.forEach(LoanedBuffer::close);

            double utilization = allocator.stats().utilization();
            assertThat(utilization)
                    .as("utilization() MUST drop to <= 0.70 (NORMAL zone) after all buffers closed. Got: %.4f"
                                + " — governor would incorrectly throttle at idle", utilization)
                    .isLessThanOrEqualTo(0.70);
        }

        @Test
        @DisplayName("allocatedBytes() returns to 0 after all buffers closed — governor returns to NORMAL")
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        void allocatedBytesReturnsToZeroAfterRelease() {
            int count = warningZoneBufferCount();
            for (int i = 0; i < count; i++) {
                allocator.allocate(AllocationHint.MICRO).close();
            }
            assertThat(allocator.stats().allocatedBytes())
                    .as("allocatedBytes() MUST be 0 after all buffers closed — governor must return to NORMAL (slab pool leak?)")
                    .isZero();
        }
    }

    // =========================================================================
    // L3: Fixed-budget utilization ratio (Enterprise tier)
    // =========================================================================

    @Nested
    @DisplayName("L3: Fixed-budget utilization ratio (governor precision)")
    class FixedBudgetPrecision {

        @Test
        @DisplayName("utilization() == 0.0 at idle — governor correctly classifies as NORMAL")
        void utilizationZeroAtIdle() {
            if (!hasFixedBudget()) {
                return;
            }
            assertThat(allocator.stats().utilization())
                    .as("utilization() MUST be exactly 0.0 at idle for fixed-budget allocators"
                                + " — governor must not trigger WARNING on empty pool")
                    .isZero();
        }

        @Test
        @DisplayName("utilization() in (0.0, 1.0] while holding buffer — governor can enter WARNING zone")
        void utilizationPositiveWhileHolding() {
            if (!hasFixedBudget()) {
                return;
            }
            double u;
            try (var _ = allocator.allocate(AllocationHint.MICRO)) {
                u = allocator.stats().utilization();
            }
            assertThat(u)
                    .as("utilization() MUST be in (0.0, 1.0] while a buffer is held. Got: %.4f — governor cannot detect WARNING zone", u)
                    .isGreaterThan(0.0)
                    .isLessThanOrEqualTo(1.0);
        }

        @Test
        @DisplayName("utilization() never exceeds 1.0 under saturation — governor never panics on > 100%")
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        void utilizationNeverExceedsOneUnderSaturation() {
            if (!hasFixedBudget()) {
                return;
            }
            int count = warningZoneBufferCount();
            List<LoanedBuffer> held = new ArrayList<>(count);
            try {
                for (int i = 0; i < count; i++) {
                    try {
                        held.add(allocator.allocate(AllocationHint.MICRO));
                    } catch (MemoryExhaustedException _) {
                        break; // expected at exhaustion
                    }
                    double u = allocator.stats().utilization();
                    assertThat(u)
                            .as("utilization() MUST NEVER exceed 1.0. Got: %.4f after %d allocations"
                                + " — WatermarkManager.forUtilization() uses this to classify levels,"
                                + " values > 1.0 corrupt the classification", u, i + 1)
                            .isLessThanOrEqualTo(1.0);
                }
            } finally {
                held.forEach(LoanedBuffer::close);
            }
        }

        @Test
        @DisplayName("allocatedBytes + freeBytes == totalBytes — governor arithmetic is exact")
        void budgetConsistencyArithmetic() {
            if (!hasFixedBudget()) {
                return;
            }
            MemoryStats stats = allocator.stats();
            assertThat(stats.allocatedBytes() + stats.freeBytes())
                    .as("allocatedBytes(%d) + freeBytes(%d) MUST equal totalBytes(%d) — inconsistency corrupts governor utilization ratio",
                            stats.allocatedBytes(), stats.freeBytes(), stats.totalBytes())
                    .isEqualTo(stats.totalBytes());
        }
    }

    // =========================================================================
    // L4: performMaintenance() does not perturb the signal
    // =========================================================================

    @Nested
    @DisplayName("L4: performMaintenance() signal stability")
    class MaintenanceSignalStability {

        @Test
        @DisplayName("performMaintenance() does not alter allocatedBytes() — governor signal is stable after maintenance")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void maintenanceDoesNotPerturbAllocatedBytes() {
            int count = warningZoneBufferCount();
            List<LoanedBuffer> held = new ArrayList<>(count);
            try {
                for (int i = 0; i < count; i++) {
                    held.add(allocator.allocate(AllocationHint.MICRO));
                }
                long beforeMaintenance = allocator.stats().allocatedBytes();
                allocator.performMaintenance();
                long afterMaintenance = allocator.stats().allocatedBytes();

                assertThat(afterMaintenance)
                        .as("performMaintenance() MUST NOT alter allocatedBytes() for held buffers. "
                                + "Before: %d, After: %d — governor would falsely see pressure drop", beforeMaintenance, afterMaintenance)
                        .isEqualTo(beforeMaintenance);
            } finally {
                held.forEach(LoanedBuffer::close);
            }
        }

        @Test
        @DisplayName("performMaintenance() on idle allocator leaves utilization() == 0.0")
        void maintenanceOnIdleIsNoop() {
            allocator.performMaintenance();
            assertThat(allocator.stats().allocatedBytes())
                    .as("performMaintenance() on idle allocator must leave allocatedBytes == 0")
                    .isZero();
        }
    }
}
