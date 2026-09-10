/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.spi.events.EventTypeSpec;
import eu.exeris.kernel.spi.exceptions.events.EventRegistryException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link KafkaEventRegistry}'s ADR-050 topic round-trip + spec identity — the Kafka
 * binding is where {@code topic} actually drives routing, so its registry passthrough is pinned
 * here directly (the registry is a plain heap map; no broker needed). The end-to-end Kafka routing
 * over the override topic is covered by the Testcontainers {@code AbstractKafkaEventEngineTck}, and
 * the override/fallback/prefix decision by {@code KafkaTopicResolutionTest}.
 */
@DisplayName("KafkaEventRegistry — ADR-050 topic round-trip + spec identity (no broker)")
class KafkaEventRegistryTest {

    private static final int    ORDINAL = 7_050;
    private static final String TYPE    = "KafkaRegistryTopicedEvent";
    private static final String TOPIC   = "orders.created";
    private static final String OTHER   = "orders.updated";

    @Test
    @DisplayName("a topic override round-trips via resolve() and specOfOrdinal()")
    void topicRoundTrips() {
        KafkaEventRegistry registry = new KafkaEventRegistry();
        registry.register(EventTypeSpec.ofPersistent(TYPE, ORDINAL, TOPIC));

        EventTypeSpec byName = registry.resolve(TYPE);
        assertThat(byName).as("resolve() MUST return the registered spec").isNotNull();
        assertThat(byName.hasTopic()).isTrue();
        assertThat(byName.topic()).isEqualTo(TOPIC);

        // specOfOrdinal is exactly what the publish path (buildRecord) reads to resolve the topic.
        assertThat(registry.specOfOrdinal(ORDINAL))
                .as("specOfOrdinal MUST return the same topic-carrying spec the publish path reads")
                .isEqualTo(byName);
    }

    @Test
    @DisplayName("no topic reports hasTopic()==false; specOfOrdinal on an unregistered ordinal is null")
    void noTopicAndUnknownOrdinal() {
        KafkaEventRegistry registry = new KafkaEventRegistry();
        registry.register(EventTypeSpec.ofPersistent(TYPE, ORDINAL));

        assertThat(registry.resolve(TYPE).hasTopic())
                .as("a spec with no topic falls back to the name-derived default routing")
                .isFalse();
        assertThat(registry.specOfOrdinal(999_999))
                .as("an unregistered ordinal MUST resolve to null (publish path throws unregistered)")
                .isNull();
    }

    @Test
    @DisplayName("a blank topic normalizes to null — null vs blank for same name+ordinal is idempotent, not a conflict")
    void blankTopicNormalizesToNull() {
        KafkaEventRegistry registry = new KafkaEventRegistry();
        registry.register(EventTypeSpec.ofPersistent(TYPE, ORDINAL));                 // topic = null

        assertThatCode(() -> registry.register(EventTypeSpec.ofPersistent(TYPE, ORDINAL, "   ")))
                .as("blank normalizes to null (ADR-050) — same identity, no spurious conflict")
                .doesNotThrowAnyException();
        assertThat(registry.resolve(TYPE).hasTopic()).isFalse();
        assertThat(registry.resolve(TYPE).topic())
                .as("a blank topic MUST be observable as normalized null")
                .isNull();
    }

    @Test
    @DisplayName("topic is part of spec identity: different topic conflicts; identical spec is idempotent")
    void topicParticipatesInIdentity() {
        KafkaEventRegistry registry = new KafkaEventRegistry();
        EventTypeSpec spec = EventTypeSpec.ofPersistent(TYPE, ORDINAL, TOPIC);
        registry.register(spec);

        assertThatThrownBy(() -> registry.register(EventTypeSpec.ofPersistent(TYPE, ORDINAL, OTHER)))
                .as("changing the topic under a fixed name+ordinal MUST raise a registry conflict")
                .isInstanceOf(EventRegistryException.class);
        assertThatCode(() -> registry.register(spec))
                .as("re-registering the identical topic-carrying spec MUST be idempotent")
                .doesNotThrowAnyException();
    }
}
