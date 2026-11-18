/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.spi.events.EventEngineConfig;
import eu.exeris.kernel.spi.events.EventRegistry;
import eu.exeris.kernel.spi.events.EventTypeSpec;
import eu.exeris.kernel.spi.exceptions.events.EventRegistryException;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The raw channel and the Exeris-framed topics never share a topic, whichever side claims it second. */
@DisplayName("Kafka engine — raw topic collisions")
class KafkaRawTopicCollisionTest {

    private static final KafkaEventConfig.LoopThread LOOP = KafkaEventConfig.LoopThread.VIRTUAL;

    private KafkaEventEngine engine;
    private MockProducer<byte[], byte[]> producer;

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    private void engine(String prefix) {
        KafkaEventConfig config = new KafkaEventConfig("localhost:1", "collision", prefix, true, 0L,
                Duration.ofMillis(20L), Map.of(), Map.of(), "", LOOP, Duration.ofSeconds(1));
        producer = new MockProducer<>(true, null, new ByteArraySerializer(), new ByteArraySerializer());
        engine = new KafkaEventEngine(EventEngineConfig.communityDefaults(), config, producer);
    }

    @Test
    @DisplayName("raw subscribe on a registered type's topic is refused")
    void subscribeOnTypeTopicRefused() {
        engine("");
        engine.registry().register(EventTypeSpec.of("OrderCreated", 9_501));

        KafkaRecordChannel records = engine.records();

        assertThatThrownBy(() -> records.subscribe("OrderCreated", _ -> { }))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("OrderCreated");
    }

    @Test
    @DisplayName("with a topic prefix the effective, prefixed topic is the one refused; the bare name is free")
    void subscribeOnPrefixedTypeTopicRefused() {
        engine("exeris.");
        engine.registry().register(EventTypeSpec.of("OrderCreated", 9_502));

        KafkaRecordChannel records = engine.records();

        assertThatThrownBy(() -> records.subscribe("exeris.OrderCreated", _ -> { }))
                .isInstanceOf(IllegalStateException.class);
        records.subscribe("OrderCreated", _ -> { });
    }

    @Test
    @DisplayName("with an ADR-050 topic override the override topic is refused")
    void subscribeOnOverriddenTopicRefused() {
        engine("exeris.");
        engine.registry().register(EventTypeSpec.ofPersistent("OrderCreated", 9_503, "orders.created"));

        KafkaRecordChannel records = engine.records();

        assertThatThrownBy(() -> records.subscribe("exeris.orders.created", _ -> { }))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("registering a type onto an already-subscribed raw topic is refused and leaves no trace")
    void registerOntoRawTopicRefused() {
        engine("exeris.");
        engine.records().subscribe("exeris.OrderCreated", _ -> { });
        engine.records().subscribe("raw.orders", _ -> { });
        EventRegistry registry = engine.registry();
        EventTypeSpec clashing = EventTypeSpec.of("OrderCreated", 9_504);

        assertThatThrownBy(() -> registry.register(clashing))
                .isInstanceOf(EventRegistryException.class).hasMessageContaining("raw record topic");
        assertThat(engine.registry().size()).isZero();
        assertThat(engine.registry().resolve("OrderCreated")).isNull();

        // Raw topics are literal, so prefix + override (exeris.orders) does not clash with raw.orders.
        engine.registry().register(EventTypeSpec.ofPersistent("Other", 9_505, "orders"));
        assertThat(engine.registry().size()).isEqualTo(1);
    }

    @Test
    @DisplayName("subscribe and send on the event-log topic are refused")
    void eventLogTopicRefused() {
        engine("exeris.");
        String eventLog = "exeris.exeris-event-log";
        KafkaRecordChannel records = engine.records();
        ProducerRecord<byte[], byte[]> toEventLog = new ProducerRecord<>(eventLog, new byte[] {1});

        assertThatThrownBy(() -> records.subscribe(eventLog, _ -> { }))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> records.send(toEventLog, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(producer.history()).isEmpty();
    }

    @Test
    @DisplayName("a raw send onto a registered type's topic is refused and nothing is written")
    void sendOntoTypeTopicRefused() {
        engine("");
        engine.registry().register(EventTypeSpec.of("OrderCreated", 9_506));
        KafkaRecordChannel records = engine.records();
        ProducerRecord<byte[], byte[]> toTypeTopic = new ProducerRecord<>("OrderCreated", new byte[] {1});

        assertThatThrownBy(() -> records.send(toTypeTopic, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("OrderCreated");
        assertThat(producer.history()).isEmpty();
    }

    @Test
    @DisplayName("a second raw subscribe on the same topic is refused")
    void duplicateSubscribeRefused() {
        engine("");
        KafkaRecordChannel records = engine.records();
        records.subscribe("raw.once", _ -> { });

        assertThatThrownBy(() -> records.subscribe("raw.once", _ -> { }))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("raw.once");
    }
}
