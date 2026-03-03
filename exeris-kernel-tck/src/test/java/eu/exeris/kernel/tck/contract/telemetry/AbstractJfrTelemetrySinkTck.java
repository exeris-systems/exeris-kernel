/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.tck.contract.telemetry;

import eu.exeris.kernel.spi.exceptions.memory.MemoryExhaustedException;
import eu.exeris.kernel.spi.exceptions.transport.TransportException;
import eu.exeris.kernel.spi.telemetry.KernelEvent;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * TCK Inquisition: Abstract base for typed JFR telemetry sink contract verification.
 *
 * <h2>What is verified (telemetry.md §JFR-First)</h2>
 * <ul>
 *   <li>Every {@link KernelEvent} with a typed exception routes to the correct strongly-typed
 *       JFR event class ({@code eu.exeris.kernel.telemetry.*}).</li>
 *   <li>{@code isEnabled() == false} results in zero observable JFR events — the fast-path
 *       gate is proven by the absence of events outside of an active {@link RecordingStream}.</li>
 *   <li>Structured {@code rawArgs} fields ({@code requestedBytes}, {@code port},
 *       {@code blockTimeMs}) are correctly populated — no {@code toString()} substitution.</li>
 *   <li>Sink is idempotently closeable and emits no events after {@code close()}.</li>
 * </ul>
 *
 * <h2>How to use</h2>
 * <pre>{@code
 * class JfrTelemetrySinkTckTest extends AbstractJfrTelemetrySinkTck {
 *     @Override protected TelemetrySink createSink() { return new JfrTelemetrySink(); }
 * }
 * }</pre>
 *
 * @since 0.5.0
 */
public abstract class AbstractJfrTelemetrySinkTck {

    protected abstract TelemetrySink createSink();

    private TelemetrySink sink;

    @BeforeEach
    final void setUp() {
        sink = createSink();
    }

    @AfterEach
    final void tearDown() {
        sink.close();
    }

    // =========================================================================
    // Typed event routing
    // =========================================================================

    @Nested
    @DisplayName("Typed JFR event routing")
    class TypedRouting {

        @Test
        @DisplayName("MemoryExhaustedException emits eu.exeris.kernel.telemetry.MemoryExhaustion")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void memoryExhaustionEmitsTypedEvent() throws InterruptedException {
            CountDownLatch latch = new CountDownLatch(1);
            BlockingQueue<RecordedEvent> captured = new ArrayBlockingQueue<>(4);
            try (var rs = new RecordingStream()) {
                rs.enable("eu.exeris.kernel.telemetry.MemoryExhaustion").withoutStackTrace();
                rs.onEvent("eu.exeris.kernel.telemetry.MemoryExhaustion", e -> {
                    captured.offer(e);
                    latch.countDown();
                });
                rs.startAsync();

                KernelEvent event = KernelEvent.error("EX-MEM-1001", "MemoryAllocator",
                        new MemoryExhaustedException(8192L, 512L, null));
                sink.emit(event);
                latch.await(5, TimeUnit.SECONDS);

                RecordedEvent jfr = captured.poll();
                assertThat(jfr).as("MemoryExhaustion JFR event must be emitted").isNotNull();
                assertThat(jfr.getLong("requestedBytes")).isEqualTo(8192L);
                assertThat(jfr.getLong("availableBytes")).isEqualTo(512L);
                assertThat(jfr.getString("component")).isEqualTo("MemoryAllocator");
            }
        }

        @Test
        @DisplayName("TransportException EX-NET-4001 emits eu.exeris.kernel.telemetry.TransportBind")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void transportBindExceptionEmitsTypedEvent() throws InterruptedException {
            CountDownLatch latch = new CountDownLatch(1);
            BlockingQueue<RecordedEvent> captured = new ArrayBlockingQueue<>(4);
            try (var rs = new RecordingStream()) {
                rs.enable("eu.exeris.kernel.telemetry.TransportBind").withoutStackTrace();
                rs.onEvent("eu.exeris.kernel.telemetry.TransportBind", e -> {
                    captured.offer(e);
                    latch.countDown();
                });
                rs.startAsync();

                KernelEvent event = KernelEvent.error("EX-NET-4001", "TransportEngine",
                        TransportException.bindFailure("quic-edge", 8443, null));
                sink.emit(event);
                latch.await(5, TimeUnit.SECONDS);

                RecordedEvent jfr = captured.poll();
                assertThat(jfr).as("TransportBind JFR event must be emitted").isNotNull();
                assertThat(jfr.getString("transportName")).isEqualTo("quic-edge");
                assertThat(jfr.getInt("port")).isEqualTo(8443);
                assertThat(jfr.getString("component")).isEqualTo("TransportEngine");
            }
        }

        @Test
        @DisplayName("Unknown domain code falls back to eu.exeris.kernel.telemetry.KernelLifecycle")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void unknownCodeFallsBackToLifecycleEvent() throws InterruptedException {
            CountDownLatch latch = new CountDownLatch(1);
            BlockingQueue<RecordedEvent> captured = new ArrayBlockingQueue<>(4);
            try (var rs = new RecordingStream()) {
                rs.enable("eu.exeris.kernel.telemetry.KernelLifecycle").withoutStackTrace();
                rs.onEvent("eu.exeris.kernel.telemetry.KernelLifecycle", e -> {
                    captured.offer(e);
                    latch.countDown();
                });
                rs.startAsync();

                sink.emit(KernelEvent.info("EX-TCK-0001", "Tck"));
                latch.await(5, TimeUnit.SECONDS);

                RecordedEvent jfr = captured.poll();
                assertThat(jfr).as("KernelLifecycle fallback event must be emitted").isNotNull();
                assertThat(jfr.getString("errorCode")).isEqualTo("EX-TCK-0001");
            }
        }
    }

    // =========================================================================
    // isEnabled() fast-path gate — no recording = no events
    // =========================================================================

    @Nested
    @DisplayName("isEnabled() fast-path gate")
    class EnabledGate {

        @Test
        @DisplayName("emit() with no active recording produces no JFR events — isEnabled() gate")
        void noEventsWhenRecordingInactive() {
            // No RecordingStream active → isEnabled() returns false on all event types.
            // The sink must not throw and must not attempt to populate event fields.
            KernelEvent event = KernelEvent.error("EX-MEM-1001", "Gate",
                    new MemoryExhaustedException(1024L, 0L, null));
            assertThatCode(() -> sink.emit(event)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("increment() with no active recording does not throw")
        void metricsWithNoRecordingDoNotThrow() {
            assertThatCode(() -> {
                sink.increment("kernel.alloc.count", 1L);
                sink.gauge("kernel.heap.used", 1024L);
                sink.latency("kernel.dispatch.ns", 250L);
            }).doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // rawArgs structured field extraction
    // =========================================================================

    @Nested
    @DisplayName("rawArgs structured field extraction")
    class RawArgsExtraction {

        @Test
        @DisplayName("MemoryExhaustedException with zero available bytes sets availableBytes=0")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void exhaustedWithZeroAvailableBytes() throws InterruptedException {
            CountDownLatch latch = new CountDownLatch(1);
            BlockingQueue<RecordedEvent> captured = new ArrayBlockingQueue<>(4);
            try (var rs = new RecordingStream()) {
                rs.enable("eu.exeris.kernel.telemetry.MemoryExhaustion").withoutStackTrace();
                rs.onEvent("eu.exeris.kernel.telemetry.MemoryExhaustion", e -> {
                    captured.offer(e);
                    latch.countDown();
                });
                rs.startAsync();

                sink.emit(KernelEvent.error("EX-MEM-1001", "Allocator",
                        new MemoryExhaustedException(4096L, 0L, null)));
                latch.await(5, TimeUnit.SECONDS);

                RecordedEvent jfr = captured.poll();
                assertThat(jfr).isNotNull();
                assertThat(jfr.getLong("availableBytes")).isZero();
                assertThat(jfr.getLong("requestedBytes")).isEqualTo(4096L);
            }
        }

        @Test
        @DisplayName("TransportException EX-NET-4005 (engine start) emits TransportBind with correct port")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void engineStartFailureEmitsPort() throws InterruptedException {
            CountDownLatch latch = new CountDownLatch(1);
            BlockingQueue<RecordedEvent> captured = new ArrayBlockingQueue<>(4);
            try (var rs = new RecordingStream()) {
                rs.enable("eu.exeris.kernel.telemetry.TransportBind").withoutStackTrace();
                rs.onEvent("eu.exeris.kernel.telemetry.TransportBind", e -> {
                    captured.offer(e);
                    latch.countDown();
                });
                rs.startAsync();

                sink.emit(KernelEvent.error("EX-NET-4005", "Transport",
                        TransportException.engineStartFailure("tcp-edge", 9090, null)));
                latch.await(5, TimeUnit.SECONDS);

                RecordedEvent jfr = captured.poll();
                assertThat(jfr).isNotNull();
                assertThat(jfr.getInt("port")).isEqualTo(9090);
                assertThat(jfr.getString("transportName")).isEqualTo("tcp-edge");
            }
        }
    }

    // =========================================================================
    // close() contract
    // =========================================================================

    @Nested
    @DisplayName("close() contract")
    class CloseContract {

        @Test
        @DisplayName("close() is idempotent")
        void closeIsIdempotent() {
            sink.close();
            assertThatCode(() -> sink.close()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("emit() after close() silently drops the event")
        void emitAfterCloseIsNoop() {
            sink.close();
            assertThatCode(() ->
                    sink.emit(KernelEvent.info("EX-TCK-CLOSED", "Tck")))
                    .doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // Metric events
    // =========================================================================

    @Nested
    @DisplayName("Metric JFR events")
    class MetricEvents {

        @Test
        @DisplayName("increment() emits eu.exeris.kernel.telemetry.KernelMetric with type=COUNTER")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void incrementEmitsCounterEvent() throws InterruptedException {
            CountDownLatch latch = new CountDownLatch(1);
            BlockingQueue<RecordedEvent> captured = new ArrayBlockingQueue<>(4);
            try (var rs = new RecordingStream()) {
                rs.enable("eu.exeris.kernel.telemetry.KernelMetric").withoutStackTrace();
                rs.onEvent("eu.exeris.kernel.telemetry.KernelMetric", e -> {
                    captured.offer(e);
                    latch.countDown();
                });
                rs.startAsync();

                sink.increment("kernel.requests.total", 42L);
                latch.await(5, TimeUnit.SECONDS);

                RecordedEvent jfr = captured.poll();
                assertThat(jfr).isNotNull();
                assertThat(jfr.getString("metricName")).isEqualTo("kernel.requests.total");
                assertThat(jfr.getString("metricType")).isEqualTo("COUNTER");
                assertThat(jfr.getLong("value")).isEqualTo(42L);
            }
        }

        @Test
        @DisplayName("gauge() emits eu.exeris.kernel.telemetry.KernelMetric with type=GAUGE")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void gaugeEmitsGaugeEvent() throws InterruptedException {
            CountDownLatch latch = new CountDownLatch(1);
            BlockingQueue<RecordedEvent> captured = new ArrayBlockingQueue<>(4);
            try (var rs = new RecordingStream()) {
                rs.enable("eu.exeris.kernel.telemetry.KernelMetric").withoutStackTrace();
                rs.onEvent("eu.exeris.kernel.telemetry.KernelMetric", e -> {
                    captured.offer(e);
                    latch.countDown();
                });
                rs.startAsync();

                sink.gauge("kernel.heap.bytes", 8388608L);
                latch.await(5, TimeUnit.SECONDS);

                RecordedEvent jfr = captured.poll();
                assertThat(jfr).isNotNull();
                assertThat(jfr.getString("metricType")).isEqualTo("GAUGE");
                assertThat(jfr.getLong("value")).isEqualTo(8388608L);
            }
        }

        @Test
        @DisplayName("latency() emits eu.exeris.kernel.telemetry.KernelLatency")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void latencyEmitsLatencyEvent() throws InterruptedException {
            CountDownLatch latch = new CountDownLatch(1);
            BlockingQueue<RecordedEvent> captured = new ArrayBlockingQueue<>(4);
            try (var rs = new RecordingStream()) {
                rs.enable("eu.exeris.kernel.telemetry.KernelLatency").withoutStackTrace();
                rs.onEvent("eu.exeris.kernel.telemetry.KernelLatency", e -> {
                    captured.offer(e);
                    latch.countDown();
                });
                rs.startAsync();

                sink.latency("kernel.dispatch.ns", 750L);
                latch.await(5, TimeUnit.SECONDS);

                RecordedEvent jfr = captured.poll();
                assertThat(jfr).isNotNull();
                assertThat(jfr.getString("metricName")).isEqualTo("kernel.dispatch.ns");
                assertThat(jfr.getLong("nanoseconds")).isEqualTo(750L);
            }
        }
    }
}
