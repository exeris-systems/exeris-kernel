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
 * L0: CI-matrix-only assertion that {@link CoreOpenSslLoader#load} bound the <em>intended</em>
 * OpenSSL major — not a silent fallback to a co-installed system OpenSSL.
 *
 * <p>Reads {@code -Dexeris.test.expectedOpenSslMajor}. When unset/blank (local dev) the test
 * <strong>skips</strong>. When set (the {@code tls-openssl-matrix} CI entries pin it to the
 * SONAME major), the test is a <strong>hard gate</strong>: {@code load()} must succeed and the
 * emitted {@code OpenSslLoad} event's {@code versionMajor} must equal the pinned value. This
 * converts an env-path miss (which would otherwise fall back to the host's system 3.x and pass
 * the band gate) into a build failure on the 4.0 matrix entry.
 *
 * <p>Unlike {@link OpenSslLoadEventTest}, a load failure here is NOT swallowed into a skip — it
 * propagates so the matrix entry FAILS rather than silently passing.
 */
@DisplayName("L0: CoreOpenSslLoader pinned-version assertion (CI OpenSSL matrix)")
class CoreOpenSslPinnedVersionAssertionTest {

    private static final String EVENT_NAME = "eu.exeris.kernel.core.crypto.OpenSslLoad";
    private static final String EXPECTED_MAJOR_PROP = "exeris.test.expectedOpenSslMajor";

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("load() binds exactly the pinned OpenSSL major reported in the matrix env")
    void boundMajorMatchesPinnedMatrixMajor() throws Exception {
        String expected = System.getProperty(EXPECTED_MAJOR_PROP);
        assumeTrue(expected != null && !expected.isBlank(),
                "pinned-version assertion runs only in the CI OpenSSL matrix "
                + "(set -D" + EXPECTED_MAJOR_PROP + "=<major>)");

        int expectedMajor = Integer.parseInt(expected.trim());

        try (Recording recording = new Recording()) {
            recording.enable(EVENT_NAME);
            recording.start();

            // HARD-RUN: any load failure must propagate (FAIL the matrix entry), never skip.
            // CHECKSTYLE:OFF DirectArena — bootstrap loads symbols for the JVM lifetime
            CoreOpenSslLoader.load(Arena.global());
            // CHECKSTYLE:ON

            List<RecordedEvent> events = stopAndRead(recording).stream()
                    .filter(e -> EVENT_NAME.equals(e.getEventType().getName()))
                    .toList();
            assertThat(events)
                    .as("exactly one OpenSslLoad event per successful load()")
                    .hasSize(1);

            int boundMajor = events.getFirst().getInt("versionMajor");
            assertThat(boundMajor)
                    .as("JVM must bind the pinned OpenSSL major (no silent fallback to system OpenSSL)")
                    .isEqualTo(expectedMajor);
        }
    }

    private static List<RecordedEvent> stopAndRead(Recording recording) throws Exception {
        Path dump = Files.createTempFile("openssl-pinned-version", ".jfr");
        try {
            recording.dump(dump);
            recording.stop();
            return RecordingFile.readAllEvents(dump);
        } finally {
            Files.deleteIfExists(dump);
        }
    }
}
