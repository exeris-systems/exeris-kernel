/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.diagnostics;

import eu.exeris.kernel.spi.diagnostics.KernelDiagnostics;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the JFR audit emission for {@link KernelDiagnostics} calls (ADR-033 §EP step 8,
 * codes {@code EX-DIAG-1001..1005}). Uses a synchronous {@link Recording} dump rather than an async
 * {@code RecordingStream} for deterministic assertions.
 */
@DisplayName("CommunityKernelDiagnostics — JFR EX-DIAG-1001..1005 emission")
class CommunityKernelDiagnosticsJfrTest {

    private static final String EVENT = "eu.exeris.kernel.diagnostics.KernelDiagnostics";

    @Test
    @DisplayName("each diagnostic method commits its own EX-DIAG event")
    void eachMethodEmitsItsCode(@TempDir Path tmp) throws Exception {
        KernelDiagnostics diagnostics = new CommunityKernelDiagnosticsProvider().create();
        Path jfr = tmp.resolve("diagnostics.jfr");

        try (Recording recording = new Recording()) {
            recording.enable(EVENT);
            recording.start();
            diagnostics.listProviders();
            diagnostics.listCapabilities();
            diagnostics.getBootstrapDag();
            diagnostics.describeSubsystem("memory");
            diagnostics.getJvmErgonomics();
            recording.stop();
            recording.dump(jfr);
        }

        List<RecordedEvent> events = RecordingFile.readAllEvents(jfr).stream()
                .filter(e -> e.getEventType().getName().equals(EVENT))
                .toList();

        assertThat(events).extracting(e -> e.getString("errorCode"))
                .contains("EX-DIAG-1001", "EX-DIAG-1002", "EX-DIAG-1003", "EX-DIAG-1004", "EX-DIAG-1005");
        assertThat(events).extracting(e -> e.getString("method"))
                .contains("listProviders", "listCapabilities", "getBootstrapDag", "describeSubsystem",
                        "getJvmErgonomics");
    }
}
