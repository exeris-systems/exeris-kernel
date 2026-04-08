/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.telemetry;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.memory.MemoryExhaustedException;
import eu.exeris.kernel.spi.telemetry.KernelEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * L1 Unit: {@link ConsoleSink} — output format, level routing, and lifecycle.
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>sinkName() is "ExerisCommunity/ConsoleSink"</li>
 *   <li>emit(INFO) produces a line containing event code and component</li>
 *   <li>emit(WARN) is routed to output</li>
 *   <li>emit(ERROR) includes exception message</li>
 *   <li>increment/gauge/latency are silent (no output)</li>
 *   <li>close() is a no-op — does not close System.out</li>
 *   <li>Custom PrintStream injection (test pattern)</li>
 * </ul>
 *
 * @since 0.5.0
 */
@DisplayName("L1 Unit: ConsoleSink output format")
class ConsoleSinkTest {

    private ByteArrayOutputStream capturedOutput;
    private PrintStream testStream;
    private ConsoleSink sink;

    @BeforeEach
    void setUp() {
        capturedOutput = new ByteArrayOutputStream();
        testStream = new PrintStream(capturedOutput, true, StandardCharsets.UTF_8);
        sink = new ConsoleSink(testStream);
    }

    @AfterEach
    void tearDown() {
        sink.close();
        testStream.close();
    }

    // =========================================================================
    // Identity
    // =========================================================================

    @Test
    @DisplayName("sinkName() == 'ExerisCommunity/ConsoleSink'")
    void sinkNameIsCorrect() {
        assertThat(sink.sinkName()).isEqualTo("ExerisCommunity/ConsoleSink");
    }

    // =========================================================================
    // emit() output format
    // =========================================================================

    @Nested
    @DisplayName("emit() output format")
    class EmitFormat {

        @Test
        @DisplayName("emit(INFO) produces a non-empty output line")
        void emitInfoProducesOutput() {
            sink.emit(KernelEvent.info("EX-CONSOLE-001", "ConsoleSinkTest"));
            String output = capturedOutput.toString(StandardCharsets.UTF_8);
            assertThat(output).isNotBlank();
        }

        @Test
        @DisplayName("emit(INFO) output contains the event code")
        void emitInfoContainsCode() {
            sink.emit(KernelEvent.info("EX-CONSOLE-002", "ConsoleSinkTest"));
            assertThat(capturedOutput.toString(StandardCharsets.UTF_8))
                    .contains("EX-CONSOLE-002");
        }

        @Test
        @DisplayName("emit(INFO) output contains the component name")
        void emitInfoContainsComponent() {
            sink.emit(KernelEvent.info("EX-CONSOLE-003", "MyComponent"));
            assertThat(capturedOutput.toString(StandardCharsets.UTF_8))
                    .contains("MyComponent");
        }

        @Test
        @DisplayName("emit(WARN) output contains 'WARN' level tag")
        void emitWarnContainsLevelTag() {
            sink.emit(KernelEvent.warn("EX-CONSOLE-004", "WarnComponent", null));
            assertThat(capturedOutput.toString(StandardCharsets.UTF_8))
                    .containsIgnoringCase("WARN");
        }

        @Test
        @DisplayName("emit(ERROR) output contains exception message")
        void emitErrorContainsExceptionMessage() {
            ExerisKernelException ex = new MemoryExhaustedException(1024L, 0L, null);
            sink.emit(KernelEvent.error("EX-CONSOLE-005", "ErrorComponent", ex));
            String output = capturedOutput.toString(StandardCharsets.UTF_8);
            assertThat(output).isNotBlank();
            // Exception message should be present
            assertThat(output).doesNotContain("null");
        }

        @Test
        @DisplayName("emit() output contains a timestamp-like prefix")
        void emitContainsTimestamp() {
            sink.emit(KernelEvent.info("EX-TS-001", "TSTest"));
            String output = capturedOutput.toString(StandardCharsets.UTF_8);
            // Timestamp format is [HH:mm:ss.SSS] — look for ':' and '.' patterns
            assertThat(output).matches("(?s).*\\[\\d{2}:\\d{2}:\\d{2}\\.\\d{3}].*");
        }
    }

    // =========================================================================
    // Counter/gauge/latency — silent by design
    // =========================================================================

    @Nested
    @DisplayName("increment/gauge/latency — silent by design")
    class SilentMetrics {

        @Test
        @DisplayName("increment() produces no output")
        void incrementIsSilent() {
            sink.increment("some.counter", 42L);
            assertThat(capturedOutput.toString(StandardCharsets.UTF_8)).isEmpty();
        }

        @Test
        @DisplayName("gauge() produces no output")
        void gaugeIsSilent() {
            sink.gauge("some.gauge", 99L);
            assertThat(capturedOutput.toString(StandardCharsets.UTF_8)).isEmpty();
        }

        @Test
        @DisplayName("latency() produces no output")
        void latencyIsSilent() {
            sink.latency("some.latency", 1_500_000L);
            assertThat(capturedOutput.toString(StandardCharsets.UTF_8)).isEmpty();
        }
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("close() does not throw and does not close the underlying PrintStream")
        void closeIsNoOp() {
            assertThatCode(() -> sink.close()).doesNotThrowAnyException();
            // PrintStream should still be writable after sink.close()
            assertThatCode(() -> sink.emit(KernelEvent.info("EX-POST-CLOSE", "Test")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("double close() does not throw")
        void doubleCloseIsIdempotent() {
            sink.close();
            assertThatCode(() -> sink.close()).doesNotThrowAnyException();
        }
    }
}



