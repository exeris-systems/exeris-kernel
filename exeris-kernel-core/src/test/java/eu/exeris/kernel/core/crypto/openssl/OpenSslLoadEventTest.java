/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.crypto.openssl;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * L0: {@link OpenSslLoadEvent} — proves {@link CoreOpenSslLoader#load} emits exactly one
 * {@code eu.exeris.kernel.core.crypto.OpenSslLoad} event reporting the resolved version and
 * {@code SSL_CTX_new_ex} API, when OpenSSL 3.x/4.x is present.
 *
 * <p>Skipped via {@link org.junit.jupiter.api.Assumptions} when the host has no usable
 * OpenSSL (the load itself fails the version gate / library resolution).
 */
@DisplayName("L0: OpenSslLoadEvent (JFR bootstrap event)")
class OpenSslLoadEventTest {

    private static final String EVENT_NAME = "eu.exeris.kernel.core.crypto.OpenSslLoad";

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("load() emits one OpenSslLoad event with major>=3, non-blank text, SSL_CTX_new_ex")
    void loadEmitsOpenSslLoadEvent() throws Exception {
        try (Recording recording = new Recording()) {
            recording.enable(EVENT_NAME);
            recording.start();

            boolean loaded;
            // CHECKSTYLE:OFF DirectArena — bootstrap loads symbols for the JVM lifetime
            try {
                CoreOpenSslLoader.load(Arena.global());
                loaded = true;
            } catch (Throwable t) { //NOPMD AvoidCatchingThrowable — FFM load may throw UnsatisfiedLinkError
                loaded = false;
            }
            // CHECKSTYLE:ON
            assumeTrue(loaded, "OpenSSL 3.x/4.x not available on this host — skipping JFR emission test");

            List<RecordedEvent> events = stopAndRead(recording).stream()
                    .filter(e -> EVENT_NAME.equals(e.getEventType().getName()))
                    .toList();
            assertThat(events)
                    .as("exactly one OpenSslLoad event per successful load()")
                    .hasSize(1);

            RecordedEvent event = events.getFirst();
            assertThat(event.getInt("versionMajor")).isGreaterThanOrEqualTo(3);
            assertThat(event.getString("versionText")).isNotBlank();
            assertThat(event.getString("ctxApi")).isEqualTo("SSL_CTX_new_ex");
            assertThat(event.getString("sslPath")).isNotBlank();
            assertThat(event.getString("cryptoPath")).isNotBlank();
        }
    }

    private static List<RecordedEvent> stopAndRead(Recording recording) throws Exception {
        Path dump = Files.createTempFile("openssl-load-event", ".jfr");
        try {
            recording.dump(dump);
            recording.stop();
            return RecordingFile.readAllEvents(dump);
        } finally {
            Files.deleteIfExists(dump);
        }
    }
}
