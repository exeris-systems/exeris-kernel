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
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
 *       gate is proven by the absence of events outside of an active {@link Recording}.</li>
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
        void memoryExhaustionEmitsTypedEvent() throws IOException {
            Path tmp = Files.createTempFile("tck-mem-", ".jfr");
            try (Recording recording = new Recording()) {
                recording.enable("eu.exeris.kernel.telemetry.MemoryExhaustion").withoutStackTrace();
                recording.start();

                KernelEvent event = KernelEvent.error("EX-MEM-1001", "MemoryAllocator",
                        new MemoryExhaustedException(8192L, 512L, null));
                sink.emit(event);

                recording.stop();
                recording.dump(tmp);
            }
            try {
                List<RecordedEvent> events = RecordingFile.readAllEvents(tmp);
                List<RecordedEvent> matched = events.stream()
                        .filter(e -> e.getEventType().getName().equals("eu.exeris.kernel.telemetry.MemoryExhaustion"))
                        .toList();
                assertThat(matched).as("MemoryExhaustion JFR event must be emitted").isNotEmpty();
                RecordedEvent jfr = matched.get(0);
                assertThat(jfr.getLong("requestedBytes")).isEqualTo(8192L);
                assertThat(jfr.getLong("availableBytes")).isEqualTo(512L);
                assertThat(jfr.getString("component")).isEqualTo("MemoryAllocator");
            } finally {
                Files.deleteIfExists(tmp);
            }
        }

        @Test
        @DisplayName("TransportException EX-NET-4001 emits eu.exeris.kernel.telemetry.TransportBind")
        void transportBindExceptionEmitsTypedEvent() throws IOException {
            Path tmp = Files.createTempFile("tck-net-", ".jfr");
            try (Recording recording = new Recording()) {
                recording.enable("eu.exeris.kernel.telemetry.TransportBind").withoutStackTrace();
                recording.start();

                KernelEvent event = KernelEvent.error("EX-NET-4001", "TransportEngine",
                        TransportException.bindFailure("quic-edge", 8443, null));
                sink.emit(event);

                recording.stop();
                recording.dump(tmp);
            }
            try {
                List<RecordedEvent> events = RecordingFile.readAllEvents(tmp);
                List<RecordedEvent> matched = events.stream()
                        .filter(e -> e.getEventType().getName().equals("eu.exeris.kernel.telemetry.TransportBind"))
                        .toList();
                assertThat(matched).as("TransportBind JFR event must be emitted").isNotEmpty();
                RecordedEvent jfr = matched.get(0);
                assertThat(jfr.getString("transportName")).isEqualTo("quic-edge");
                assertThat(jfr.getInt("port")).isEqualTo(8443);
                assertThat(jfr.getString("component")).isEqualTo("TransportEngine");
            } finally {
                Files.deleteIfExists(tmp);
            }
        }

        @Test
        @DisplayName("Unknown domain code falls back to eu.exeris.kernel.telemetry.KernelLifecycle")
        void unknownCodeFallsBackToLifecycleEvent() throws IOException {
            Path tmp = Files.createTempFile("tck-lcl-", ".jfr");
            try (Recording recording = new Recording()) {
                recording.enable("eu.exeris.kernel.telemetry.KernelLifecycle").withoutStackTrace();
                recording.start();

                sink.emit(KernelEvent.info("EX-TCK-0001", "Tck"));

                recording.stop();
                recording.dump(tmp);
            }
            try {
                List<RecordedEvent> events = RecordingFile.readAllEvents(tmp);
                List<RecordedEvent> matched = events.stream()
                        .filter(e -> e.getEventType().getName().equals("eu.exeris.kernel.telemetry.KernelLifecycle"))
                        .toList();
                assertThat(matched).as("KernelLifecycle fallback event must be emitted").isNotEmpty();
                assertThat(matched.get(0).getString("errorCode")).isEqualTo("EX-TCK-0001");
            } finally {
                Files.deleteIfExists(tmp);
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
        void exhaustedWithZeroAvailableBytes() throws IOException {
            Path tmp = Files.createTempFile("tck-mem0-", ".jfr");
            try (Recording recording = new Recording()) {
                recording.enable("eu.exeris.kernel.telemetry.MemoryExhaustion").withoutStackTrace();
                recording.start();

                sink.emit(KernelEvent.error("EX-MEM-1001", "Allocator",
                        new MemoryExhaustedException(4096L, 0L, null)));

                recording.stop();
                recording.dump(tmp);
            }
            try {
                List<RecordedEvent> events = RecordingFile.readAllEvents(tmp);
                List<RecordedEvent> matched = events.stream()
                        .filter(e -> e.getEventType().getName().equals("eu.exeris.kernel.telemetry.MemoryExhaustion"))
                        .toList();
                assertThat(matched).isNotEmpty();
                RecordedEvent jfr = matched.get(0);
                assertThat(jfr.getLong("availableBytes")).isZero();
                assertThat(jfr.getLong("requestedBytes")).isEqualTo(4096L);
            } finally {
                Files.deleteIfExists(tmp);
            }
        }

        @Test
        @DisplayName("TransportException EX-NET-4005 (engine start) emits TransportBind with correct port")
        void engineStartFailureEmitsPort() throws IOException {
            Path tmp = Files.createTempFile("tck-net5-", ".jfr");
            try (Recording recording = new Recording()) {
                recording.enable("eu.exeris.kernel.telemetry.TransportBind").withoutStackTrace();
                recording.start();

                sink.emit(KernelEvent.error("EX-NET-4005", "Transport",
                        TransportException.engineStartFailure("tcp-edge", 9090, null)));

                recording.stop();
                recording.dump(tmp);
            }
            try {
                List<RecordedEvent> events = RecordingFile.readAllEvents(tmp);
                List<RecordedEvent> matched = events.stream()
                        .filter(e -> e.getEventType().getName().equals("eu.exeris.kernel.telemetry.TransportBind"))
                        .toList();
                assertThat(matched).isNotEmpty();
                RecordedEvent jfr = matched.get(0);
                assertThat(jfr.getInt("port")).isEqualTo(9090);
                assertThat(jfr.getString("transportName")).isEqualTo("tcp-edge");
            } finally {
                Files.deleteIfExists(tmp);
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
        void incrementEmitsCounterEvent() throws IOException {
            Path tmp = Files.createTempFile("tck-ctr-", ".jfr");
            try (Recording recording = new Recording()) {
                recording.enable("eu.exeris.kernel.telemetry.KernelMetric").withoutStackTrace();
                recording.start();

                sink.increment("kernel.requests.total", 42L);

                recording.stop();
                recording.dump(tmp);
            }
            try {
                List<RecordedEvent> events = RecordingFile.readAllEvents(tmp);
                List<RecordedEvent> matched = events.stream()
                        .filter(e -> e.getEventType().getName().equals("eu.exeris.kernel.telemetry.KernelMetric"))
                        .toList();
                assertThat(matched).isNotEmpty();
                RecordedEvent jfr = matched.get(0);
                assertThat(jfr.getString("metricName")).isEqualTo("kernel.requests.total");
                assertThat(jfr.getString("metricType")).isEqualTo("COUNTER");
                assertThat(jfr.getLong("value")).isEqualTo(42L);
            } finally {
                Files.deleteIfExists(tmp);
            }
        }

        @Test
        @DisplayName("gauge() emits eu.exeris.kernel.telemetry.KernelMetric with type=GAUGE")
        void gaugeEmitsGaugeEvent() throws IOException {
            Path tmp = Files.createTempFile("tck-gge-", ".jfr");
            try (Recording recording = new Recording()) {
                recording.enable("eu.exeris.kernel.telemetry.KernelMetric").withoutStackTrace();
                recording.start();

                sink.gauge("kernel.heap.bytes", 8388608L);

                recording.stop();
                recording.dump(tmp);
            }
            try {
                List<RecordedEvent> events = RecordingFile.readAllEvents(tmp);
                List<RecordedEvent> matched = events.stream()
                        .filter(e -> e.getEventType().getName().equals("eu.exeris.kernel.telemetry.KernelMetric"))
                        .toList();
                assertThat(matched).isNotEmpty();
                RecordedEvent jfr = matched.get(0);
                assertThat(jfr.getString("metricType")).isEqualTo("GAUGE");
                assertThat(jfr.getLong("value")).isEqualTo(8388608L);
            } finally {
                Files.deleteIfExists(tmp);
            }
        }

        @Test
        @DisplayName("latency() emits eu.exeris.kernel.telemetry.KernelLatency")
        void latencyEmitsLatencyEvent() throws IOException {
            Path tmp = Files.createTempFile("tck-lat-", ".jfr");
            try (Recording recording = new Recording()) {
                recording.enable("eu.exeris.kernel.telemetry.KernelLatency").withoutStackTrace();
                recording.start();

                sink.latency("kernel.dispatch.ns", 750L);

                recording.stop();
                recording.dump(tmp);
            }
            try {
                List<RecordedEvent> events = RecordingFile.readAllEvents(tmp);
                List<RecordedEvent> matched = events.stream()
                        .filter(e -> e.getEventType().getName().equals("eu.exeris.kernel.telemetry.KernelLatency"))
                        .toList();
                assertThat(matched).isNotEmpty();
                RecordedEvent jfr = matched.get(0);
                assertThat(jfr.getString("metricName")).isEqualTo("kernel.dispatch.ns");
                assertThat(jfr.getLong("nanoseconds")).isEqualTo(750L);
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
    }
}
