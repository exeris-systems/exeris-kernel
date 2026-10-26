/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.persistence;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RlsBypassRoleEvent: one record per consented downgrade, only when enabled")
class RlsBypassRoleEventTest {

    private static final String EVENT = "eu.exeris.kernel.persistence.RlsBypassRole";

    @Test
    @DisplayName("an enabled event records the provider, the role and the attribute")
    void enabledEventCarriesItsFields(@TempDir Path dir) throws IOException {
        Path dump = dir.resolve("downgrade.jfr");
        try (Recording recording = new Recording()) {
            recording.enable(EVENT);
            recording.start();
            RlsBypassRoleEvent.commitDowngrade(
                    new RlsBypassRoleEvent.Payload("ExerisCommunity/JDBC", "app_owner", "BYPASSRLS"));
            recording.stop();
            recording.dump(dump);
        }

        List<RecordedEvent> events = readDowngrades(dump);
        assertThat(events).hasSize(1);
        RecordedEvent event = events.getFirst();
        assertThat(event.getString("providerName")).isEqualTo("ExerisCommunity/JDBC");
        assertThat(event.getString("roleName")).isEqualTo("app_owner");
        assertThat(event.getString("attribute")).isEqualTo("BYPASSRLS");
    }

    @Test
    @DisplayName("a disabled event records nothing while another recording runs")
    void disabledEventRecordsNothing(@TempDir Path dir) throws IOException {
        Path dump = dir.resolve("disabled.jfr");
        try (Recording recording = new Recording()) {
            recording.disable(EVENT);
            recording.start();
            RlsBypassRoleEvent.commitDowngrade(new RlsBypassRoleEvent.Payload("p", "r", "SUPERUSER"));
            recording.stop();
            recording.dump(dump);
        }

        assertThat(readDowngrades(dump)).isEmpty();
    }

    private static List<RecordedEvent> readDowngrades(Path dump) throws IOException {
        return RecordingFile.readAllEvents(dump).stream()
                .filter(e -> e.getEventType().getName().equals(EVENT))
                .toList();
    }
}
