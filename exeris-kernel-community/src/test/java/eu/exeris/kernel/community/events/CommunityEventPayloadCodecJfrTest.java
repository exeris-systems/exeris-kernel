/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.events;

import eu.exeris.kernel.spi.events.codec.EventCodecContext;
import eu.exeris.kernel.spi.events.codec.EventPayloadCodec;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Asserts {@link eu.exeris.kernel.community.events.jfr.CommunityEventPayloadEncodeFailedEvent}
 * fires on an encode failure and carries the secret-safe fields (ADR-046 JFR obligation).
 *
 * <p>Uses the deterministic {@link Recording} + dump + {@link RecordingFile} pattern (mirrors
 * {@code CommunityJwksKeyRotationJfrTest}) — a synchronous recording avoids the
 * {@code RecordingStream.startAsync()} race against a single synchronous emit.
 */
@DisplayName("Community: EventPayloadCodec encode-failure JFR event")
class CommunityEventPayloadCodecJfrTest {

    private static final String ENCODE_FAILED = "eu.exeris.kernel.events.CommunityEventPayloadEncodeFailed";

    @TempDir
    private Path tmp;

    @Test
    @DisplayName("encode failure fires CommunityEventPayloadEncodeFailed with secret-safe fields")
    void encodeFailureFiresJfrEvent() throws IOException {
        Path jfr = tmp.resolve("encode-failed.jfr");
        try (Recording recording = new Recording()) {
            recording.enable(ENCODE_FAILED);
            recording.start();

            // Self-referential map → Jackson trips its max-nesting-depth → encode wraps + emits.
            Map<String, Object> cyclic = new HashMap<>();
            cyclic.put("self", cyclic);
            EventPayloadCodec codec = new CommunityJsonEventPayloadCodec(new ObjectMapper());
            assertThatThrownBy(() -> codec.encode(cyclic, EventCodecContext.json("OrderPlaced")))
                    .isInstanceOf(IllegalStateException.class);

            recording.stop();
            recording.dump(jfr);
        }

        List<RecordedEvent> events = RecordingFile.readAllEvents(jfr).stream()
                .filter(e -> e.getEventType().getName().equals(ENCODE_FAILED))
                .toList();

        assertThat(events).as("encode-failure JFR event must fire").hasSize(1);
        RecordedEvent event = events.getFirst();
        assertThat(event.getString("payloadType"))
                .as("payloadType records the runtime class")
                .contains("HashMap");
        assertThat(event.getString("contentType")).isEqualTo("application/json");
        assertThat(event.getString("eventType")).isEqualTo("OrderPlaced");
        assertThat(event.getString("failureClass"))
                .as("failureClass records the actual binding (Jackson) exception class — "
                        + "guards against a swallowed/re-wrapped regression")
                .startsWith("tools.jackson");
    }
}
