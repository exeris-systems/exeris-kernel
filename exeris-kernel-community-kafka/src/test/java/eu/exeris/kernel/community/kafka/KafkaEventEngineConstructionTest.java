/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.spi.events.EventEngineConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit: {@link KafkaEventEngine}'s public constructor builds its own producer, and releases it on
 * {@code close()}. Kafka clients connect lazily — the producer on its first send, the consumer on
 * its first poll with a subscription — so neither reaches a broker here and none is needed.
 */
@DisplayName("KafkaEventEngine — public construction (no broker)")
class KafkaEventEngineConstructionTest {

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("the public constructor builds a startable engine that stops on close()")
    void publicConstructorBuildsAStartableEngine() {
        KafkaEventEngine engine = new KafkaEventEngine(
                EventEngineConfig.communityDefaults(),
                KafkaEventConfig.defaults("localhost:9092", "construction-unit"));
        engine.start();
        engine.close();

        assertThat(engine.loop().isRunning()).isFalse();
    }

    @Test
    @DisplayName("a null spiConfig is rejected before a producer is built")
    void nullSpiConfigRejected() {
        KafkaEventConfig kafkaConfig = KafkaEventConfig.defaults("localhost:9092", "construction-unit");
        EventEngineConfig spiConfig = null;

        assertThatNullPointerException()
                .isThrownBy(() -> new KafkaEventEngine(spiConfig, kafkaConfig))
                .withMessage("spiConfig");
    }
}
