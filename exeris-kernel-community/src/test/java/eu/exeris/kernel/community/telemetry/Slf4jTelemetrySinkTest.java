/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.telemetry;

import eu.exeris.kernel.spi.exceptions.memory.MemoryExhaustedException;
import eu.exeris.kernel.spi.telemetry.KernelEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * L1 Unit: {@link Slf4jTelemetrySink} structured logging and EX-* level mapping.
 *
 * @since 0.5.0
 */
@DisplayName("L1 Unit: Slf4jTelemetrySink")
class Slf4jTelemetrySinkTest {

    @Test
    @DisplayName("sinkName() is canonical")
    void sinkNameIsCanonical() {
        try (Slf4jTelemetrySink sink = new Slf4jTelemetrySink()) {
            assertThat(sink.sinkName()).isEqualTo("ExerisCommunity/Slf4jTelemetrySink");
        }
    }

    @Nested
    @DisplayName("EX-* mapping")
    class ExMapping {

        @Test
        @DisplayName("EX-MEM-* maps to ERROR and emits rawArgs JSON")
        void memoryCodeMapsToError() {
            CapturingMdcAdapter mdcAdapter = new CapturingMdcAdapter();
            CapturingLogAdapter logAdapter = new CapturingLogAdapter(mdcAdapter);
            try (Slf4jTelemetrySink sink = new Slf4jTelemetrySink(logAdapter, mdcAdapter)) {
            KernelEvent event = KernelEvent.error("EX-MEM-1001", "MemoryUnit",
                new MemoryExhaustedException(4096L, 128L, null));

            sink.emit(event);

            assertThat(logAdapter.lastLevel).isEqualTo("ERROR");
            assertThat(logAdapter.lastMessage)
                .contains("\"code\":\"EX-MEM-1001\"")
                .contains("\"component\":\"MemoryUnit\"")
                .contains("\"rawArgs\":[4096,128]");
            assertThat(logAdapter.lastMdcSnapshot)
                .containsEntry("ex.code", "EX-MEM-1001")
                .containsEntry("ex.level", "ERROR")
                .containsEntry("ex.component", "MemoryUnit");
                assertThat(logAdapter.lastThrowablePresent).isTrue();
            }
        }

        @Test
        @DisplayName("EX-RUN-* elevates INFO event to WARN")
        void runCodeElevatesInfoToWarn() {
            CapturingMdcAdapter mdcAdapter = new CapturingMdcAdapter();
            CapturingLogAdapter logAdapter = new CapturingLogAdapter(mdcAdapter);
            try (Slf4jTelemetrySink sink = new Slf4jTelemetrySink(logAdapter, mdcAdapter)) {
                KernelEvent event = KernelEvent.info("EX-RUN-3002", "RuntimeUnit");
                sink.emit(event);

                assertThat(logAdapter.lastLevel).isEqualTo("WARN");
                assertThat(logAdapter.lastMessage).contains("\"code\":\"EX-RUN-3002\"");
            }
        }

        @Test
        @DisplayName("EX-PERS-* elevates INFO event to ERROR")
        void persistenceCodeElevatesInfoToError() {
            CapturingMdcAdapter mdcAdapter = new CapturingMdcAdapter();
            CapturingLogAdapter logAdapter = new CapturingLogAdapter(mdcAdapter);
            try (Slf4jTelemetrySink sink = new Slf4jTelemetrySink(logAdapter, mdcAdapter)) {
                KernelEvent event = KernelEvent.info("EX-PERS-2001", "PersistenceUnit");
                sink.emit(event);

                assertThat(logAdapter.lastLevel).isEqualTo("ERROR");
                assertThat(logAdapter.lastMessage).contains("\"code\":\"EX-PERS-2001\"");
            }
        }

        @Test
        @DisplayName("unknown code keeps INFO level")
        void unknownCodeKeepsInfoLevel() {
            CapturingMdcAdapter mdcAdapter = new CapturingMdcAdapter();
            CapturingLogAdapter logAdapter = new CapturingLogAdapter(mdcAdapter);
            try (Slf4jTelemetrySink sink = new Slf4jTelemetrySink(logAdapter, mdcAdapter)) {
                sink.emit(KernelEvent.info("EX-UNK-9999", "UnknownUnit"));

                assertThat(logAdapter.lastLevel).isEqualTo("INFO");
            }
        }
    }

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("close() is idempotent and emit() after close is noop")
        void closeIsIdempotent() {
            CapturingMdcAdapter mdcAdapter = new CapturingMdcAdapter();
            CapturingLogAdapter logAdapter = new CapturingLogAdapter(mdcAdapter);
            try (Slf4jTelemetrySink sink = new Slf4jTelemetrySink(logAdapter, mdcAdapter)) {
                sink.close();
                assertThatCode(sink::close).doesNotThrowAnyException();
                sink.emit(KernelEvent.info("EX-CLOSE-001", "SinkTest"));

                assertThat(logAdapter.invocations).isZero();
            }
        }
    }

    private static final class CapturingLogAdapter implements Slf4jTelemetrySink.LogAdapter {

        private final CapturingMdcAdapter mdcAdapter;

        private String lastLevel;
        private String lastMessage;
        private Map<String, String> lastMdcSnapshot;
        private boolean lastThrowablePresent;
        private int invocations;

        private CapturingLogAdapter(CapturingMdcAdapter mdcAdapter) {
            this.mdcAdapter = mdcAdapter;
            this.lastMdcSnapshot = Map.of();
        }

        @Override
        public void info(String message, Throwable throwable) {
            capture("INFO", message, throwable);
        }

        @Override
        public void warn(String message, Throwable throwable) {
            capture("WARN", message, throwable);
        }

        @Override
        public void error(String message, Throwable throwable) {
            capture("ERROR", message, throwable);
        }

        private void capture(String level, String message, Throwable throwable) {
            this.lastLevel = level;
            this.lastMessage = message;
            this.lastMdcSnapshot = mdcAdapter.snapshot();
            this.lastThrowablePresent = throwable != null;
            this.invocations++;
        }
    }

    private static final class CapturingMdcAdapter implements Slf4jTelemetrySink.MdcAdapter {

        private final Map<String, String> entries = new LinkedHashMap<>();

        @Override
        public Slf4jTelemetrySink.MdcScope put(String key, String value) {
            String previous = entries.put(key, value);
            return () -> {
                if (previous == null) {
                    entries.remove(key);
                } else {
                    entries.put(key, previous);
                }
            };
        }

        private Map<String, String> snapshot() {
            return Map.copyOf(entries);
        }
    }
}