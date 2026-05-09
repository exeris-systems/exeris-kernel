/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.telemetry;

import eu.exeris.kernel.spi.config.KernelProfile;
import eu.exeris.kernel.spi.exceptions.memory.MemoryExhaustedException;
import eu.exeris.kernel.spi.telemetry.KernelEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Profile-aware end-to-end check that {@link Slf4jTelemetrySink} honours
 * {@link eu.exeris.kernel.spi.exceptions.ExceptionDisclosure} when shaping the
 * structured JSON line and forwarding the throwable to the underlying logger.
 *
 * <p>The {@link AbstractDisclosureModeTck} subclass covers the SPI helper directly;
 * this test pins the contract at the sink boundary — the operator-visible artifact.
 */
@DisplayName("Slf4jTelemetrySink — ExceptionDisclosure integration")
class Slf4jTelemetrySinkDisclosureTest {

    private static final long REQUESTED = 4096L;
    private static final long AVAILABLE = 128L;

    @Test
    @DisplayName("DEV profile renders rawArgs and forwards the throwable to the logger")
    void devProfileEmitsFullPayload() {
        CapturingMdcAdapter mdcAdapter = new CapturingMdcAdapter();
        CapturingLogAdapter logAdapter = new CapturingLogAdapter();
        try (Slf4jTelemetrySink sink =
                     new Slf4jTelemetrySink(logAdapter, mdcAdapter, () -> KernelProfile.DEV)) {
            MemoryExhaustedException exception =
                    new MemoryExhaustedException(REQUESTED, AVAILABLE, null);
            sink.emit(KernelEvent.error("EX-MEM-1001", "MemoryUnit", exception));

            assertThat(logAdapter.lastMessage)
                    .as("DEV must surface the original exception message")
                    .contains(exception.getMessage())
                    .contains("\"rawArgs\":[" + REQUESTED + "," + AVAILABLE + "]");
            assertThat(logAdapter.lastThrowablePresent)
                    .as("DEV must forward the throwable so SLF4J can render the stack trace")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("PROD profile redacts message + rawArgs and suppresses the throwable")
    void prodProfileRedactsPayload() {
        CapturingMdcAdapter mdcAdapter = new CapturingMdcAdapter();
        CapturingLogAdapter logAdapter = new CapturingLogAdapter();
        try (Slf4jTelemetrySink sink =
                     new Slf4jTelemetrySink(logAdapter, mdcAdapter, () -> KernelProfile.PROD)) {
            MemoryExhaustedException exception =
                    new MemoryExhaustedException(REQUESTED, AVAILABLE, null);
            sink.emit(KernelEvent.error("EX-MEM-1001", "MemoryUnit", exception));

            assertThat(logAdapter.lastMessage)
                    .as("PROD must not surface the original exception message")
                    .doesNotContain(exception.getMessage())
                    .contains("EX-MEM-1001")
                    .contains(exception.traceId().toString())
                    .contains("\"rawArgs\":[]");
            assertThat(logAdapter.lastThrowablePresent)
                    .as("PROD must suppress the throwable so SLF4J does not log a stack trace")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("TEST profile mirrors PROD redaction (no full disclosure)")
    void testProfileRedactsPayload() {
        CapturingMdcAdapter mdcAdapter = new CapturingMdcAdapter();
        CapturingLogAdapter logAdapter = new CapturingLogAdapter();
        try (Slf4jTelemetrySink sink =
                     new Slf4jTelemetrySink(logAdapter, mdcAdapter, () -> KernelProfile.TEST)) {
            MemoryExhaustedException exception =
                    new MemoryExhaustedException(REQUESTED, AVAILABLE, null);
            sink.emit(KernelEvent.error("EX-MEM-1001", "MemoryUnit", exception));

            assertThat(logAdapter.lastMessage)
                    .doesNotContain(exception.getMessage())
                    .contains("\"rawArgs\":[]");
            assertThat(logAdapter.lastThrowablePresent).isFalse();
        }
    }

    private static final class CapturingLogAdapter implements Slf4jTelemetrySink.LogAdapter {

        private String lastMessage;
        private boolean lastThrowablePresent;

        @Override
        public void info(String message, Throwable throwable) {
            capture(message, throwable);
        }

        @Override
        public void warn(String message, Throwable throwable) {
            capture(message, throwable);
        }

        @Override
        public void error(String message, Throwable throwable) {
            capture(message, throwable);
        }

        private void capture(String message, Throwable throwable) {
            this.lastMessage = message;
            this.lastThrowablePresent = throwable != null;
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
    }
}
