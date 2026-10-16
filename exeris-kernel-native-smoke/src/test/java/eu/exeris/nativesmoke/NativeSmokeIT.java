/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.nativesmoke;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.util.List;

/**
 * Failsafe integration suite exercising the exeris-native-smoke native image across Community subsystems.
 */
@Testcontainers
class NativeSmokeIT {

    private static final String NEO4J_PASSWORD = "smokeneo4jpass";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container
    static final Neo4jContainer<?> NEO4J = new Neo4jContainer<>("neo4j:5-community")
            .withAdminPassword(NEO4J_PASSWORD);

    @TempDir
    Path tempDirectory;

    private static Path resolveImage() {
        String configuredPath = System.getProperty("native.smoke.image");
        if (configuredPath == null || configuredPath.isBlank()) {
            return Path.of("target/exeris-native-smoke");
        }
        return Path.of(configuredPath);
    }

    @Test
    void bootLocal() {
        NativeSmokeHarness.executeStep(
                resolveImage(),
                "boot-local",
                List.of("subsystems", "http1", "persistence", "jfr-custom"));
    }

    @Test
    void tls() {
        TlsTestCertificate certificate = TlsTestCertificate.generateInto(tempDirectory);
        NativeSmokeHarness.executeStep(
                resolveImage(),
                "tls",
                List.of("kernel-client", "jdk-http1", "jdk-http2", "recording-stream"),
                certificate.certificate().toString(),
                certificate.privateKey().toString());
    }

    @Test
    void roles() {
        NativeSmokeHarness.executeStep(
                resolveImage(),
                "roles",
                List.of("registry-present", "method-count"));
    }

    @Test
    void graphPostgresql() {
        NativeSmokeHarness.executeStep(
                resolveImage(),
                "graph",
                List.of("upsert", "bfs-depth-2", "shortest-path"),
                "postgresql",
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }

    @Test
    void graphNeo4j() {
        NativeSmokeHarness.executeStep(
                resolveImage(),
                "graph",
                List.of("upsert", "bfs-depth-2", "shortest-path"),
                "neo4j",
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                NEO4J.getBoltUrl(),
                "neo4j",
                NEO4J_PASSWORD);
    }

    @Test
    void startupMeasurement() {
        NativeSmokeHarness.executeStep(
                resolveImage(),
                "startup",
                List.of("bootstrap"));
    }
}
