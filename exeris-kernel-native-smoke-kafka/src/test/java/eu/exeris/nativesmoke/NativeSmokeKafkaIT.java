/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.nativesmoke;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.util.List;

/**
 * Failsafe integration suite exercising the exeris-native-smoke-kafka native image with a broker.
 */
@Testcontainers
class NativeSmokeKafkaIT {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    private static Path resolveImage() {
        String configuredPath = System.getProperty("native.smoke.image");
        if (configuredPath == null || configuredPath.isBlank()) {
            return Path.of("target/exeris-native-smoke-kafka");
        }
        return Path.of(configuredPath);
    }

    @Test
    void kafka() {
        NativeSmokeHarness.executeStep(
                resolveImage(),
                "kafka",
                List.of("provider", "roundtrip"),
                KAFKA.getBootstrapServers());
    }
}
