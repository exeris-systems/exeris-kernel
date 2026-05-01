/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L2 Integration Tests: PAQS Scheduler — priority differentiation, counter invariants,
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
    private static final String HANDLER_FAILURE_EVENT = "eu.exeris.kernel.core.transport.PaqsHandlerFailure";
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

    @Test
    @DisplayName("emits PaqsHandlerFailure event with stream/engine/phase/cause on handler failure")
    @Timeout(value = TIMEOUT_MS, unit = TimeUnit.MILLISECONDS)
    void emitsPaqsHandlerFailureEventOnHandlerFailure() throws InterruptedException {
        setPressure(WatermarkLevel.NORMAL);
        long streamId = 919L;
        CountDownLatch eventLatch = new CountDownLatch(1);
        AtomicReference<RecordedEvent> captured = new AtomicReference<>();

        try (RecordingStream rs = new RecordingStream()) {
            rs.enable(HANDLER_FAILURE_EVENT);
            rs.onEvent(HANDLER_FAILURE_EVENT, event -> {
                captured.compareAndSet(null, event);
                eventLatch.countDown();
            });
            rs.startAsync();

            PaqsScheduler sut = new PaqsScheduler(admissionController, loadShedder,
                    stream -> {
                        throw new IllegalStateException("deterministic-failure");
                    }, s -> StreamPriority.NORMAL, ENGINE);

            sut.schedule(buildFailingStream(streamId));

            assertThat(eventLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();
        }

        RecordedEvent event = captured.get();
        assertThat(event).isNotNull();
        assertThat(event.getLong("streamId")).isEqualTo(streamId);
        assertThat(event.getString("engineName")).isEqualTo(ENGINE);
        assertThat(event.getString("exceptionClass")).isEqualTo(IllegalStateException.class.getName());
        assertThat(event.getString("lifecyclePhase")).isEqualTo("HANDLER");
    }

    private static TransportStream buildFailingStream(long streamId) {
        return new TransportStream() {
            @Override
            public int read(MemorySegment target, int maxBytes) {
                return -1;
            }

            @Override
            public void write(MemorySegment source, int length) {
                // test stub — write direction not exercised in integration tests
            }

            @Override
            public void queueWrite(LoanedBuffer buffer, int length) {
                // test stub — async write path not exercised in integration tests
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
                // test stub — no resources to release on integration test stream stubs
            }
        };
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
