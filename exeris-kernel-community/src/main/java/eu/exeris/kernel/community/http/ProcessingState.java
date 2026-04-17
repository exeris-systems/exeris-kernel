/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;

import java.lang.foreign.MemorySegment;

@SuppressWarnings({
    "PMD.TooManyMethods",
    "PMD.CommentDefaultAccessModifier",
    "PMD.NullAssignment"
})
final class ProcessingState implements AutoCloseable {
    private LoanedBuffer aggregate;
    private long bufferedBytes;
    private long aggregateAllocationTimeNs;
    private long pipelinedRequestCount;
    private long totalRequestCount;

    LoanedBuffer aggregate() {
        return aggregate;
    }

    long bufferedBytes() {
        return bufferedBytes;
    }

    long aggregateAllocationTimeNs() {
        return aggregateAllocationTimeNs;
    }

    long pipelinedRequestCount() {
        return pipelinedRequestCount;
    }

    long totalRequestCount() {
        return totalRequestCount;
    }

    boolean hasAggregate() {
        return aggregate != null;
    }

    void ensureAggregate(MemoryAllocator allocator, int initialAggregateBytes) {
        aggregate = aggregate != null ? aggregate : allocator.allocateNetwork(initialAggregateBytes);
    }

    void ensureAggregateCapacity(MemoryAllocator allocator,
                                 long requiredBytes,
                                 int maxAggregateBytes) {
        if (aggregate == null || requiredBytes <= aggregate.capacity()) {
            return;
        }

        long targetCapacity = aggregate.capacity();
        while (targetCapacity < requiredBytes && targetCapacity < maxAggregateBytes) {
            targetCapacity = Math.min(targetCapacity << 1, maxAggregateBytes);
        }
        if (targetCapacity < requiredBytes) {
            throw new IllegalStateException("HTTP aggregate exceeds configured maximum");
        }

        try (LoanedBuffer previous = aggregate;
             LoanedBuffer expanded = allocator.allocateNetwork((int) targetCapacity)) {
            long previousSize = previous.size();
            if (previousSize > 0) {
                MemorySegment.copy(previous.segment(), 0, expanded.segment(), 0, previousSize);
                expanded.setSize(previousSize);
            }
            expanded.retain();
            aggregate = expanded;
        }
    }

    void resetBufferForNewAggregate() {
        bufferedBytes = 0;
        aggregateAllocationTimeNs = System.nanoTime();
    }

    void recordRequest() {
        totalRequestCount++;
        if (bufferedBytes > 0 && totalRequestCount > 1) {
            pipelinedRequestCount++;
        }
    }

    void updateBufferedBytes(long newBufferedBytes) {
        bufferedBytes = newBufferedBytes;
    }

    void forceReleaseAggregate() {
        if (aggregate != null) {
            aggregate.close();
            aggregate = null;
        }
        aggregateAllocationTimeNs = 0;
        pipelinedRequestCount = 0;
        totalRequestCount = 0;
    }

    void releaseAggregateIfIdle() {
        if (bufferedBytes == 0 && aggregate != null) {
            aggregate.close();
            aggregate = null;
        }
    }

    @Override
    public void close() {
        if (aggregate != null) {
            aggregate.close();
            aggregate = null;
        }
    }
}
