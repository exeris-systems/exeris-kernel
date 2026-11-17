/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the client-property overrides, client id and loop-thread kind of
 * {@link KafkaEventConfig}.
 */
@DisplayName("KafkaEventConfig — overrides, client id and loop thread")
class KafkaEventConfigTest {

    private static final KafkaEventConfig BASE = KafkaEventConfig.defaults("localhost:9092", "g");

    @ParameterizedTest
    @ValueSource(strings = {"bootstrap.servers", "key.serializer", "value.serializer",
            "transactional.id"})
    @DisplayName("forbidden producer override keys are rejected by name")
    void producerForbiddenKeys(String key) {
        assertThatThrownBy(() -> BASE.withProducerOverrides(Map.of(key, "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(key);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bootstrap.servers", "key.deserializer", "value.deserializer",
            "group.id", "transactional.id"})
    @DisplayName("forbidden consumer override keys are rejected by name")
    void consumerForbiddenKeys(String key) {
        assertThatThrownBy(() -> BASE.withConsumerOverrides(Map.of(key, "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(key);
    }

    @Test
    @DisplayName("keys owned by the other client are allowed in a map")
    void otherClientKeysAreNotForbidden() {
        assertThat(BASE.withProducerOverrides(Map.of("group.id", "x", "key.deserializer", "y"))
                .producerOverrides()).hasSize(2);
        assertThat(BASE.withConsumerOverrides(Map.of("key.serializer", "y"))
                .consumerOverrides()).hasSize(1);
    }

    @Test
    @DisplayName("override maps are defensively copied")
    void mapsAreCopied() {
        Map<String, String> source = new HashMap<>(Map.of("acks", "0"));
        KafkaEventConfig cfg = BASE.withProducerOverrides(source);
        source.put("linger.ms", "1");

        assertThat(cfg.producerOverrides()).containsOnlyKeys("acks");
        assertThatThrownBy(() -> cfg.producerOverrides().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("null maps, clientId and loopThread are rejected")
    void nullsRejected() {
        assertThatThrownBy(() -> BASE.withProducerOverrides(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> BASE.withConsumerOverrides(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> BASE.withClientId(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> BASE.withLoopThread(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("the six-argument constructor and defaults give empty maps, empty clientId, VIRTUAL")
    void legacyConstructorAndDefaults() {
        KafkaEventConfig six = new KafkaEventConfig("b:1", "g", "", true, 5L,
                Duration.ofMillis(250L));

        for (KafkaEventConfig cfg : new KafkaEventConfig[] {six, BASE}) {
            assertThat(cfg.producerOverrides()).isEmpty();
            assertThat(cfg.consumerOverrides()).isEmpty();
            assertThat(cfg.clientId()).isEmpty();
            assertThat(cfg.loopThread()).isEqualTo(KafkaEventConfig.LoopThread.VIRTUAL);
            assertThat(cfg.closeTimeout()).isEqualTo(Duration.ofSeconds(30));
        }
    }

    @Test
    @DisplayName("closeTimeout must be present and positive, and withCloseTimeout changes only it")
    void closeTimeoutValidationAndWither() {
        assertThatThrownBy(() -> BASE.withCloseTimeout(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> BASE.withCloseTimeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("closeTimeout");
        assertThatThrownBy(() -> BASE.withCloseTimeout(Duration.ofMillis(-1L)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("closeTimeout");

        KafkaEventConfig cfg = BASE.withClientId("c").withCloseTimeout(Duration.ofSeconds(2));

        assertThat(cfg.closeTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(cfg.clientId()).isEqualTo("c");
        assertThat(cfg.withClientId("d").closeTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(cfg.toString()).contains("closeTimeout=PT2S");
    }

    @Test
    @DisplayName("withers change only their own component")
    void withersChangeOneComponent() {
        KafkaEventConfig cfg = BASE.withClientId("c").withLoopThread(KafkaEventConfig.LoopThread.PLATFORM);

        assertThat(cfg.clientId()).isEqualTo("c");
        assertThat(cfg.loopThread()).isEqualTo(KafkaEventConfig.LoopThread.PLATFORM);
        assertThat(cfg.bootstrapServers()).isEqualTo("localhost:9092");
    }

    @Test
    @DisplayName("toString prints override key names and never their values")
    void toStringHidesValues() {
        KafkaEventConfig cfg = BASE
                .withProducerOverrides(Map.of("sasl.jaas.config", "SECRET-PRODUCER-VALUE"))
                .withConsumerOverrides(Map.of("ssl.key.password", "SECRET-CONSUMER-VALUE"));

        assertThat(cfg.toString())
                .contains("sasl.jaas.config", "ssl.key.password")
                .doesNotContain("SECRET-PRODUCER-VALUE", "SECRET-CONSUMER-VALUE");
    }
}
