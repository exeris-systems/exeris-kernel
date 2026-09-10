/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.memory.MemoryStats;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessingStateTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    @AfterAll
    @SuppressWarnings("unused")
    static void closeAllocator() {
        ALLOCATOR.close();
    }

    @Test
    void failedExpansionAllocationKeepsExistingAggregateLive() {
        byte[] original = "ping".getBytes(StandardCharsets.US_ASCII);

        try (ProcessingState state = new ProcessingState();
             MemoryAllocator allocator = new FailOnSecondNetworkAllocationAllocator(ALLOCATOR)) {
            state.ensureAggregate(allocator, 8);
            LoanedBuffer aggregate = state.aggregate();

            MemorySegment.copy(MemorySegment.ofArray(original), 0, aggregate.segment(), 0, original.length);
            aggregate.setSize(original.length);

            long requiredBytes = aggregate.capacity() + 1;
            int maxAggregateBytes = Math.toIntExact(Math.max(requiredBytes + 1, aggregate.capacity() << 1));

            assertThatThrownBy(() -> state.ensureAggregateCapacity(allocator, requiredBytes, maxAggregateBytes))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("simulated expansion failure");

            assertThat(state.aggregate()).isSameAs(aggregate);
            assertThat(aggregate.isAlive()).isTrue();
            assertThat(aggregate.size()).isEqualTo(original.length);

            byte[] preserved = new byte[original.length];
            MemorySegment.copy(aggregate.segment(), 0, MemorySegment.ofArray(preserved), 0, original.length);
            assertThat(preserved).containsExactly(original);
        }
    }

    private static final class FailOnSecondNetworkAllocationAllocator implements MemoryAllocator {
        private final MemoryAllocator delegate;
        private int networkAllocations;

        private FailOnSecondNetworkAllocationAllocator(MemoryAllocator delegate) {
            this.delegate = delegate;
        }

        @Override
        public LoanedBuffer allocate(AllocationHint hint) {
            return delegate.allocate(hint);
        }

        @Override
        public LoanedBuffer allocateNetwork(int estimatedBytes) {
            networkAllocations++;
            if (networkAllocations >= 2) {
                throw new IllegalStateException("simulated expansion failure");
            }
            return delegate.allocateNetwork(estimatedBytes);
        }

        @Override
        public LoanedBuffer allocateCarrierSlab(int carrierIndex) {
            return delegate.allocateCarrierSlab(carrierIndex);
        }

        @Override
        public LoanedBuffer allocateInfrastructure(long sizeBytes) {
            return delegate.allocateInfrastructure(sizeBytes);
        }

        @Override
        public MemoryStats stats() {
            return delegate.stats();
        }

        @Override
        public void close() {
            // delegate lifecycle is owned by the outer test class
        }
    }
}
