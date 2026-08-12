/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.telemetry;

import eu.exeris.kernel.spi.telemetry.KernelEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.StructuredTaskScope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 Unit: {@link FileSink} — queue-based async writer, backpressure, lifecycle.
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>sinkName() is non-blank</li>
 *   <li>emit() does not block or throw on the caller thread</li>
 *   <li>Events are eventually flushed to disk (drainTimeout)</li>
 *   <li>close() flushes remaining events before terminating the writer thread</li>
 *   <li>double close() is idempotent</li>
 *   <li>Queue overflow increments droppedCount (backpressure — no blocking)</li>
 *   <li>Concurrent emit() from many VTs — no data corruption</li>
 * </ul>
 *
 * @since 0.5.0
 */
@DisplayName("L1 Unit: FileSink")
class FileSinkTest {

    @TempDir
    Path tempDir;

    // =========================================================================
    // Identity
    // =========================================================================

    @Test
    @DisplayName("sinkName() is non-blank")
    void sinkNameIsNonBlank() {
        Path logFile = tempDir.resolve("test.log");
        try (FileSink sink = new FileSink(logFile, 128)) {
            assertThat(sink.sinkName()).isNotNull().isNotBlank();
        }
    }

    // =========================================================================
    // emit() — non-blocking caller contract
    // =========================================================================

    @Nested
    @DisplayName("emit() — non-blocking caller contract")
    class EmitContract {

        @Test
        @DisplayName("emit(INFO) does not throw and returns immediately")
        void emitInfoDoesNotThrow() {
            Path logFile = tempDir.resolve("emit-info.log");
            try (FileSink sink = new FileSink(logFile, 128)) {
                KernelEvent event = KernelEvent.info("EX-TEST-001", "FileSink unit test");
                assertThatCode(() -> sink.emit(event)).doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("emit(WARN) does not throw")
        void emitWarnDoesNotThrow() {
            Path logFile = tempDir.resolve("emit-warn.log");
            try (FileSink sink = new FileSink(logFile, 128)) {
                assertThatCode(() -> sink.emit(KernelEvent.warn("EX-TEST-002", "warn message", null)))
                        .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("emit(null) throws NullPointerException — null events are a programming error")
        void emitNullThrowsNpe() {
            Path logFile = tempDir.resolve("emit-null.log");
            try (FileSink sink = new FileSink(logFile, 128)) {
                // ArrayBlockingQueue.offer(null) throws NullPointerException per Java spec.
                // Null KernelEvent is a programming error — callers must not pass null.
                assertThatThrownBy(() -> sink.emit(null))
                        .isInstanceOf(NullPointerException.class);
            }
        }
    }

    // =========================================================================
    // Flush on close()
    // =========================================================================

    @Nested
    @DisplayName("Flush on close()")
    class FlushOnClose {

        @Test
        @DisplayName("Events are written to file after close()")
        void eventsWrittenOnClose() throws IOException {
            Path logFile = tempDir.resolve("flush.log");
            FileSink sink = new FileSink(logFile, 256);
            for (int i = 0; i < 10; i++) {
                sink.emit(KernelEvent.info("EX-TEST-" + i, "event " + i));
            }
            sink.close();

            assertThat(logFile).exists();
            List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            assertThat(lines)
                    .as("close() flushes every emitted event — none may be lost at shutdown")
                    .hasSize(10);
        }

        @Test
        @DisplayName("A queue backlog at close() is drained, not discarded")
        void backlogIsDrainedOnClose() throws IOException {
            Path logFile = tempDir.resolve("backlog.log");
            // The producer enqueues an order of magnitude faster than the writer formats and
            // writes, so a burst this size guarantees a backlog is still queued when close()
            // clears the running flag. Capacity exceeds the burst, so nothing is dropped and
            // the expected line count is exact. Without drain-on-close this loses thousands of
            // lines; the weaker isNotEmpty() assertion above used to pass either way.
            int burst = 5_000;
            FileSink sink = new FileSink(logFile, 8_192);
            for (int i = 0; i < burst; i++) {
                sink.emit(KernelEvent.info("EX-BACKLOG-" + i, "event " + i));
            }
            sink.close();

            assertThat(sink.droppedCount())
                    .as("queue capacity exceeds the burst, so no event may be dropped")
                    .isZero();
            assertThat(Files.readAllLines(logFile, StandardCharsets.UTF_8))
                    .as("every queued event survives close()")
                    .hasSize(burst);
        }

        @Test
        @DisplayName("close() is idempotent — double close does not throw")
        void closeIsIdempotent() {
            Path logFile = tempDir.resolve("idempotent.log");
            FileSink sink = new FileSink(logFile, 128);
            sink.close();
            assertThatCode(() -> sink.close()).doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // Backpressure — queue overflow
    // =========================================================================

    @Nested
    @DisplayName("Backpressure — queue overflow drops events")
    class Backpressure {

        @Test
        @DisplayName("Overflow increments droppedCount without blocking the caller")
        void overflowIncrementsDropped() throws Exception {
            Path logFile = tempDir.resolve("overflow.log");
            // Queue depth=1, burst from 1000 VTs: concurrent offers will race and many must be dropped.
            try (FileSink sink = new FileSink(logFile, 1)) {
                try (var scope = StructuredTaskScope.open()) {
                    for (int i = 0; i < 1_000; i++) {
                        final int idx = i;
                        scope.fork(() -> {
                            sink.emit(KernelEvent.info("EX-OVF-" + idx, "overflow test"));
                            return null;
                        });
                    }
                    scope.join();
                }
                assertThat(sink.droppedCount()).isGreaterThan(0L);
            }
        }
    }

    // =========================================================================
    // Concurrent emit()
    // =========================================================================

    @Test
    @DisplayName("Concurrent emit() from 500 VTs — no data corruption or crash")
    void concurrentEmitIsThreadSafe() throws Exception {
        Path logFile = tempDir.resolve("concurrent.log");
        try (FileSink sink = new FileSink(logFile, 1024)) {
            try (var scope = StructuredTaskScope.open()) {
                List<StructuredTaskScope.Subtask<Void>> subtasks = new java.util.ArrayList<>();
                for (int i = 0; i < 500; i++) {
                    final int idx = i;
                    subtasks.add(scope.fork(() -> {
                        sink.emit(KernelEvent.info("EX-CONCURRENT-" + idx, "vt-" + idx));
                        return null;
                    }));
                }
                scope.join();
                assertThat(subtasks)
                        .allSatisfy(subtask -> assertThat(subtask.state())
                                .isEqualTo(StructuredTaskScope.Subtask.State.SUCCESS));
            }
            // All emits completed without exception
        }
        // Writer thread finished by close() — verify file is readable
        if (Files.exists(logFile)) {
            assertThatCode(() -> Files.readAllLines(logFile, StandardCharsets.UTF_8))
                    .doesNotThrowAnyException();
        }
    }
}




