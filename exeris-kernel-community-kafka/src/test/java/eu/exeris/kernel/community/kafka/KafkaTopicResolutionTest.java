/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.spi.events.EventTypeSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link KafkaEventEngine#effectiveTopic} — the ADR-050 topic-override
 * resolution used by both the publish path and the subscribe path. No broker required: this
 * pins the routing <em>decision</em> (which topic name a type maps to), while the Testcontainers
 * {@code AbstractKafkaEventEngineTck} proves the end-to-end round-trip over that topic.
 */
@DisplayName("KafkaEventEngine.effectiveTopic — ADR-050 topic-override resolution")
class KafkaTopicResolutionTest {

    private static final int ORDINAL = 42;

    private static KafkaEventConfig configWithPrefix(String prefix) {
        return new KafkaEventConfig("localhost:9092", "unit", prefix, true, 0L, Duration.ofMillis(250L));
    }

    @Test
    @DisplayName("a spec with a topic override routes to the override topic (prefix applied)")
    void overrideWinsWithPrefix() {
        KafkaEventConfig config = configWithPrefix("exeris.");
        EventTypeSpec spec = EventTypeSpec.ofPersistent("OrderCreatedEvent", ORDINAL, "orders.created");

        assertThat(KafkaEventEngine.effectiveTopic(spec, config))
                .as("the override MUST be honoured and the config prefix still applied")
                .isEqualTo("exeris.orders.created");
    }

    @Test
    @DisplayName("a spec with no topic override falls back to the type-name-derived topic")
    void fallsBackToTypeName() {
        KafkaEventConfig config = configWithPrefix("exeris.");
        EventTypeSpec spec = EventTypeSpec.ofPersistent("OrderCreatedEvent", ORDINAL);

        assertThat(KafkaEventEngine.effectiveTopic(spec, config))
                .as("no override MUST fall back to prefix + type name")
                .isEqualTo("exeris.OrderCreatedEvent");
    }

    @Test
    @DisplayName("a blank topic is treated as no override (fallback to type name)")
    void blankTopicIsNoOverride() {
        KafkaEventConfig config = configWithPrefix(KafkaEventConfig.EMPTY_TOPIC_PREFIX);
        EventTypeSpec spec = EventTypeSpec.ofPersistent("OrderCreatedEvent", ORDINAL, "   ");

        assertThat(KafkaEventEngine.effectiveTopic(spec, config))
                .as("a blank override is not a real override — fall back to the type name")
                .isEqualTo("OrderCreatedEvent");
    }
}
