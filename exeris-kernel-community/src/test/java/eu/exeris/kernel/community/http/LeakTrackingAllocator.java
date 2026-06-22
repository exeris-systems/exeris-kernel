/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Test-only {@link MemoryAllocator} decorator that counts outstanding {@link LoanedBuffer}s allocated
 * through it for ONE stream (ADR-043 TCK: {@code outstandingLoans()} must be stream-scoped, not a
 * global snapshot). Every buffer it hands out increments the counter; the counter decrements when that
 * buffer's reference count reaches zero (via {@link LoanedBuffer#addCloseAction}).
 *
 * <p>The wrapped allocator is the real Community allocator, so the underlying zero-copy / ref-count
 * semantics are exercised faithfully — this decorator only observes the open/close edges.
 */
final class LeakTrackingAllocator implements MemoryAllocator {

    private final MemoryAllocator delegate;
    private final AtomicLong outstanding = new AtomicLong(0L);

    LeakTrackingAllocator(MemoryAllocator delegate) {
        this.delegate = delegate;
    }

    long outstanding() {
        return outstanding.get();
    }

    private LoanedBuffer track(LoanedBuffer buffer) {
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
