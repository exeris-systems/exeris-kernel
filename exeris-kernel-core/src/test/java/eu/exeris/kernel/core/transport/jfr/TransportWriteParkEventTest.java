/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.jfr;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TransportWriteParkEvent: one record per park, only when enabled")
class TransportWriteParkEventTest {

    private static final String EVENT = "eu.exeris.kernel.core.transport.WriteBackpressurePark";

    @Test
    @DisplayName("an enabled event records the stream, the queued bytes and the bound")
    void enabledEventCarriesItsFields(@TempDir Path dir) throws Exception {
        Path dump = dir.resolve("park.jfr");
        try (Recording recording = new Recording()) {
            recording.enable(EVENT);
            recording.start();
            TransportWriteParkEvent.emit(42L, 196_608L, 262_144L);
            recording.stop();
            recording.dump(dump);
        }

        List<RecordedEvent> events = readParkEvents(dump);
        assertThat(events).hasSize(1);
        RecordedEvent event = events.getFirst();
        assertThat(event.getLong("streamId")).isEqualTo(42L);
        assertThat(event.getLong("queuedBytes")).isEqualTo(196_608L);
        assertThat(event.getLong("boundBytes")).isEqualTo(262_144L);
    }

    @Test
    @DisplayName("a disabled event records nothing while another recording runs")
    void disabledEventRecordsNothing(@TempDir Path dir) throws Exception {
        Path dump = dir.resolve("disabled.jfr");
        try (Recording recording = new Recording()) {
            recording.disable(EVENT);
            recording.start();
            TransportWriteParkEvent.emit(7L, 1L, 2L);
            recording.stop();
            recording.dump(dump);
        }

        assertThat(readParkEvents(dump)).isEmpty();
    }

    private static List<RecordedEvent> readParkEvents(Path dump) throws java.io.IOException {
        return RecordingFile.readAllEvents(dump).stream()
                .filter(e -> e.getEventType().getName().equals(EVENT))
                .toList();
    }
}
