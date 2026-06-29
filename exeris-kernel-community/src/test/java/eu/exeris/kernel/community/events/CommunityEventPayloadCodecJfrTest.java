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
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Asserts {@link eu.exeris.kernel.community.events.jfr.CommunityEventPayloadEncodeFailedEvent}
 * fires on an encode failure and carries the secret-safe fields (ADR-046 JFR obligation).
 */
@DisplayName("Community: EventPayloadCodec encode-failure JFR event")
class CommunityEventPayloadCodecJfrTest {

    private static final String ENCODE_FAILED = "eu.exeris.kernel.events.CommunityEventPayloadEncodeFailed";
    private static final long SETTLE_MILLIS = 1_000L;

    @Test
    @Timeout(value = 30L, unit = TimeUnit.SECONDS)
    @DisplayName("encode failure fires CommunityEventPayloadEncodeFailed with secret-safe fields")
    void encodeFailureFiresJfrEvent() {
        AtomicReference<RecordedEvent> captured = new AtomicReference<>();
        try (RecordingStream recording = new RecordingStream()) {
            recording.enable(ENCODE_FAILED);
            recording.onEvent(ENCODE_FAILED, captured::set);
            recording.startAsync();

            // Self-referential map → Jackson trips its max-nesting-depth → encode wraps + emits.
            Map<String, Object> cyclic = new HashMap<>();
            cyclic.put("self", cyclic);
            EventPayloadCodec codec = new CommunityJsonEventPayloadCodec(new ObjectMapper());
            assertThatThrownBy(() -> codec.encode(cyclic, EventCodecContext.json("OrderPlaced")))
                    .isInstanceOf(IllegalStateException.class);

            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(SETTLE_MILLIS));
        }

        RecordedEvent event = captured.get();
        assertThat(event).as("encode-failure JFR event must fire").isNotNull();
        assertThat(event.getString("payloadType"))
                .as("payloadType records the runtime class")
                .contains("HashMap");
        assertThat(event.getString("contentType")).isEqualTo("application/json");
        assertThat(event.getString("eventType")).isEqualTo("OrderPlaced");
        assertThat(event.getString("failureClass"))
                .as("failureClass records the binding exception class")
                .isNotBlank();
    }
}
