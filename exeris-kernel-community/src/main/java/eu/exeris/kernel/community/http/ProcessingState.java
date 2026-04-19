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
        if (aggregate == null) {
            aggregate = allocator.allocateNetwork(initialAggregateBytes);
        }
    }

    void ensureAggregateCapacity(MemoryAllocator allocator,
                                 long requiredBytes,
                                 int maxAggregateBytes) {
        if (aggregate == null || requiredBytes <= aggregate.capacity()) {
            return;
        }

        replaceAggregate(allocateExpandedAggregate(allocator, aggregate, requiredBytes, maxAggregateBytes));
    }

    private void replaceAggregate(LoanedBuffer expanded) {
        if (aggregate != null) {
            aggregate.close();
        }
        aggregate = expanded;
    }

    private static LoanedBuffer allocateExpandedAggregate(MemoryAllocator allocator,
                                                          LoanedBuffer current,
                                                          long requiredBytes,
                                                          int maxAggregateBytes) {
        long targetCapacity = current.capacity();
        while (targetCapacity < requiredBytes) {
            if (targetCapacity >= maxAggregateBytes) {
                throw new IllegalStateException("HTTP aggregate exceeds configured maximum");
            }
            targetCapacity = Math.min(targetCapacity << 1, maxAggregateBytes);
        }

        try (AggregateExpansion expansion = new AggregateExpansion(
                allocator.allocateNetwork(Math.toIntExact(targetCapacity)))) {
            expansion.copyFrom(current);
            return expansion.take();
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

    private static final class AggregateExpansion implements AutoCloseable {
        private LoanedBuffer buffer;

        private AggregateExpansion(LoanedBuffer buffer) {
            this.buffer = buffer;
        }

        private void copyFrom(LoanedBuffer current) {
            long currentSize = current.size();
            if (currentSize > 0) {
                MemorySegment.copy(current.segment(), 0, buffer.segment(), 0, currentSize);
                buffer.setSize(currentSize);
            }
        }

        private LoanedBuffer take() {
            LoanedBuffer taken = buffer;
            buffer = null;
            return taken;
        }

        @Override
        public void close() {
            if (buffer != null) {
                buffer.close();
                buffer = null;
            }
        }
    }
}
