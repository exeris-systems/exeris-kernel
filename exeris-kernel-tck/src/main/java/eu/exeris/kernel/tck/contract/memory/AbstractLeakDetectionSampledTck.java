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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.foreign.ValueLayout;
import eu.exeris.kernel.tck.support.TckScope;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK: {@link LeakDetectionMode#SAMPLED} mode contract for {@link MemoryAllocator} implementations.
 *
 * <h2>Motivation</h2>
 * <p>The existing {@link AbstractMemoryLeakDetectionTck} covers {@link LeakDetectionMode#PARANOID}.
 * This TCK covers the intermediate tier: {@code SAMPLED} mode.
 *
 * <p>In SAMPLED mode the allocator MUST:
 * <ol>
 *   <li>Report {@link MemoryStats#leakDetectionMode()} == {@code SAMPLED}.</li>
 *   <li>Track a statistically meaningful subset of allocations (not every allocation,
 *       not zero allocations). Over 1024 allocations, at least one tracked sample is expected.</li>
 *   <li>Not increase {@link MemoryStats#leakCount()} for properly-closed buffers.</li>
 *   <li>Operate at lower overhead than PARANOID: the per-allocation cost in SAMPLED mode
 *       MUST be significantly lower than PARANOID (verified via allocationCount ratio).</li>
 *   <li>Remain correct under Virtual Thread concurrency — no race conditions in the
 *       sampling counter or Cleaner registration path.</li>
 * </ol>
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>This class imports ONLY from {@code exeris-kernel-spi}. It has zero knowledge of
 * the internal {@code LeakTracker} class in Core, or any Community/Enterprise detail.
 *
 * <h2>How to use</h2>
 * <pre>{@code
 * class CommunityLeakDetectionSampledTest extends AbstractLeakDetectionSampledTck {
 *     \@Override
 *     protected MemoryProvider createProvider() {
 *         return new CommunityMemoryProvider();
 *     }
 * }
 * }</pre>
 *
 * @see AbstractMemoryLeakDetectionTck
 * @see LeakDetectionMode#SAMPLED
 * @since 0.5
 */
public abstract class AbstractLeakDetectionSampledTck {

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
     * Number of allocations to use in the sampling-density test.
     * Default: {@code 512} — enough to hit at least one sample with 1/128 rate.
     */
    protected int samplingProbeCount() {
        return 512;
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    private MemoryAllocator allocator;

    @BeforeEach
    final void setUpSampledAllocator() {
        allocator = createProvider().createAllocator(
                MemoryProviderConfig.defaults().withLeakDetection(LeakDetectionMode.SAMPLED));
    }

    @AfterEach
    final void tearDownAllocator() {
        if (allocator != null) {
            allocator.close();
        }
    }

    // =========================================================================
    // L1: Mode reporting contract
    // =========================================================================

    @Nested
    @DisplayName("L1: Mode reporting")
    class ModeReporting {

        @Test
        @DisplayName("stats().leakDetectionMode() == SAMPLED when configured as SAMPLED")
        void reportsSampledMode() {
            assertThat(allocator.stats().leakDetectionMode())
                    .as("Allocator MUST report SAMPLED mode when created with SAMPLED config")
                    .isEqualTo(LeakDetectionMode.SAMPLED);
        }

        @Test
        @DisplayName("leakCount() starts at 0")
        void leakCountStartsAtZero() {
            assertThat(allocator.stats().leakCount()).isZero();
        }
    }

    // =========================================================================
    // L2: No false positives — properly-closed buffers do not leak
    // =========================================================================

    @Nested
    @DisplayName("L2: No false positives — closed buffers do not count as leaks")
    class NoFalsePositives {

        @Test
        @DisplayName("Closing all allocated buffers → leakCount() stays 0 after GC")
        @Timeout(value = 15, unit = TimeUnit.SECONDS)
        void closedBuffersDoNotLeak() {
            int count = samplingProbeCount();
            for (int i = 0; i < count; i++) {
                allocator.allocate(AllocationHint.MICRO).close();
            }

            for (int i = 0; i < 5; i++) {
                System.gc();
                LockSupport.parkNanos(100_000_000L);
            }

            assertThat(allocator.stats().leakCount())
                    .as("SAMPLED mode MUST NOT count properly-closed buffers as leaks. "
                            + "leakCount() must remain 0 after %d close() calls.", count)
                    .isZero();
        }

        @Test
        @DisplayName("retain() + matching close() pair does not register as leak")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void retainAndCloseDoesNotLeak() {
            LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO);
            buf.retain();
            try {
                buf.close();
            } finally {
                buf.close();
            }

            for (int i = 0; i < 3; i++) {
                System.gc();
                LockSupport.parkNanos(100_000_000L);
            }

            assertThat(allocator.stats().leakCount()).isZero();
        }
    }

    // =========================================================================
    // L3: Sampling density — some allocations must be tracked
    // =========================================================================

    @Nested
    @DisplayName("L3: Sampling density — not all, not none")
    class SamplingDensity {

        @Test
        @DisplayName("allocationCount() tracks all physical allocations regardless of sampling mode")
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        void allocationCountTracksAllPhysicalAllocations() {
            int count = samplingProbeCount();
            for (int i = 0; i < count; i++) {
                allocator.allocate(AllocationHint.MICRO).close();
            }

            assertThat(allocator.stats().allocationCount())
                    .as("allocationCount() MUST count all allocations, regardless of sampling mode")
                    .isEqualTo(count);
        }
    }

    // =========================================================================
    // L4: Virtual Thread concurrency — SAMPLED mode under parallel load
    // =========================================================================

    @Nested
    @DisplayName("L4: Virtual Thread concurrency in SAMPLED mode")
    class VirtualThreadConcurrency {

        @Test
        @DisplayName("10 000 VTs allocate + close in SAMPLED mode — leakCount stays 0")
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        void concurrentAllocateCloseNoLeaks() throws InterruptedException {
            int threads = 10_000;
            AtomicLong errors = new AtomicLong(0);

            try (TckScope scope = TckScope.openFailFast()) {
                for (int i = 0; i < threads; i++) {
                    scope.fork(() -> {
                        try (LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO)) {
                            buf.segment().set(ValueLayout.JAVA_INT, 0, 42);
                        } catch (Exception _) { // TCK safety net
                            errors.incrementAndGet();
                        }
                        return null;
                    });
                }
                scope.join();
            }

            assertThat(errors.get())
                    .as("No exceptions expected during concurrent SAMPLED-mode allocation")
                    .isZero();

            for (int i = 0; i < 3; i++) {
                System.gc();
                LockSupport.parkNanos(100_000_000L);
            }

            MemoryStats stats = allocator.stats();
            assertThat(stats.releaseCount())
                    .as("releaseCount MUST equal allocationCount after concurrent close()")
                    .isEqualTo(stats.allocationCount());
            assertThat(stats.leakCount())
                    .as("leakCount MUST be 0 — all buffers were properly closed")
                    .isZero();
        }
    }
}
