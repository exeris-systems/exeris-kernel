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

@SuppressWarnings("PMD.TooManyMethods")
/* default */ final class ProcessingState implements AutoCloseable {
    private LoanedBuffer aggregate;
    private boolean aggregateActive;
    private long bufferedBytes;
    private long aggregateAllocationTimeNs;
    private long pipelinedRequestCount;
    private long totalRequestCount;

    /* default */ LoanedBuffer aggregate() {
        return aggregateActive ? aggregate : null;
    }

    /* default */ long bufferedBytes() {
        return bufferedBytes;
    }

    /* default */ long aggregateAllocationTimeNs() {
        return aggregateAllocationTimeNs;
    }

    /* default */ long pipelinedRequestCount() {
        return pipelinedRequestCount;
    }

    /* default */ long totalRequestCount() {
        return totalRequestCount;
    }

    /* default */ boolean hasAggregate() {
        return aggregateActive;
    }

    /* default */ void ensureAggregate(MemoryAllocator allocator, int initialAggregateBytes) {
        if (!aggregateActive) {
            aggregate = allocator.allocateNetwork(initialAggregateBytes);
            aggregateActive = true;
        }
    }

    /* default */ void ensureAggregateCapacity(MemoryAllocator allocator,
                                               long requiredBytes,
                                               int maxAggregateBytes) {
        if (!aggregateActive || requiredBytes <= aggregate.capacity()) {
            return;
        }

        replaceAggregate(allocateExpandedAggregate(allocator, aggregate, requiredBytes, maxAggregateBytes));
    }

    private void replaceAggregate(LoanedBuffer expanded) {
        closeAggregate();
        aggregate = expanded;
        aggregateActive = true;
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

    /* default */ void resetBufferForNewAggregate() {
        bufferedBytes = 0;
        aggregateAllocationTimeNs = System.nanoTime();
    }

    /* default */ void recordRequest() {
        totalRequestCount++;
        if (bufferedBytes > 0 && totalRequestCount > 1) {
            pipelinedRequestCount++;
        }
    }

    /* default */ void updateBufferedBytes(long newBufferedBytes) {
        bufferedBytes = newBufferedBytes;
    }

    /* default */ void forceReleaseAggregate() {
        closeAggregate();
        aggregateAllocationTimeNs = 0;
        pipelinedRequestCount = 0;
        totalRequestCount = 0;
    }

    /* default */ void releaseAggregateIfIdle() {
        if (bufferedBytes == 0) {
            closeAggregate();
        }
    }

    @Override
    public void close() {
        closeAggregate();
    }

    private void closeAggregate() {
        if (aggregateActive && aggregate != null) {
            aggregate.close();
            aggregateActive = false;
        }
    }

    private static final class AggregateExpansion implements AutoCloseable {
        private final LoanedBuffer buffer;
        private boolean taken;

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
            taken = true;
            return buffer;
        }

        @Override
        public void close() {
            if (!taken) {
                buffer.close();
            }
        }
    }
}
