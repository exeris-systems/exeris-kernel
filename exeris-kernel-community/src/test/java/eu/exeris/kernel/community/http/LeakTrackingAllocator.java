/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Test-only {@link MemoryAllocator} decorator that counts outstanding {@link LoanedBuffer}s allocated
 * through it for ONE owner — one stream (ADR-043 TCK: {@code outstandingLoans()} must be stream-scoped,
 * not a global snapshot), or one client. Every buffer it hands out increments the counter; the counter
 * decrements when that buffer's reference count reaches zero (via {@link LoanedBuffer#addCloseAction}).
 * {@link #allocated()} counts every hand-out and never decrements, so a test can show the path under
 * test allocated at all before it reads a zero from {@link #outstanding()}.
 *
 * <p>The wrapped allocator is the real Community allocator, so the underlying zero-copy / ref-count
 * semantics are exercised faithfully — this decorator only observes the open/close edges.
 */
public final class LeakTrackingAllocator implements MemoryAllocator {

    private final MemoryAllocator delegate;
    private final AtomicLong outstanding = new AtomicLong(0L);
    private final AtomicLong allocated = new AtomicLong(0L);

    /**
     * Wraps {@code delegate}, which does the real allocation.
     *
     * @param delegate the allocator every call is forwarded to
     */
    public LeakTrackingAllocator(MemoryAllocator delegate) {
        this.delegate = delegate;
    }

    /**
     * Returns how many buffers handed out by this allocator have not yet been released.
     *
     * @return outstanding buffer count
     */
    public long outstanding() {
        return outstanding.get();
    }

    /**
     * Returns how many buffers this allocator has handed out in total.
     *
     * @return cumulative allocation count
     */
    public long allocated() {
        return allocated.get();
    }

    private LoanedBuffer track(LoanedBuffer buffer) {
        allocated.incrementAndGet();
        outstanding.incrementAndGet();
        buffer.addCloseAction(outstanding::decrementAndGet);
        return buffer;
    }

    @Override
    public LoanedBuffer allocate(AllocationHint hint) {
        return track(delegate.allocate(hint));
    }

    @Override
    public LoanedBuffer allocateNetwork(int estimatedBytes) {
        return track(delegate.allocateNetwork(estimatedBytes));
    }

    @Override
    public LoanedBuffer allocateCarrierSlab(int carrierIndex) {
        return track(delegate.allocateCarrierSlab(carrierIndex));
    }

    @Override
    public LoanedBuffer allocateInfrastructure(long sizeBytes) {
        return track(delegate.allocateInfrastructure(sizeBytes));
    }

    @Override
    public MemoryStats stats() {
        return delegate.stats();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
