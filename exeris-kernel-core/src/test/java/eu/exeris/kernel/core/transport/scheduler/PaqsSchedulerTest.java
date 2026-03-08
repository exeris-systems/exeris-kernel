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
import eu.exeris.kernel.core.transport.TransportScopes;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LeakDetectionMode;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;
import eu.exeris.kernel.spi.transport.StreamPriority;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 Unit Tests: {@link PaqsScheduler} — VT lifecycle, ScopedValue bindings,
 * admit/shed routing, null handling, counter cleanup on error.
 *
 * @since 0.5.0
 */
@DisplayName("L1: PaqsScheduler")
class PaqsSchedulerTest {

    private static final String ENGINE = "TestEngine";
    private static final long TIMEOUT_MS = 3_000L;

    // =========================================================================
    // Test infrastructure
    // =========================================================================

    private static MemoryAllocator stubAllocator(long allocated, long total) {
        return new MemoryAllocator() {
            @Override
            public MemoryStats stats() {
                return new MemoryStats(total, allocated, total - allocated,
                        0, 0, allocated, 0, 0, LeakDetectionMode.DISABLED);
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
    }

    private static PaqsScheduler schedulerAtLevel(WatermarkLevel level,
                                                  java.util.function.Consumer<TransportStream> handlerBody) {
        long total = 1_000_000L;
        long allocated = utilizationForLevel(level, total);
        MemoryAllocator alloc = stubAllocator(allocated, total);
        WatermarkManager mgr = new WatermarkManager(alloc);
        mgr.refresh();
        ResourceArbiter arbiter = ResourceArbiterTestHelper.expiredGraceArbiter(mgr);
        AdmissionController controller = new AdmissionController(arbiter);
        StreamLoadShedder shedder = new StreamLoadShedder(ENGINE);
        return new PaqsScheduler(controller, shedder, handlerBody::accept,
                stream -> StreamPriority.NORMAL, ENGINE);
    }

    private static long utilizationForLevel(WatermarkLevel level, long total) {
        return switch (level) {
            case NORMAL -> (long) (total * 0.50);
            case WARNING -> (long) (total * 0.75);
            case CRITICAL -> (long) (total * 0.90);
            case SHEDDING -> (long) (total * 0.97);
        };
    }

    private static TransportStream stubStream(long id, AtomicBoolean closedFlag) {
        return new TransportStream() {
            @Override
            public int read(MemorySegment target, int maxBytes) {
                return -1;
            }

            @Override
            public void write(MemorySegment source, int length) {
                // test stub — write direction not exercised in scheduler unit tests
            }

            @Override
            public void queueWrite(LoanedBuffer buffer, int length) {
                // test stub — async write path not exercised in scheduler unit tests
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
                closedFlag.set(true);
            }
        };
    }

    // =========================================================================
    // Construction guards
    // =========================================================================

    @Nested
    @DisplayName("Construction guards")
    class ConstructionGuards {

        @Test
        @DisplayName("null admissionController → NPE")
        void nullControllerThrows() {
            assertThatThrownBy(ConstructionGuards::buildWithNullController)
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null handler → NPE")
        void nullHandlerThrows() {
            WatermarkManager mgr = new WatermarkManager(stubAllocator(0, 1_000_000));
            ResourceArbiter arbiter = ResourceArbiterTestHelper.expiredGraceArbiter(mgr);
            assertThatThrownBy(() -> buildWithNullHandler(arbiter))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("blank engineName → IllegalArgumentException")
        void blankEngineNameThrows() {
            WatermarkManager mgr = new WatermarkManager(stubAllocator(0, 1_000_000));
            ResourceArbiter arbiter = ResourceArbiterTestHelper.expiredGraceArbiter(mgr);
            assertThatThrownBy(() -> buildWithBlankEngineName(arbiter))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        private static PaqsScheduler buildWithNullController() {
            return new PaqsScheduler(null, new StreamLoadShedder(ENGINE),
                    stream -> {}, s -> StreamPriority.NORMAL, ENGINE);
        }

        private static PaqsScheduler buildWithNullHandler(ResourceArbiter arbiter) {
            return new PaqsScheduler(new AdmissionController(arbiter), new StreamLoadShedder(ENGINE),
                    null, s -> StreamPriority.NORMAL, ENGINE);
        }

        private static PaqsScheduler buildWithBlankEngineName(ResourceArbiter arbiter) {
            return new PaqsScheduler(new AdmissionController(arbiter), new StreamLoadShedder(ENGINE),
                    stream -> {}, s -> StreamPriority.NORMAL, "");
        }
    }

    // =========================================================================
    // Happy path — stream is admitted, VT runs, counter resets
    // =========================================================================

    @Nested
    @DisplayName("Admitted stream lifecycle")
    class AdmittedStreamLifecycle {

        @Test
        @DisplayName("handler is invoked for admitted stream")
        @Timeout(value = TIMEOUT_MS, unit = TimeUnit.MILLISECONDS)
        void handlerInvokedForAdmittedStream() throws InterruptedException {
            CountDownLatch handlerRan = new CountDownLatch(1);
            PaqsScheduler scheduler = schedulerAtLevel(WatermarkLevel.NORMAL, stream -> handlerRan.countDown());

            scheduler.schedule(stubStream(1L, new AtomicBoolean()));

            assertThat(handlerRan.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();
        }

        @Test
        @DisplayName("active stream count returns to zero after stream completes")
        @Timeout(value = TIMEOUT_MS, unit = TimeUnit.MILLISECONDS)
        void activeCountReturnsToZeroAfterCompletion() throws InterruptedException {
            CountDownLatch handlerDone = new CountDownLatch(1);
            AdmissionController controller = buildController(WatermarkLevel.NORMAL);
            PaqsScheduler sut = new PaqsScheduler(controller, new StreamLoadShedder(ENGINE),
                    stream -> handlerDone.countDown(), s -> StreamPriority.HIGH, ENGINE);

            sut.schedule(stubStream(7L, new AtomicBoolean()));
            assertThat(handlerDone.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();

            CountDownLatch counterZero = new CountDownLatch(1);
            Thread.ofVirtual().start(() -> awaitCounterZero(controller, counterZero));
            assertThat(counterZero.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(controller.activeStreamCount()).isZero();
        }

        @Test
        @DisplayName("ScopedValues are bound in stream handler")
        @Timeout(value = TIMEOUT_MS, unit = TimeUnit.MILLISECONDS)
        void scopedValuesBoundInHandler() throws InterruptedException {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<StreamPriority> capturedPriority = new AtomicReference<>();
            AtomicReference<Long> capturedStreamId = new AtomicReference<>();

            AdmissionController controller = buildController(WatermarkLevel.NORMAL);
            eu.exeris.kernel.spi.transport.StreamHandler capturingHandler =
                    stream -> captureScopedValuesAndSignal(capturedPriority, capturedStreamId, latch);
            PaqsScheduler sut = new PaqsScheduler(controller, new StreamLoadShedder(ENGINE),
                    capturingHandler, s -> StreamPriority.HIGH, ENGINE);

            sut.schedule(stubStream(55L, new AtomicBoolean()));
            assertThat(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();

            assertThat(capturedPriority.get()).isEqualTo(StreamPriority.HIGH);
            assertThat(capturedStreamId.get()).isEqualTo(55L);
        }

        private void captureScopedValuesAndSignal(AtomicReference<StreamPriority> capturedPriority,
                                                   AtomicReference<Long> capturedStreamId,
                                                   CountDownLatch latch) {
            capturedPriority.set(TransportScopes.STREAM_PRIORITY.get());
            capturedStreamId.set(TransportScopes.STREAM_ID.get());
            latch.countDown();
        }
    }

    // =========================================================================
    // Load shedding — stream is not admitted
    // =========================================================================

    @Nested
    @DisplayName("Shed stream lifecycle")
    class ShedStreamLifecycle {

        @Test
        @DisplayName("stream.close() called when shed by memory pressure")
        void streamClosedWhenShed() {
            AtomicBoolean closed = new AtomicBoolean(false);
            PaqsScheduler scheduler = schedulerAtLevel(WatermarkLevel.SHEDDING, stream -> {
                // no-op handler — test verifies stream.close() is called during shedding
            });

            scheduler.schedule(stubStream(100L, closed));

            assertThat(closed).isTrue();
        }

        @Test
        @DisplayName("handler never invoked when stream is shed")
        @Timeout(value = 1_000L, unit = TimeUnit.MILLISECONDS)
        void handlerNotInvokedWhenShed() throws InterruptedException {
            CountDownLatch handlerLatch = new CountDownLatch(1);
            PaqsScheduler scheduler = schedulerAtLevel(WatermarkLevel.SHEDDING,
                    stream -> handlerLatch.countDown());

            scheduler.schedule(stubStream(200L, new AtomicBoolean()));

            assertThat(handlerLatch.await(200, TimeUnit.MILLISECONDS))
                    .as("handler must never be invoked for a shed stream")
                    .isFalse();
        }

        @Test
        @DisplayName("shed count increments when stream is shed")
        void shedCountIncrements() {
            PaqsScheduler scheduler = schedulerAtLevel(WatermarkLevel.SHEDDING, stream -> {
                // no-op handler — test verifies shed counter is incremented
            });
            for (int i = 0; i < 3; i++) {
                scheduler.schedule(stubStream(i, new AtomicBoolean()));
            }
            assertThat(scheduler.loadShedder().shedCount()).isEqualTo(3L);
        }

        @Test
        @DisplayName("active count is NOT incremented when stream is shed")
        void activeCountNotIncrementedOnShed() {
            PaqsScheduler scheduler = schedulerAtLevel(WatermarkLevel.SHEDDING, stream -> {
                // no-op handler — test verifies counter invariant during shedding
            });
            scheduler.schedule(stubStream(300L, new AtomicBoolean()));
            assertThat(scheduler.admissionController().activeStreamCount()).isZero();
        }
    }

    // =========================================================================
    // Handler exception — active count is still decremented
    // =========================================================================

    @Nested
    @DisplayName("Handler exception resilience")
    class HandlerExceptionResilience {

        @Test
        @DisplayName("active count returns to zero even when handler throws")
        @Timeout(value = TIMEOUT_MS, unit = TimeUnit.MILLISECONDS)
        void activeCountReturnsToZeroAfterHandlerException() throws InterruptedException {
            CountDownLatch errorLatch = new CountDownLatch(1);
            AdmissionController controller = buildController(WatermarkLevel.NORMAL);
            eu.exeris.kernel.spi.transport.StreamHandler failingHandler =
                    stream -> signalAndFail(errorLatch);
            PaqsScheduler sut = new PaqsScheduler(controller, new StreamLoadShedder(ENGINE),
                    failingHandler, s -> StreamPriority.NORMAL, ENGINE);

            sut.schedule(stubStream(400L, new AtomicBoolean()));
            assertThat(errorLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();

            CountDownLatch counterZero = new CountDownLatch(1);
            Thread.ofVirtual().start(() -> awaitCounterZero(controller, counterZero));
            assertThat(counterZero.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(controller.activeStreamCount()).isZero();
        }

        private static void signalAndFail(CountDownLatch latch) {
            latch.countDown();
            throw new RuntimeException("simulated handler failure");
        }
    }

    // =========================================================================
    // Null stream guard
    // =========================================================================

    @Nested
    @DisplayName("Null stream guard")
    class NullStreamGuard {

        @Test
        @DisplayName("schedule(null) → NullPointerException")
        void nullStreamThrows() {
            PaqsScheduler scheduler = schedulerAtLevel(WatermarkLevel.NORMAL, stream -> {
                // no-op handler — test verifies null guard before handler is reached
            });
            assertThatThrownBy(() -> scheduler.schedule(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // =========================================================================
    // Null priority extractor fallback
    // =========================================================================

    @Nested
    @DisplayName("Priority extractor returns null — falls back to NORMAL")
    class NullPriorityFallback {

        @Test
        @DisplayName("null extractor result → NORMAL priority, handler invoked")
        @Timeout(value = TIMEOUT_MS, unit = TimeUnit.MILLISECONDS)
        void nullPriorityFallsBackToNormal() throws InterruptedException {
            CountDownLatch latch = new CountDownLatch(1);
            AdmissionController controller = buildController(WatermarkLevel.NORMAL);
            PaqsScheduler sut = new PaqsScheduler(controller, new StreamLoadShedder(ENGINE),
                    stream -> latch.countDown(), stream -> null, ENGINE);

            sut.schedule(stubStream(999L, new AtomicBoolean()));
            assertThat(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();
        }
    }

    private static AdmissionController buildController(WatermarkLevel level) {
        long total = 1_000_000L;
        long allocated = utilizationForLevel(level, total);
        WatermarkManager mgr = new WatermarkManager(stubAllocator(allocated, total));
        mgr.refresh();
        return new AdmissionController(ResourceArbiterTestHelper.expiredGraceArbiter(mgr));
    }

    private static void awaitCounterZero(AdmissionController controller, CountDownLatch latch) {
        while (controller.activeStreamCount() > 0) {
            Thread.onSpinWait();
        }
        latch.countDown();
    }
}
