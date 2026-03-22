/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.core.memory.ResourceArbiter;
import eu.exeris.kernel.core.memory.WatermarkManager;
import eu.exeris.kernel.core.transport.TransportScopes;
import eu.exeris.kernel.core.transport.scheduler.AdmissionController;
import eu.exeris.kernel.core.transport.scheduler.PaqsScheduler;
import eu.exeris.kernel.core.transport.scheduler.StreamExecutionBackend;
import eu.exeris.kernel.core.transport.scheduler.StreamLoadShedder;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LeakDetectionMode;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;
import eu.exeris.kernel.spi.transport.StreamPriority;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Community: PAQS execution backend contract")
class CommunityPaqsExecutionBackendContractTest {

    @Test
    @DisplayName("custom backend preserves ScopedValue bindings")
    @Timeout(value = 5_000, unit = TimeUnit.MILLISECONDS)
    void customBackendPreservesScopedValueBindings() throws InterruptedException {
        MemoryAllocator allocator = stubAllocator(500_000L, 1_000_000L);
        WatermarkManager watermarkManager = new WatermarkManager(allocator);
        watermarkManager.refresh();

        ResourceArbiter arbiter = new ResourceArbiter(watermarkManager);
        AdmissionController admissionController = new AdmissionController(arbiter);
        StreamLoadShedder loadShedder = new StreamLoadShedder("CommunityTestEngine");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<StreamPriority> capturedPriority = new AtomicReference<>();
        AtomicReference<Long> capturedStreamId = new AtomicReference<>();

        StreamExecutionBackend backend = (threadName, task) -> task.run();

        try (PaqsScheduler scheduler = new PaqsScheduler(
                admissionController,
                loadShedder,
                stream -> {
                    capturedPriority.set(TransportScopes.STREAM_PRIORITY.get());
                    capturedStreamId.set(TransportScopes.STREAM_ID.get());
                    latch.countDown();
                },
                stream -> StreamPriority.HIGH,
                "CommunityTestEngine",
                backend)) {
            scheduler.schedule(stubStream(501L));
            assertThat(latch.await(5_000, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(capturedPriority.get()).isEqualTo(StreamPriority.HIGH);
            assertThat(capturedStreamId.get()).isEqualTo(501L);
        }
    }

    private static MemoryAllocator stubAllocator(long allocated, long total) {
        return new MemoryAllocator() {
            @Override
            public MemoryStats stats() {
                return new MemoryStats(total, allocated, total - allocated,
                        0L, 0L, allocated, 0, 0L, LeakDetectionMode.DISABLED);
            }

            @Override
            public LoanedBuffer allocate(AllocationHint hint) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LoanedBuffer allocateNetwork(int estimatedBytes) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LoanedBuffer allocateCarrierSlab(int carrierIndex) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LoanedBuffer allocateInfrastructure(long sizeBytes) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void close() {
                // No resources in this test stub.
            }
        };
    }

    private static TransportStream stubStream(long streamId) {
        return new TransportStream() {
            @Override
            public int read(MemorySegment target, int maxBytes) {
                return -1;
            }

            @Override
            public void write(MemorySegment source, int length) {
                // Not used by this scheduler test.
            }

            @Override
            public void queueWrite(LoanedBuffer buffer, int length) {
                // Not used by this scheduler test.
            }

            @Override
            public long streamId() {
                return streamId;
            }

            @Override
            public boolean isBidirectional() {
                return true;
            }

            @Override
            public boolean isClientInitiated() {
                return true;
            }

            @Override
            public TransportConnection connection() {
                return null;
            }

            @Override
            public boolean hasPendingData() {
                return false;
            }

            @Override
            public void close() {
                // No resources in this test stub.
            }
        };
    }
}
