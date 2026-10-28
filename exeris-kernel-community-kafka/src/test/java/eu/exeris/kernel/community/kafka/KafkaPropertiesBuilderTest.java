/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit verification for the package-private Kafka {@link Properties} builders.
 *
 * <p>Pins the exact set of keys and values produced by {@link KafkaEventEngine#producerProperties(KafkaEventConfig)},
 * {@link KafkaEventEngine#consumerProperties(KafkaEventConfig)}, and {@link KafkaEventLog#consumerProps(KafkaEventConfig)}.
 */
@DisplayName("Kafka client configuration properties builders")
class KafkaPropertiesBuilderTest {

    private static final KafkaEventConfig CONFIG_ALL_ACKS = new KafkaEventConfig(
            "kafka.example.com:9092",
            "test-consumer-group",
            KafkaEventConfig.EMPTY_TOPIC_PREFIX,
            true,
            15L,
            Duration.ofMillis(300L)
    );

    private static final KafkaEventConfig CONFIG_LEADER_ACKS = new KafkaEventConfig(
            "broker1:9092,broker2:9092",
            "custom-group",
            KafkaEventConfig.EMPTY_TOPIC_PREFIX,
            false,
            0L,
            Duration.ofMillis(100L)
    );

    @Test
    @DisplayName("producerProperties builds exact expected key/value set with all acks")
    void producerPropertiesWithAllAcks() {
        Properties props = KafkaEventEngine.producerProperties(CONFIG_ALL_ACKS);

        assertThat(props).hasSize(5);
        assertThat(props.getProperty("bootstrap.servers")).isEqualTo("kafka.example.com:9092");
        assertThat(props.getProperty("key.serializer")).isEqualTo(ByteArraySerializer.class.getName());
        assertThat(props.getProperty("value.serializer")).isEqualTo(ByteArraySerializer.class.getName());
        assertThat(props.getProperty("acks")).isEqualTo("all");
        assertThat(props.getProperty("linger.ms")).isEqualTo("15");
    }

    @Test
    @DisplayName("producerProperties builds exact expected key/value set with leader acks")
    void producerPropertiesWithLeaderAcks() {
        Properties props = KafkaEventEngine.producerProperties(CONFIG_LEADER_ACKS);

        assertThat(props).hasSize(5);
        assertThat(props.getProperty("bootstrap.servers")).isEqualTo("broker1:9092,broker2:9092");
        assertThat(props.getProperty("key.serializer")).isEqualTo(ByteArraySerializer.class.getName());
        assertThat(props.getProperty("value.serializer")).isEqualTo(ByteArraySerializer.class.getName());
        assertThat(props.getProperty("acks")).isEqualTo("1");
        assertThat(props.getProperty("linger.ms")).isEqualTo("0");
    }

    @Test
    @DisplayName("consumerProperties builds exact expected key/value set")
    void consumerPropertiesExactKeysAndValues() {
        Properties props = KafkaEventEngine.consumerProperties(CONFIG_ALL_ACKS);

        assertThat(props).hasSize(6);
        assertThat(props.getProperty("bootstrap.servers")).isEqualTo("kafka.example.com:9092");
        assertThat(props.getProperty("group.id")).isEqualTo("test-consumer-group");
        assertThat(props.getProperty("key.deserializer")).isEqualTo(ByteArrayDeserializer.class.getName());
        assertThat(props.getProperty("value.deserializer")).isEqualTo(ByteArrayDeserializer.class.getName());
        assertThat(props.getProperty("enable.auto.commit")).isEqualTo("true");
        assertThat(props.getProperty("auto.offset.reset")).isEqualTo("earliest");
    }

    @Test
    @DisplayName("consumerProps builds exact expected key/value set with unique scan group ID")
    void eventLogConsumerPropsExactKeysAndValues() {
        Properties props = KafkaEventLog.consumerProps(CONFIG_ALL_ACKS);

        assertThat(props).hasSize(7);
        assertThat(props.getProperty("bootstrap.servers")).isEqualTo("kafka.example.com:9092");
        assertThat(props.getProperty("key.deserializer")).isEqualTo(ByteArrayDeserializer.class.getName());
        assertThat(props.getProperty("value.deserializer")).isEqualTo(ByteArrayDeserializer.class.getName());
        assertThat(props.getProperty("enable.auto.commit")).isEqualTo("false");
        assertThat(props.getProperty("auto.offset.reset")).isEqualTo("earliest");
        assertThat(props.getProperty("allow.auto.create.topics")).isEqualTo("false");

        String groupId = props.getProperty("group.id");
        assertThat(groupId).startsWith("exeris-eventlog-scan-");
        String uuidPart = groupId.substring("exeris-eventlog-scan-".length());
        UUID parsedUuid = UUID.fromString(uuidPart);
        assertThat(parsedUuid).isNotNull();
    }
}
