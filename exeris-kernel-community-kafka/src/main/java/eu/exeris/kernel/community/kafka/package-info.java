/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */

/**
 * Community Kafka / Redpanda binding for the Exeris Events SPI (since 0.7.0).
 *
 * <h2>Module Boundary (ADR-008)</h2>
 * <p>This package and its sub-packages are the <em>only</em> place where
 * {@code org.apache.kafka.clients} types are allowed in the kernel reactor. The Core
 * orchestration layer ({@code eu.exeris.kernel.core.events.kafka}, planned for
 * Sprint 5b proper) defines narrow contracts that this binding implements with
 * the Kafka client; the SPI module ({@code eu.exeris.kernel.spi.events}) names
 * neither the client nor the orchestrator and remains implementation-blind.
 *
 * <h2>Why a Separate Module</h2>
 * <p>Single-node operators of {@code exeris-kernel-community} should NOT pay the
 * classpath / startup cost of {@code kafka-clients} (and its transitive
 * {@code zstd-jni}, {@code lz4-java}, {@code snappy-java}, {@code jackson-databind}
 * dependencies). Splitting the binding off into this submodule isolates that cost:
 * operators who need Kafka add {@code exeris-kernel-community-kafka} to the
 * classpath; everyone else gets the lean Community jar.
 *
 * <h2>Status (0.7 Sprint 5b1)</h2>
 * <p>This module ships as a build-system-ready skeleton: reactor wiring, BOM entry,
 * dependency declaration, and Testcontainers-Kafka test scope are in place. The
 * concrete {@code KafkaEventEngine}, {@code KafkaEventProvider}, session
 * orchestration, and {@code AbstractKafkaEventEngineTck} land in Sprint 5b proper
 * (EVENT-204 / EVENT-206) once the Core {@code KafkaSessionOrchestrator} interface
 * is finalised.
 *
 * @since 0.7.0
 */
package eu.exeris.kernel.community.kafka;
