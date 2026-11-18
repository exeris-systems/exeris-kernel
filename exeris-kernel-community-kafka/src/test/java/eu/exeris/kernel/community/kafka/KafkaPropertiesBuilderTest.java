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
import java.util.Map;
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
    @DisplayName("a producer override wins over acks and linger.ms, and adds nothing else")
    void producerOverridesWinOverDefaults() {
        KafkaEventConfig cfg = CONFIG_ALL_ACKS.withProducerOverrides(
                Map.of("acks", "0", "linger.ms", "99", "compression.type", "lz4"));

        Properties props = KafkaEventEngine.producerProperties(cfg);

        assertThat(props.getProperty("acks")).isEqualTo("0");
        assertThat(props.getProperty("linger.ms")).isEqualTo("99");
        assertThat(props.stringPropertyNames()).containsExactlyInAnyOrder(
                "bootstrap.servers", "key.serializer", "value.serializer", "acks", "linger.ms",
                "compression.type");
    }

    @Test
    @DisplayName("a consumer override wins over enable.auto.commit and auto.offset.reset, and adds nothing else")
    void consumerOverridesWinOverDefaults() {
        KafkaEventConfig cfg = CONFIG_ALL_ACKS.withConsumerOverrides(
                Map.of("enable.auto.commit", "false", "auto.offset.reset", "latest",
                        "max.poll.records", "10"));

        Properties props = KafkaEventEngine.consumerProperties(cfg);

        assertThat(props.getProperty("enable.auto.commit")).isEqualTo("false");
        assertThat(props.getProperty("auto.offset.reset")).isEqualTo("latest");
        assertThat(props.stringPropertyNames()).containsExactlyInAnyOrder(
                "bootstrap.servers", "group.id", "key.deserializer", "value.deserializer",
                "enable.auto.commit", "auto.offset.reset", "max.poll.records");
    }

    @Test
    @DisplayName("clientId adds client.id to both builders, and a map entry wins over it")
    void clientIdIsAddedAndMapWins() {
        KafkaEventConfig cfg = CONFIG_ALL_ACKS.withClientId("from-component");

        assertThat(KafkaEventEngine.producerProperties(cfg)).hasSize(6)
                .containsEntry("client.id", "from-component");
        assertThat(KafkaEventEngine.consumerProperties(cfg)).hasSize(7)
                .containsEntry("client.id", "from-component");

        KafkaEventConfig overridden = cfg
                .withProducerOverrides(Map.of("client.id", "from-map-p"))
                .withConsumerOverrides(Map.of("client.id", "from-map-c"));

        assertThat(KafkaEventEngine.producerProperties(overridden)).hasSize(6)
                .containsEntry("client.id", "from-map-p");
        assertThat(KafkaEventEngine.consumerProperties(overridden)).hasSize(7)
                .containsEntry("client.id", "from-map-c");
    }

    @Test
    @DisplayName("KafkaEventLog.consumerProps ignores overrides and clientId")
    void eventLogConsumerPropsUnaffectedByOverrides() {
        KafkaEventConfig cfg = CONFIG_ALL_ACKS.withClientId("cid")
                .withConsumerOverrides(Map.of("auto.offset.reset", "latest", "extra", "x"));

        Properties props = KafkaEventLog.consumerProps(cfg);

        assertThat(props).hasSize(7);
        assertThat(props.getProperty("auto.offset.reset")).isEqualTo("earliest");
        assertThat(props.stringPropertyNames()).doesNotContain("client.id", "extra");
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
