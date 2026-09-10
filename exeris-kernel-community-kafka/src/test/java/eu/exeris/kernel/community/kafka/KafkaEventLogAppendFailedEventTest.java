/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link KafkaEventLogAppendFailedEvent} — pins the secret-safe JFR contract
 * (stream type + reason token + exception class only; never the failure message) without a broker.
 */
@DisplayName("KafkaEventLogAppendFailedEvent — secret-safe JFR emission")
class KafkaEventLogAppendFailedEventTest {

    private static final String EVENT = "eu.exeris.kernel.events.kafka.EventLogAppendFailed";

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("emit carries engine/stream/reason/failure-class and never the exception message")
    void emitCarriesSecretSafeFields() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<RecordedEvent> captured = new AtomicReference<>();

        try (RecordingStream rs = new RecordingStream()) {
            rs.enable(EVENT);
            rs.onEvent(EVENT, event -> {
                if (captured.compareAndSet(null, event)) {
                    received.countDown();
                }
            });
            rs.startAsync();

            RuntimeException boom = new IllegalStateException("SECRET-payload-echo-42");
            KafkaEventLogAppendFailedEvent.emit(
                    "unit-engine", "OrderStream", KafkaEventLogAppendFailedEvent.REASON_KAFKA, boom);

            assertThat(received.await(5, TimeUnit.SECONDS))
                    .as("the JFR event MUST be emitted")
                    .isTrue();
            RecordedEvent event = captured.get();
            assertThat(event.getString("engineName")).isEqualTo("unit-engine");
            assertThat(event.getString("streamType")).isEqualTo("OrderStream");
            assertThat(event.getString("reason")).isEqualTo("KAFKA");
            assertThat(event.getString("failureClass")).isEqualTo("java.lang.IllegalStateException");
            // Secret-safe: the exception message MUST NOT leak into any recorded field.
            assertThat(event.getString("failureClass")).doesNotContain("SECRET");
        }
    }
}
