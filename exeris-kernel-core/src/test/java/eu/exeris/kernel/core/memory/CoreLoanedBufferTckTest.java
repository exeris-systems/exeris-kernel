/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.memory;

import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LeakDetectionMode;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;
import eu.exeris.kernel.tck.contract.memory.AbstractLoanedBufferTck;
import org.junit.jupiter.api.DisplayName;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TCK binding: {@link AbstractLoanedBuffer} against the full {@link AbstractLoanedBufferTck}
 * contract suite.
 *
 * <h2>Scope</h2>
 * <p>Verifies that the Core-tier {@link AbstractLoanedBuffer} — the shared base class for
 * all {@code LoanedBuffer} implementations (Community and Enterprise) — correctly
 * implements every clause of the SPI {@link LoanedBuffer}
 * contract as defined by the TCK Inquisition.
 *
 * <h2>Test Allocator</h2>
 * <p>The inline {@code MinimalAllocator} wraps raw {@code Arena.ofShared()} + an
 * anonymous {@link AbstractLoanedBuffer} subclass. It is the thinnest possible driver
 * that exercises the base-class logic without pulling in Community or Enterprise code,
 * thereby keeping this test isolated to {@code exeris-kernel-core}.
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>The inherited TCK class ({@link AbstractLoanedBufferTck}) imports only from
 * {@code exeris-kernel-spi}. This binding class adds Core imports solely to construct
 * the {@link MemoryAllocator} stub — never to influence test assertions.
 *
 * @since 0.5.0
 * @see AbstractLoanedBufferTck
 * @see AbstractLoanedBuffer
 */
@DisplayName("TCK: AbstractLoanedBuffer (Core binding)")
class CoreLoanedBufferTckTest extends AbstractLoanedBufferTck {

    /**
     * Returns the minimal {@link MemoryAllocator} backed by {@link AbstractLoanedBuffer}.
     *
     * <p>Each {@code allocate()} call opens a fresh {@code Arena.ofShared()} and wraps
     * it in an anonymous concrete subclass of {@link AbstractLoanedBuffer}. The arena is
     * closed in {@code onRelease()} — the moment the last retain is released.
     *
     * <p>{@code Arena.ofShared()} is used here instead of {@code Arena.ofConfined()} so
     * that slices created on the hot path and closed from a different Virtual Thread do
     * not trigger {@code WrongThreadException}. In production this responsibility belongs
     * to the allocator driver; here we choose the safer option for a test harness.
     */
    @Override
    protected MemoryAllocator createAllocator() {
        return new MinimalAllocator();
    }

    // =========================================================================
    // Minimal allocator — test-scope only, never referenced by production code
    // =========================================================================

    /**
     * Minimal {@link MemoryAllocator} backed directly by {@link AbstractLoanedBuffer}.
     *
     * <p>Allocates {@code Arena.ofShared()} segments sized by {@link AllocationHint}.
     * Stats reporting is best-effort (tracks live buffer count via volatile counter).
     * {@code performMaintenance()} is a no-op — there is no pool to compact.
     */
    // CHECKSTYLE:OFF — Arena.ofShared() permitted in test-harness allocators
    private static final class MinimalAllocator implements MemoryAllocator {

        private final AtomicLong allocatedBytes = new AtomicLong();

        @Override
        public LoanedBuffer allocate(AllocationHint hint) {
            return allocateRaw(hint.sizeBytes());
        }

        @Override
        public LoanedBuffer allocateNetwork(int bytes) {
            return allocateRaw(bytes);
        }

        @Override
        public LoanedBuffer allocateCarrierSlab(int bytes) {
            return allocateRaw(bytes);
        }

        @Override
        public LoanedBuffer allocateInfrastructure(long bytes) {
            return allocateRaw(bytes);
        }

        @Override
        public MemoryStats stats() {
            long alloc = allocatedBytes.get();
            return new MemoryStats(0L, alloc, 0L, 0, 0, alloc, 0, 0, LeakDetectionMode.DISABLED);
        }

        @Override
        public void close() {
            // no pool to close — each buffer holds its own arena
        }

        /**
         * Opens a fresh {@code Arena.ofShared()} for each allocation.
         * The arena lifecycle is tied to the buffer's reference count and is closed
         * inside {@code onRelease()} — TWR would close it prematurely.
         */
        @SuppressWarnings("resource") // Arena owned by onRelease(), not TWR
        private LoanedBuffer allocateRaw(long bytes) {
            Arena         arena = Arena.ofShared();
            MemorySegment seg   = arena.allocate(bytes);
            allocatedBytes.addAndGet(bytes);

            return new AbstractLoanedBuffer() {
                @Override
                protected MemorySegment backingSegment() {
                    return seg;
                }

                @Override
                protected void onRelease() {
                    allocatedBytes.addAndGet(-bytes);
                    arena.close();
                }

                {
                    setSize(bytes);
                }
            };
        }
    }
    // CHECKSTYLE:ON
}
