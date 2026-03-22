/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.transport.tck;

import eu.exeris.kernel.core.memory.ResourceArbiter;
import eu.exeris.kernel.core.memory.ResourceArbiterTestHelper;
import eu.exeris.kernel.core.memory.WatermarkLevel;
import eu.exeris.kernel.core.memory.WatermarkManager;
import eu.exeris.kernel.core.transport.TransportScopes;
import eu.exeris.kernel.core.transport.scheduler.AdmissionController;
import eu.exeris.kernel.core.transport.scheduler.PaqsScheduler;
import eu.exeris.kernel.core.transport.scheduler.StreamExecutionBackend;
import eu.exeris.kernel.core.transport.scheduler.StreamLoadShedder;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;
import eu.exeris.kernel.spi.transport.StreamHandler;
import eu.exeris.kernel.spi.transport.StreamPriority;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Core TCK: Abstract base for {@link PaqsScheduler} behavioural contract verification.
 *
 * <h2>Purpose (TCK Inquisition)</h2>
 * <p>This abstract class is the final judge for PAQS correctness. Community and
 * Enterprise transport driver implementations must subclass this TCK and provide a
 * configured {@link WatermarkManager} wired to their specific allocator. If any test
 * fails, the implementation violates the PAQS contract and MUST NOT be released.
 *
 * <h2>Watermark Pressure Simulation</h2>
 * <p>Subclasses implement {@link #createDynamicAllocator(AtomicLong, long)} to provide
 * a {@link MemoryAllocator} whose {@code stats()} reads from the live {@code allocated}
 * reference. Tests call {@link #setPressure(WatermarkLevel)} to update the allocated
 * value and call {@link WatermarkManager#refresh()} — this avoids the package-private
 * {@code forceLevel} method.
 *
 * <h2>Packaging Note (The Wall)</h2>
 * <p>This class lives in {@code exeris-kernel-core}'s test-jar. It imports Core classes
 * because it tests Core orchestration, NOT SPI contracts. The {@code exeris-kernel-tck}
 * module tests SPI contracts only.
 *
 * @since 0.5.0
 */
public abstract class AbstractPaqsSchedulerTck {
    protected static final String ENGINE_NAME = "TckTestEngine";
    protected static final long TIMEOUT_MS = 5_000L;
    protected static final long TOTAL_BYTES = 1_000_000L;
    protected final AtomicLong liveAllocated = new AtomicLong(0L);
    protected WatermarkManager watermarkManager;
    protected AdmissionController admissionController;
    protected StreamLoadShedder loadShedder;

    /**
     * Provides a {@link MemoryAllocator} whose {@code stats()} reads the live
     * allocated value from {@code allocated} and uses {@code totalBytes} as the total.
     * Typically an anonymous class wrapping {@link MemoryStats}.
     */
    protected abstract MemoryAllocator createDynamicAllocator(AtomicLong allocated, long totalBytes);

    @BeforeEach
    final void setUpSchedulerInfrastructure() {
        MemoryAllocator allocator = createDynamicAllocator(liveAllocated, TOTAL_BYTES);
        watermarkManager = new WatermarkManager(allocator);
        ResourceArbiter arbiter = ResourceArbiterTestHelper.expiredGraceArbiter(watermarkManager);
        admissionController = new AdmissionController(arbiter);
        loadShedder = new StreamLoadShedder(ENGINE_NAME);
    }

    protected final void setPressure(WatermarkLevel level) {
        long allocated = switch (level) {
            case NORMAL -> (long) (TOTAL_BYTES * 0.50);
            case WARNING -> (long) (TOTAL_BYTES * 0.75);
            case CRITICAL -> (long) (TOTAL_BYTES * 0.90);
            case SHEDDING -> (long) (TOTAL_BYTES * 0.97);
        };
        liveAllocated.set(allocated);
        watermarkManager.refresh();
    }

    // =========================================================================
    // Priority differentiation
    // =========================================================================
    @Nested
    @DisplayName("Priority differentiation under memory pressure")
    class PriorityDifferentiation {
        @Test
        @DisplayName("CRITICAL admitted under THROTTLE (WARNING watermark)")
        @Timeout(value = TIMEOUT_MS, unit = TimeUnit.MILLISECONDS)
        void criticalAdmittedUnderThrottle() throws InterruptedException {
            setPressure(WatermarkLevel.WARNING);
            CountDownLatch latch = new CountDownLatch(1);
            buildScheduler(stream -> latch.countDown(), s -> StreamPriority.CRITICAL).schedule(stubStream(1L));
            assertThat(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();
        }

        @Test
        @DisplayName("TELEMETRY shed under THROTTLE (WARNING watermark)")
        void telemetryShedUnderThrottle() {
            setPressure(WatermarkLevel.WARNING);
            buildScheduler(stream -> {
                // no-op handler — stream is shed before handler is invoked
            }, s -> StreamPriority.TELEMETRY).schedule(stubStream(2L));
            assertThat(loadShedder.shedCount()).isEqualTo(1L);
        }

        @Test
        @DisplayName("CRITICAL admitted under REJECT (CRITICAL watermark, TRANSPORT_IO context)")
        @Timeout(value = TIMEOUT_MS, unit = TimeUnit.MILLISECONDS)
        void criticalAdmittedUnderReject() throws InterruptedException {
            setPressure(WatermarkLevel.CRITICAL);
            CountDownLatch latch = new CountDownLatch(1);
            buildScheduler(stream -> latch.countDown(), s -> StreamPriority.CRITICAL).schedule(stubStream(3L));
            assertThat(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();
        }

        @Test
        @DisplayName("All priorities shed under SHED_LOAD (SHEDDING watermark)")
        void allShedUnderShedLoad() {
            setPressure(WatermarkLevel.SHEDDING);
            PaqsScheduler sut = buildScheduler(stream -> {
                // no-op handler — all streams shed at SHEDDING watermark before handler
            }, s -> StreamPriority.CRITICAL);
            sut.schedule(stubStream(4L));
            sut.schedule(stubStream(5L));
            assertThat(loadShedder.shedCount()).isEqualTo(2L);
        }
    }

    // =========================================================================
    // ScopedValue propagation
    // =========================================================================
    @Nested
    @DisplayName("ScopedValue propagation in admitted stream Virtual Thread")
    class ScopedValuePropagation {
        @Test
        @DisplayName("STREAM_PRIORITY ScopedValue is bound to the handler Virtual Thread")
        @Timeout(value = TIMEOUT_MS, unit = TimeUnit.MILLISECONDS)
        void streamPriorityBound() throws InterruptedException {
            setPressure(WatermarkLevel.NORMAL);
            AtomicReference<StreamPriority> captured = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            buildScheduler(stream -> {
                captured.set(TransportScopes.STREAM_PRIORITY.get());
                latch.countDown();
            }, s -> StreamPriority.HIGH).schedule(stubStream(10L));
            assertThat(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(captured.get()).isEqualTo(StreamPriority.HIGH);
        }

        @Test
        @DisplayName("STREAM_ID ScopedValue is bound to the handler Virtual Thread")
        @Timeout(value = TIMEOUT_MS, unit = TimeUnit.MILLISECONDS)
        void streamIdBound() throws InterruptedException {
            setPressure(WatermarkLevel.NORMAL);
            AtomicReference<Long> captured = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            buildScheduler(stream -> {
                captured.set(TransportScopes.STREAM_ID.get());
                latch.countDown();
            }, s -> StreamPriority.NORMAL).schedule(stubStream(77L));
            assertThat(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(captured.get()).isEqualTo(77L);
        }
    }

    // =========================================================================
    // Execution backend seam contract
    // =========================================================================
    @Nested
    @DisplayName("Execution backend seam contract")
    class ExecutionBackendSeamContract {
        @Test
        @DisplayName("custom execution backend preserves ScopedValue bindings")
        @Timeout(value = TIMEOUT_MS, unit = TimeUnit.MILLISECONDS)
        void customExecutionBackendPreservesScopedValueBindings() throws InterruptedException {
            setPressure(WatermarkLevel.NORMAL);
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<StreamPriority> capturedPriority = new AtomicReference<>();
            AtomicReference<Long> capturedStreamId = new AtomicReference<>();
            StreamExecutionBackend backend = (threadName, task) -> task.run();

            buildScheduler(stream -> {
                capturedPriority.set(TransportScopes.STREAM_PRIORITY.get());
                capturedStreamId.set(TransportScopes.STREAM_ID.get());
                latch.countDown();
            }, s -> StreamPriority.HIGH, backend).schedule(stubStream(501L));

            assertThat(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(capturedPriority.get()).isEqualTo(StreamPriority.HIGH);
            assertThat(capturedStreamId.get()).isEqualTo(501L);
        }
    }

    // =========================================================================
    // Counter invariants
    // =========================================================================
    @Nested
    @DisplayName("Active stream counter invariants")
    class CounterInvariants {
        @Test
        @DisplayName("Active count returns to zero after all admitted streams complete")
        @Timeout(value = TIMEOUT_MS, unit = TimeUnit.MILLISECONDS)
        void activeCountZeroAfterCompletion() throws InterruptedException {
            setPressure(WatermarkLevel.NORMAL);
            int count = 20;
            CountDownLatch allDone = new CountDownLatch(count);
            PaqsScheduler sut = buildScheduler(stream -> allDone.countDown(), s -> StreamPriority.NORMAL);
            for (int i = 0; i < count; i++) {
                sut.schedule(stubStream(i));
            }
            assertThat(allDone.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();
            CountDownLatch counterZero = new CountDownLatch(1);
            Thread.ofVirtual().start(() -> {
                while (admissionController.activeStreamCount() > 0) {
                    Thread.onSpinWait();
                }
                counterZero.countDown();
            });
            assertThat(counterZero.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(admissionController.activeStreamCount()).isZero();
        }

        @Test
        @DisplayName("Active count does not increment when stream is shed")
        void activeCountNotIncrementedOnShed() {
            setPressure(WatermarkLevel.SHEDDING);
            buildScheduler(stream -> {
                // no-op handler — stream is shed before handler can be invoked
            }, s -> StreamPriority.HIGH).schedule(stubStream(100L));
            assertThat(admissionController.activeStreamCount()).isZero();
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================
    protected PaqsScheduler buildScheduler(StreamHandler handler,
                                           Function<TransportStream, StreamPriority> extractor) {
        return new PaqsScheduler(admissionController, loadShedder, handler, extractor, ENGINE_NAME);
    }

    protected PaqsScheduler buildScheduler(StreamHandler handler,
                                           Function<TransportStream, StreamPriority> extractor,
                                           StreamExecutionBackend executionBackend) {
        return new PaqsScheduler(admissionController, loadShedder, handler, extractor, ENGINE_NAME, executionBackend);
    }

    protected static TransportStream stubStream(long id) {
        return new TransportStream() {
            @Override
            public int read(MemorySegment t, int m) {
                return -1;
            }

            @Override
            public void write(MemorySegment s, int l) {
                // test stub — write direction not exercised by TCK scheduler tests
            }

            @Override
            public void queueWrite(LoanedBuffer b, int l) {
                // test stub — async write path not exercised by TCK scheduler tests
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
                // test stub — no resources to release on TCK transport stubs
            }
        };
    }
}
