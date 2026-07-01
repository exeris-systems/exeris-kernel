/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.kafka;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.testcontainers.containers.KafkaContainer;

import java.util.Properties;
import java.util.UUID;

/**
 * Test-only helpers shared by the Kafka event-log TCK bindings (ADR-049): a per-run config with a
 * unique scan group and an {@code acks=all} byte-array producer for seeding/appending.
 */
final class KafkaEventLogTestSupport {

    private KafkaEventLogTestSupport() {
        // utility — no instances
    }

    static KafkaEventConfig config(KafkaContainer kafka) {
        return KafkaEventConfig.defaults(kafka.getBootstrapServers(), "exeris-eventlog-tck-" + UUID.randomUUID());
    }

    static Producer<byte[], byte[]> createProducer(KafkaContainer kafka) {
        Properties props = new Properties();
        props.put("bootstrap.servers", kafka.getBootstrapServers());
        props.put("key.serializer",    ByteArraySerializer.class.getName());
        props.put("value.serializer",  ByteArraySerializer.class.getName());
        props.put("acks",              "all");
        return new KafkaProducer<>(props);
    }
}
