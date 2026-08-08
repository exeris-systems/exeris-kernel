/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.memory;

import eu.exeris.kernel.spi.exceptions.memory.MemoryExhaustedException;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.foreign.ValueLayout;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK: Abstract base for {@link MemoryAllocator} contract verification.
 *
 * <h2>TCK Pattern</h2>
 * <p>This class defines the <em>immutable contract</em> that every {@link MemoryAllocator}
 * implementation MUST satisfy. It tests only SPI interfaces — it has zero knowledge of
 * {@code Community}, {@code Enterprise}, or any implementation detail.
 *
 * <h2>How to use</h2>
 * <p>In the {@code community} or {@code enterprise} module, create a single concrete subclass:
 * <pre>{@code
 * class CommunityAllocatorTest extends AbstractMemoryAllocatorTck {
 *     @Override
 *     protected MemoryAllocator createAllocator() {
 *         return new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
 *     }
 * }
 * }</pre>
 *
 * <h2>The Wall</h2>
 * <p>This class imports ONLY from {@code exeris-kernel-spi}. It must never import
 * {@code eu.exeris.kernel.community.*} or {@code eu.exeris.kernel.enterprise.*}.
 *
 * @since 0.5.0
 */
public abstract class AbstractMemoryAllocatorTck {

    private static final int DEFAULT_AVALANCHE_THREADS = 100_000;

    /**
     * Subclass supplies the allocator under test. Called once before each test.
     */
    protected abstract MemoryAllocator createAllocator();

    /**
     * Override to reduce the number of concurrent Virtual Threads in the avalanche test.
     * Enterprise implementations with a fixed slab budget should return a value
     * that fits within their pool capacity (e.g. slab count in TRANSPORT_TCP partition).
     *
     * <p>Default: {@value #DEFAULT_AVALANCHE_THREADS}.
     */
    protected int avalancheThreadCount() {
        return DEFAULT_AVALANCHE_THREADS;
    }

    /**
     * Override to return {@code true} if the allocator has a fixed slab budget
     * (Enterprise tier). When {@code true}, the {@code oomBehaviourIsExhaustion()}
     * test is enabled; when {@code false}, it is skipped.
     *
     * <p>Default: {@code false} (Community: no hard limit).
     */
    protected boolean hasFixedSlabBudget() {
        return false;
    }

    /**
     * Override to return the number of slabs available in the TRANSPORT_TCP partition.
     * Used only when {@link #hasFixedSlabBudget()} returns {@code true}.
     */
    protected int fixedSlabCount() {
        return 0;
    }

    private MemoryAllocator allocator;

    @BeforeEach
    final void setUpAllocator() {
        allocator = createAllocator();
    }

    @AfterEach
    final void tearDownAllocator() {
        allocator.close();
    }

    // =========================================================================
    // L1: Basic Allocation Contract
    // =========================================================================

    @Nested
    @DisplayName("Basic Allocation Contract")
    class BasicAllocationContract {

        @Test
        @DisplayName("allocate(MICRO) returns non-null buffer with capacity >= 512 bytes")
        void allocateMicroReturnsValidBuffer() {
            try (LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO)) {
                assertThat(buf).isNotNull();
                assertThat(buf.capacity()).isGreaterThanOrEqualTo(AllocationHint.MICRO.sizeBytes());
                assertThat(buf.isAlive()).isTrue();
            }
        }

        @Test
        @DisplayName("allocate(SMALL) returns buffer with capacity >= 4096 bytes")
        void allocateSmallReturnsValidBuffer() {
            try (LoanedBuffer buf = allocator.allocate(AllocationHint.SMALL)) {
                assertThat(buf.capacity()).isGreaterThanOrEqualTo(AllocationHint.SMALL.sizeBytes());
            }
        }

        @Test
        @DisplayName("allocate(MEDIUM) returns buffer with capacity >= 16384 bytes")
        void allocateMediumReturnsValidBuffer() {
            try (LoanedBuffer buf = allocator.allocate(AllocationHint.MEDIUM)) {
                assertThat(buf.capacity()).isGreaterThanOrEqualTo(AllocationHint.MEDIUM.sizeBytes());
            }
        }

        @Test
        @DisplayName("Buffer write-read round-trip is correct (no memory aliasing)")
        void writeReadRoundTrip() {
            try (LoanedBuffer buf = allocator.allocate(AllocationHint.SMALL)) {
                buf.segment().set(ValueLayout.JAVA_LONG, 0, 0xCAFEBABEDEADL);
                long read = buf.segment().get(ValueLayout.JAVA_LONG, 0);
                assertThat(read).isEqualTo(0xCAFEBABEDEADL);
            }
        }

        @Test
        @DisplayName("Two concurrent allocations return non-overlapping buffers")
        void concurrentAllocationsAreIsolated() {
            try (LoanedBuffer a = allocator.allocate(AllocationHint.MICRO);
                 LoanedBuffer b = allocator.allocate(AllocationHint.MICRO)) {
                a.segment().set(ValueLayout.JAVA_INT, 0, 0xAAAA);
                b.segment().set(ValueLayout.JAVA_INT, 0, 0xBBBB);

                assertThat(a.segment().get(ValueLayout.JAVA_INT, 0)).isEqualTo(0xAAAA);
                assertThat(b.segment().get(ValueLayout.JAVA_INT, 0)).isEqualTo(0xBBBB);
            }
        }

        @Test
        @DisplayName("Buffer is closed after try-with-resources exits")
        void bufferIsClosedAfterTwr() {
            LoanedBuffer buf;
            try (LoanedBuffer tmp = allocator.allocate(AllocationHint.MICRO)) {
                buf = tmp;
                assertThat(buf.isAlive()).isTrue();
            }
            assertThat(buf.isAlive()).isFalse();
        }
    }

    // =========================================================================
    // L2: Stats Contract
    // =========================================================================

    @Nested
    @DisplayName("MemoryStats Contract")
    class MemoryStatsContract {

        @Test
        @DisplayName("allocationCount increments on each allocate()")
        void allocationCountIncrements() {
            long before = allocator.stats().allocationCount();
            try (var _ = allocator.allocate(AllocationHint.MICRO)) {
                assertThat(allocator.stats().allocationCount()).isEqualTo(before + 1);
            }
        }

        @Test
        @DisplayName("releaseCount equals allocationCount after all buffers are closed")
        void releaseCountEqualsAllocationCountAfterClose() {
            int ops = 10;
            for (int i = 0; i < ops; i++) {
                allocator.allocate(AllocationHint.MICRO).close();
            }
            MemoryStats stats = allocator.stats();
            assertThat(stats.releaseCount())
                    .as("releaseCount must match allocationCount — no leaks")
                    .isEqualTo(stats.allocationCount());
        }

        @Test
        @DisplayName("allocatedBytes is 0 after all buffers are released")
        void allocatedBytesIsZeroAfterRelease() {
            allocator.allocate(AllocationHint.MICRO).close();
            assertThat(allocator.stats().allocatedBytes()).isZero();
        }
    }

    // =========================================================================
    // L3: Virtual Thread Avalanche
    // =========================================================================

    @Nested
    @DisplayName("Virtual Thread Avalanche")
    class VirtualThreadAvalanche {

        @Test
        @DisplayName("100 000 Virtual Threads: allocate → write → verify → release — zero leaks")
        @Timeout(60)
        void avalanche() throws InterruptedException, java.util.concurrent.ExecutionException {
            AtomicLong errors = new AtomicLong(0);
            int threads = avalancheThreadCount();

            // JDK 28: StructuredTaskScope gained a third type parameter — the exception join()
            // throws — so the explicit spelling carries ExecutionException, which is what
            // awaitAllSuccessfulOrThrow() now reports a failed subtask as.
            try (StructuredTaskScope<Void, Void, java.util.concurrent.ExecutionException> scope =
                    StructuredTaskScope.open(
                            StructuredTaskScope.Joiner.awaitAllSuccessfulOrThrow())) {

                for (int i = 0; i < threads; i++) {
                    final int threadId = i;
                    scope.fork(() -> {
                        try (LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO)) {
                            buf.segment().set(ValueLayout.JAVA_INT, 0, threadId);
                            int read = buf.segment().get(ValueLayout.JAVA_INT, 0);
                            if (read != threadId) {
                                errors.incrementAndGet();
                            }
                        }
                        return null;
                    });
                }

                scope.join();
            }

            assertThat(errors.get())
                    .as("Data corruption detected: %d buffers returned wrong value", errors.get())
                    .isZero();

            MemoryStats stats = allocator.stats();
            assertThat(stats.allocationCount()).isEqualTo(threads);
            assertThat(stats.releaseCount()).isEqualTo(threads);
            assertThat(stats.allocatedBytes()).isZero();
        }

        @Test
        @DisplayName("Mixed hint sizes: MICRO, SMALL, MEDIUM — no cross-thread corruption")
        @Timeout(60)
        void mixedHints() throws InterruptedException, java.util.concurrent.ExecutionException {
            AllocationHint[] hints = {AllocationHint.MICRO, AllocationHint.SMALL, AllocationHint.MEDIUM};

            int total = avalancheThreadCount();
            AtomicLong errors = new AtomicLong(0);

            // JDK 28: StructuredTaskScope gained a third type parameter — the exception join()
            // throws — so the explicit spelling carries ExecutionException, which is what
            // awaitAllSuccessfulOrThrow() now reports a failed subtask as.
            try (StructuredTaskScope<Void, Void, java.util.concurrent.ExecutionException> scope =
                    StructuredTaskScope.open(
                            StructuredTaskScope.Joiner.awaitAllSuccessfulOrThrow())) {

                for (int i = 0; i < total; i++) {
                    AllocationHint hint = hints[i % hints.length];
                    final long sentinel = i;
                    scope.fork(() -> {
                        try (LoanedBuffer buf = allocator.allocate(hint)) {
                            buf.segment().set(ValueLayout.JAVA_LONG, 0, sentinel);
                            long read = buf.segment().get(ValueLayout.JAVA_LONG, 0);
                            if (read != sentinel) {
                                errors.incrementAndGet();
                            }
                        }
                        return null;
                    });
                }

                scope.join();
            }

            assertThat(errors.get()).isZero();
        }
    }

    // =========================================================================
    // L3: Out-of-Memory Behaviour (tier-specific)
    // =========================================================================

    @Nested
    @DisplayName("OOM Behaviour")
    class OomBehaviour {

        @Test
        @DisplayName("Enterprise: exhausting fixed slab budget throws MemoryExhaustedException")
        void oomBehaviourIsExhaustion() {
            if (!hasFixedSlabBudget()) {
                return;
            }

            int slabs = fixedSlabCount();
            LoanedBuffer[] held = new LoanedBuffer[slabs];
            try {
                for (int i = 0; i < slabs; i++) {
                    held[i] = allocator.allocate(AllocationHint.MICRO);
                }
                org.assertj.core.api.Assertions.assertThatThrownBy(
                                () -> allocator.allocate(AllocationHint.MICRO))
                        .isInstanceOf(MemoryExhaustedException.class);
            } finally {
                for (LoanedBuffer b : held) {
                    if (b != null) {
                        b.close();
                    }
                }
            }
        }
    }
}
