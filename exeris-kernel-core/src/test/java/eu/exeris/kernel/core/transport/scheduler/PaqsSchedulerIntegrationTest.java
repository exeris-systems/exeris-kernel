/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.core.transport.scheduler;

import eu.exeris.kernel.core.memory.ResourceArbiter;
import eu.exeris.kernel.core.memory.ResourceArbiterTestHelper;
import eu.exeris.kernel.core.memory.WatermarkLevel;
import eu.exeris.kernel.core.memory.WatermarkManager;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LeakDetectionMode;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;
import eu.exeris.kernel.spi.transport.StreamPriority;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L2 Integration Tests: PAQS Scheduler � priority differentiation, counter invariants,
 * and live watermark transition behaviour.
 *
 * @since 0.5.0
 */
@Tag("integration")
@DisplayName("L2: PAQS Scheduler Integration")
class PaqsSchedulerIntegrationTest {
    private static final String ENGINE = "IntegrationTestEngine";
    private static final long TIMEOUT_MS = 5_000L;
    private static final long TOTAL = 1_000_000L;
    private final AtomicLong liveAllocated = new AtomicLong(0L);
    private WatermarkManager watermarkManager;
    private AdmissionController admissionController;
    private StreamLoadShedder loadShedder;

    @BeforeEach
    void setUp() {
        MemoryAllocator allocator = new MemoryAllocator() {
            @Override
            public MemoryStats stats() {
                long alloc = liveAllocated.get();
                return new MemoryStats(TOTAL, alloc, TOTAL - alloc, 0, 0, alloc, 0, 0, LeakDetectionMode.DISABLED);
            }

            @Override
            public LoanedBuffer allocate(AllocationHint h) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LoanedBuffer allocateNetwork(int b) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LoanedBuffer allocateCarrierSlab(int i) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LoanedBuffer allocateInfrastructure(long s) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void close() {
                // test stub — no off-heap resources to release
            }
        };
        watermarkManager = new WatermarkManager(allocator);
        ResourceArbiter arbiter = ResourceArbiterTestHelper.expiredGraceArbiter(watermarkManager);
        admissionController = new AdmissionController(arbiter);
        loadShedder = new StreamLoadShedder(ENGINE);
    }

    private void setPressure(WatermarkLevel level) {
        long allocated = switch (level) {
            case NORMAL -> (long) (TOTAL * 0.50);
            case WARNING -> (long) (TOTAL * 0.75);
            case CRITICAL -> (long) (TOTAL * 0.90);
            case SHEDDING -> (long) (TOTAL * 0.97);
        };
        liveAllocated.set(allocated);
        watermarkManager.refresh();
    }

    @Test
    @DisplayName("CRITICAL streams admitted; LOW streams shed under WARNING pressure")
    @Timeout(value = TIMEOUT_MS, unit = TimeUnit.MILLISECONDS)
    void criticalAdmittedLowShedUnderWarning() throws InterruptedException {
        setPressure(WatermarkLevel.WARNING);
        int half = 10;
        CountDownLatch admittedLatch = new CountDownLatch(half);
        PaqsScheduler sut = new PaqsScheduler(admissionController, loadShedder,
                stream -> admittedLatch.countDown(),
                stream -> stream.streamId() < half ? StreamPriority.CRITICAL : StreamPriority.LOW, ENGINE);
        for (TransportStream s : buildStreams(0, half * 2)) {
            sut.schedule(s);
        }
        assertThat(admittedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(loadShedder.shedCount()).isEqualTo(half);
    }

    @Test
    @DisplayName("Active count returns to zero after concurrent burst of 100 streams")
    @Timeout(value = TIMEOUT_MS, unit = TimeUnit.MILLISECONDS)
    void activeCountReturnsToZeroAfterBurst() throws InterruptedException {
        setPressure(WatermarkLevel.NORMAL);
        int count = 100;
        CountDownLatch allDone = new CountDownLatch(count);
        PaqsScheduler sut = new PaqsScheduler(admissionController, loadShedder,
                stream -> allDone.countDown(), s -> StreamPriority.NORMAL, ENGINE);
        for (TransportStream s : buildStreams(0, count)) {
            sut.schedule(s);
        }
        assertThat(allDone.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();
        CountDownLatch counterZero = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> awaitCounterZero(admissionController, counterZero));
        assertThat(counterZero.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(admissionController.activeStreamCount()).isZero();
    }

    private static void awaitCounterZero(AdmissionController controller, CountDownLatch latch) {
        while (controller.activeStreamCount() > 0) {
            Thread.onSpinWait();
        }
        latch.countDown();
    }

    @Test
    @DisplayName("Transition to SHEDDING watermark causes all new streams to be shed")
    @Timeout(value = TIMEOUT_MS, unit = TimeUnit.MILLISECONDS)
    void transitionToSheddingWatermarkShedsAll() {
        setPressure(WatermarkLevel.SHEDDING);
        int count = 20;
        PaqsScheduler sut = new PaqsScheduler(admissionController, loadShedder,
                stream -> {
                    // no-op handler — all streams are shed before handler can be invoked
                }, s -> StreamPriority.HIGH, ENGINE);
        for (TransportStream s : buildStreams(0, count)) {
            sut.schedule(s);
        }
        assertThat(loadShedder.shedCount()).isEqualTo(count);
        assertThat(admissionController.activeStreamCount()).isZero();
    }

    private static List<TransportStream> buildStreams(int start, int count) {
        List<TransportStream> streams = new ArrayList<>(count);
        for (int i = start; i < start + count; i++) {
            final long id = i;
            streams.add(new TransportStream() {
                @Override
                public int read(MemorySegment t, int m) {
                    return -1;
                }

                @Override
                public void write(MemorySegment s, int l) {
                    // test stub — write direction not exercised in integration tests
                }

                @Override
                public void queueWrite(LoanedBuffer b, int l) {
                    // test stub — async write path not exercised in integration tests
                }

                @Override
                public long streamId() {
                    return id;
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
                    // test stub — no resources to release on integration test stream stubs
                }
            });
        }
        return streams;
    }
}
