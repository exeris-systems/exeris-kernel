/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventStreamAppender;
import eu.exeris.kernel.spi.events.StreamId;
import eu.exeris.kernel.spi.exceptions.events.EventStreamAppendConflictException;
import eu.exeris.kernel.tck.contract.events.AbstractEventStreamAppenderTck;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import org.apache.kafka.clients.producer.Producer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Community Kafka binding of {@link AbstractEventStreamAppenderTck} against a real Kafka 3.x broker
 * (ADR-049). Second half of the "≥2 durable bindings" merge gate (Postgres is the other). Streams use
 * fresh UUIDs per test, so the shared log topic needs no truncation. Also pins the secret-safe JFR
 * conflict-telemetry contract (reusing the binding-agnostic {@code EventLogAppendConflictEvent}).
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Community :: Kafka — KafkaEventStreamAppender TCK (Testcontainers Kafka 3.x, ADR-049)")
class CommunityKafkaEventStreamAppenderTckIT extends AbstractEventStreamAppenderTck {

    private static final String CONFLICT_EVENT = "eu.exeris.kernel.events.EventLogAppendConflict";
    private static final int JFR_EVENT_TYPE_ORDINAL = 7_777;

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    private static KafkaEventConfig config;
    private static Producer<byte[], byte[]> producer;

    @BeforeAll
    static void bootstrap() {
        config = KafkaEventLogTestSupport.config(KAFKA);
        producer = KafkaEventLogTestSupport.createProducer(KAFKA);
    }

    @AfterAll
    static void teardown() {
        if (producer != null) {
            producer.close();
            producer = null;
        }
    }

    @Override
    protected EventStreamAppender createAppender() {
        return new KafkaEventStreamAppender(producer, config, "kafka-tck-appender");
    }

    @Test
    @Tag("integration")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("emits EventLogAppendConflict (HEAD_MISMATCH) when a stale expectedVersion loses (ADR-049)")
    void emitsConflictEventOnStaleExpectedVersion() throws Exception {
        KafkaEventStreamAppender appender = new KafkaEventStreamAppender(producer, config, "occ-jfr-appender");
        UUID stream = UUID.randomUUID();
        StreamId streamId = new StreamId(stream.getMostSignificantBits(), stream.getLeastSignificantBits(), "OccJfr");

        appender.append(streamId, 0L, descriptor(streamId), EventPayload.empty()); // head -> 1

        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<RecordedEvent> captured = new AtomicReference<>();
        try (RecordingStream rs = new RecordingStream()) {
            rs.enable(CONFLICT_EVENT);
            rs.onEvent(CONFLICT_EVENT, event -> {
                if (captured.compareAndSet(null, event)) {
                    received.countDown();
                }
            });
            rs.startAsync();

            assertThatThrownBy(() -> appender.append(streamId, 0L, descriptor(streamId), EventPayload.empty()))
                    .as("re-appending with the now-stale expectedVersion=0 MUST fail closed")
                    .isInstanceOf(EventStreamAppendConflictException.class);

            assertThat(received.await(5, TimeUnit.SECONDS))
                    .as("EventLogAppendConflict JFR event MUST be emitted on the HEAD_MISMATCH path")
                    .isTrue();
            RecordedEvent event = captured.get();
            assertThat(event.getString("phase")).isEqualTo("HEAD_MISMATCH");
            assertThat(event.getString("engineName")).isEqualTo("occ-jfr-appender");
            assertThat(event.getString("streamType")).isEqualTo("OccJfr");
            assertThat(event.getLong("expectedVersion")).isZero();
            assertThat(event.getLong("actualVersion")).isEqualTo(1L);
        }
    }

    private static EventDescriptor descriptor(StreamId streamId) {
        UUID eventId = UUID.randomUUID();
        return new EventDescriptor(
                eventId.getMostSignificantBits(), eventId.getLeastSignificantBits(),
                streamId.streamIdHigh(), streamId.streamIdLow(),
                JFR_EVENT_TYPE_ORDINAL, EventDescriptor.FLAG_PERSISTENT, System.currentTimeMillis());
    }
}
