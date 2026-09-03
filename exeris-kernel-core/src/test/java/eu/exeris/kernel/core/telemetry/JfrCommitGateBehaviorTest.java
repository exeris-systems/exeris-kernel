/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.telemetry;

import eu.exeris.kernel.core.persistence.AdmissionDecisionEvent;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1 Unit: with a {@link JfrEventCommitter} installed via {@link JfrCommitGate}, admission events
 * emitted from a virtual thread are still recorded with correct fields — proving the off-thread
 * commit produces valid JFR data, not just a crash-free no-op.
 */
@DisplayName("L1 Unit: JfrCommitGate off-thread commit records valid events")
class JfrCommitGateBehaviorTest {

    private static final String EVENT_NAME = "eu.exeris.kernel.persistence.AdmissionDecision";

    @AfterEach
    void clearGate() {
        JfrCommitGate.clear();
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("AdmissionDecisionEvent emitted from a VT is recorded via the committer")
    void admissionDecisionRecordedThroughCommitter() throws Exception {
        JfrEventCommitter committer = JfrEventCommitter.start();
        JfrCommitGate.install(committer);
        try (Recording recording = new Recording()) {
            recording.enable(EVENT_NAME);
            recording.start();

            Thread vt = Thread.ofVirtual().start(() ->
                    AdmissionDecisionEvent.emit(new AdmissionDecisionEvent.Payload(
                            "postgres-community", true, 3, 0.5d, 0.95d, 2L, 1L, "ACCEPT")));
            vt.join(TimeUnit.SECONDS.toMillis(5));

            committer.close(); // flush async commit
            JfrCommitGate.clear();

            List<RecordedEvent> events = stopAndRead(recording);
            assertThat(events).as("the admission decision must be recorded").hasSize(1);
            RecordedEvent event = events.getFirst();
            assertThat(event.getString("providerId")).isEqualTo("postgres-community");
            assertThat(event.getBoolean("accepted")).isTrue();
            assertThat(event.getInt("queueDepth")).isEqualTo(3);
            assertThat(event.getString("decisionReason")).isEqualTo("ACCEPT");
            // committed off the producer VT, on the platform committer thread
            assertThat(event.getThread().getJavaName()).isEqualTo("exeris/jfr-committer");
        }
    }

    private static List<RecordedEvent> stopAndRead(Recording recording) throws Exception {
        Path dump = Files.createTempFile("jfr-commit-gate", ".jfr");
        try {
            recording.stop();
            recording.dump(dump);
            return RecordingFile.readAllEvents(dump).stream()
                    .filter(e -> EVENT_NAME.equals(e.getEventType().getName()))
                    .toList();
        } finally {
            Files.deleteIfExists(dump);
        }
    }
}
