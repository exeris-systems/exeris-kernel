/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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

import java.util.UUID;

/**
 * Community binding for {@link AbstractKafkaEventEngineTck} using a real Kafka 3.x broker
 * via Testcontainers.
 *
 * <p>The shared {@link KafkaContainer} is bootstrapped once per class; each test gets a fresh
 * {@link KafkaEventEngine} with a uniquely-suffixed consumer group id so back-to-back tests
 * do not see each other's offsets.
 *
 * <p>Tagged {@code @Tag("integration")} so default Surefire runs skip it; opt in with
 * {@code mvn -pl exeris-kernel-community-kafka test -Dgroups=integration}.
 *
 * @since 0.7.0
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Community :: Kafka — KafkaEventEngine TCK (Testcontainers Kafka 3.x)")
class CommunityKafkaEventEngineTckIT extends AbstractKafkaEventEngineTck {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @Override
    protected EventEngine createEngine() {
        EventEngineConfig spi = EventEngineConfig.communityDefaults();
        KafkaEventConfig kafka = KafkaEventConfig.defaults(
                KAFKA.getBootstrapServers(),
                "exeris-kafka-tck-" + UUID.randomUUID());
        return KafkaEventProvider.create(spi, kafka);
    }
}
