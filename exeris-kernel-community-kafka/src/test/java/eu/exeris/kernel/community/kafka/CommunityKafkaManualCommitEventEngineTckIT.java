/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventEngineConfig;
import eu.exeris.kernel.tck.contract.events.AbstractKafkaEventEngineTck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.UUID;

/**
 * Second Community binding for {@link AbstractKafkaEventEngineTck}: the same unchanged contract,
 * against an engine in <em>manual-commit</em> mode ({@code enable.auto.commit=false} through
 * {@link KafkaEventConfig#consumerOverrides()}). In that mode the consumer loop delivers every
 * framed record with {@code publishAndAwait} on the poll thread and commits after each fully handled
 * batch, so this binding is what shows the SPI contract still holds when delivery moves onto the
 * loop thread.
 *
 * <p>Tagged {@code @Tag("integration")}; it runs only in the Kafka integration gate.
 *
 * @since 0.13.2
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Community :: Kafka — KafkaEventEngine TCK, manual-commit mode (Testcontainers Kafka 3.x)")
class CommunityKafkaManualCommitEventEngineTckIT extends AbstractKafkaEventEngineTck {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @Override
    protected EventEngine createEngine() {
        EventEngineConfig spi = EventEngineConfig.communityDefaults();
        KafkaEventConfig kafka = KafkaEventConfig.defaults(
                        KAFKA.getBootstrapServers(),
                        "exeris-kafka-tck-manual-" + UUID.randomUUID())
                .withConsumerOverrides(Map.of("enable.auto.commit", "false"));
        return KafkaEventProvider.create(spi, kafka);
    }
}
