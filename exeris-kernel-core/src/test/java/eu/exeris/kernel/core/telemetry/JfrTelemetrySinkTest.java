/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.telemetry;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.memory.MemoryExhaustedException;
import eu.exeris.kernel.spi.exceptions.transport.TransportException;
import eu.exeris.kernel.spi.telemetry.EventLevel;
import eu.exeris.kernel.spi.telemetry.KernelEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit: {@link JfrTelemetrySink} internal logic and edge cases.
 *
 * <p>These tests verify contract-level behaviour (no throw, no NPE, close idempotency)
 * without requiring an active JFR recording — the {@code isEnabled()} gate is active
 * and all emitters silently no-op.
 *
 * @since 0.5.0
 */
@DisplayName("JfrTelemetrySink — unit")
class JfrTelemetrySinkTest {

    private JfrTelemetrySink sink;

    @BeforeEach
    void setUp() {
        sink = new JfrTelemetrySink();
    }

    @AfterEach
    void tearDown() {
        sink.close();
    }

    // =========================================================================
    // emit() — no-recording path (isEnabled() gate)
    // =========================================================================

    @Nested
    @DisplayName("emit() — isEnabled() gate active, no recording")
    class EmitGate {

        @Test
        @DisplayName("INFO event without exception does not throw")
        void emitInfoNoException() {
            assertThatCode(() ->
                    sink.emit(KernelEvent.info("EX-TEST-001", "CoreUnit")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("ERROR event with MemoryExhaustedException does not throw")
        void emitErrorMemoryExhausted() {
            assertThatCode(() ->
                    sink.emit(KernelEvent.error(KernelErrorCodes.EX_MEM_1001, "MemUnit",
                            new MemoryExhaustedException(1024L, 0L, null))))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("ERROR event with TransportException EX-NET-4001 does not throw")
        void emitErrorTransportBind() {
            assertThatCode(() ->
                    sink.emit(KernelEvent.error(KernelErrorCodes.EX_NET_4001, "Transport",
                            TransportException.bindFailure("tcp", 8080, null))))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("ERROR event with TransportException EX-NET-4005 does not throw")
        void emitErrorTransportEngineStart() {
            assertThatCode(() ->
                    sink.emit(KernelEvent.error(KernelErrorCodes.EX_NET_4005, "Transport",
                            TransportException.engineStartFailure("quic", 443, null))))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("ERROR event with unknown domain code falls back without throw")
        void emitErrorUnknownDomain() {
            assertThatCode(() ->
                    sink.emit(KernelEvent.error("EX-XYZ-9999", "Unknown",
                            new MemoryExhaustedException(1L, 0L, null))))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("emit() with null exception field (INFO variant) does not throw")
        void emitInfoNullException() {
            assertThatCode(() ->
                    sink.emit(KernelEvent.info("EX-MEM-1001", "InfoPath")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("emit() with short error code (< 6 chars) does not throw")
        void emitShortCode() {
            assertThatCode(() ->
                    sink.emit(KernelEvent.error("EX-X", "Short",
                            new MemoryExhaustedException(1L, 0L, null))))
                    .doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // Metrics
    // =========================================================================

    @Nested
    @DisplayName("Metric methods — isEnabled() gate active")
    class MetricGate {

        @Test
        @DisplayName("increment() does not throw")
        void incrementDoesNotThrow() {
            assertThatCode(() -> sink.increment("kernel.count", 1L)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("gauge() does not throw")
        void gaugeDoesNotThrow() {
            assertThatCode(() -> sink.gauge("kernel.heap", 4096L)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("latency() does not throw")
        void latencyDoesNotThrow() {
            assertThatCode(() -> sink.latency("kernel.latency.ns", 300L)).doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // close() contract
    // =========================================================================

    @Nested
    @DisplayName("close() contract")
    class CloseContract {

        @Test
        @DisplayName("close() is idempotent — calling twice does not throw")
        void closeIdempotent() {
            sink.close();
            assertThatCode(() -> sink.close()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("emit() after close() silently drops — does not throw")
        void emitAfterClose() {
            sink.close();
            assertThatCode(() ->
                    sink.emit(KernelEvent.info("EX-TEST-CLOSED", "AfterClose")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("increment() after close() silently drops — does not throw")
        void incrementAfterClose() {
            sink.close();
            assertThatCode(() -> sink.increment("x", 1L)).doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // sinkName()
    // =========================================================================

    @Test
    @DisplayName("sinkName() returns non-blank canonical name")
    void sinkNameNonBlank() {
        String name = sink.sinkName();
        assertThat(name).isNotBlank();
    }

    // =========================================================================
    // Defensive routing — null/empty code guard
    // =========================================================================

    @Nested
    @DisplayName("Defensive routing")
    class DefensiveRouting {

        @Test
        @DisplayName("emit() with null code and non-null exception does not throw NPE")
        void nullCodeWithException() {
            KernelEvent event = new KernelEvent(
                    null, EventLevel.ERROR, java.time.Instant.now(),
                    new MemoryExhaustedException(1024L, 0L), "DefensiveTest");
            assertThatCode(() -> sink.emit(event)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("emit() with empty code and non-null exception does not throw NPE")
        void emptyCodeWithException() {
            KernelEvent event = new KernelEvent(
                    "", EventLevel.ERROR, java.time.Instant.now(),
                    new MemoryExhaustedException(1024L, 0L), "DefensiveTest");
            assertThatCode(() -> sink.emit(event)).doesNotThrowAnyException();
        }
    }
}
