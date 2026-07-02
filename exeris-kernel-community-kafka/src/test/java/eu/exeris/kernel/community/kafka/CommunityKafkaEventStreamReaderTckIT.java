/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventStreamAppender;
import eu.exeris.kernel.spi.events.EventStreamReader;
import eu.exeris.kernel.spi.events.StreamId;
import eu.exeris.kernel.tck.contract.events.AbstractEventStreamReaderTck;
import org.apache.kafka.clients.producer.Producer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

/**
 * Community Kafka binding of {@link AbstractEventStreamReaderTck} against a real Kafka 3.x broker
 * (ADR-049). Streams are seeded through the {@link KafkaEventStreamAppender} with
 * {@link EventStreamAppender#ANY_VERSION}, so replay is verified end-to-end over the same durable log
 * the appender writes — proving the append→replay per-stream ordering round-trip on Kafka.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Community :: Kafka — KafkaEventStreamReader TCK (Testcontainers Kafka 3.x, ADR-049)")
class CommunityKafkaEventStreamReaderTckIT extends AbstractEventStreamReaderTck {

    private static final int SEED_EVENT_TYPE_ORDINAL = 7_777;

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    private static KafkaEventConfig config;
    private static Producer<byte[], byte[]> producer;
    private static KafkaEventStreamAppender seeder;

    @BeforeAll
    static void bootstrap() {
        config = KafkaEventLogTestSupport.config(KAFKA);
        producer = KafkaEventLogTestSupport.createProducer(KAFKA);
        seeder = new KafkaEventStreamAppender(producer, config, "kafka-tck-seeder");
    }

    @AfterAll
    static void teardown() {
        seeder = null;
        if (producer != null) {
            producer.close();
            producer = null;
        }
    }

    @Override
    protected EventStreamReader createReader() {
        return new KafkaEventStreamReader(config, name -> -1);
    }

    @Override
    protected void seedStream(StreamId streamId, int eventCount) {
        for (int i = 0; i < eventCount; i++) {
            UUID eventId = UUID.randomUUID();
            EventDescriptor descriptor = new EventDescriptor(
                    eventId.getMostSignificantBits(), eventId.getLeastSignificantBits(),
                    streamId.streamIdHigh(), streamId.streamIdLow(),
                    SEED_EVENT_TYPE_ORDINAL, EventDescriptor.FLAG_PERSISTENT, System.currentTimeMillis());
            // ADR-049 ordering contract: event i carries indexPayload(i) so the reader TCK can assert
            // the append order round-trips (an unordered replay would fail the assertion).
            seeder.append(streamId, EventStreamAppender.ANY_VERSION, descriptor,
                    KafkaHeapEventPayload.wrap(indexPayload(i)));
        }
    }
}
