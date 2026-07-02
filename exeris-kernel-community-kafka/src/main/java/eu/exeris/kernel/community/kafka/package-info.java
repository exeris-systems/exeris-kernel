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
 * {@code org.apache.kafka.clients} types are allowed in the kernel reactor. Core's
 * {@code OutboxBrokerPort} contract is implemented here by {@link KafkaEventBrokerPort}
 * via an {@code IntFunction<String> ordinalToTopic} resolver — Core never sees a Kafka
 * type. The SPI module ({@code eu.exeris.kernel.spi.events}) is implementation-blind.
 *
 * <h2>Why a Separate Module</h2>
 * <p>Single-node operators of {@code exeris-kernel-community} should NOT pay the
 * classpath / startup cost of {@code kafka-clients} (and its transitive
 * {@code zstd-jni}, {@code lz4-java}, {@code snappy-java}, {@code jackson-databind}
 * dependencies). Splitting the binding off into this submodule isolates that cost:
 * operators who need Kafka add {@code exeris-kernel-community-kafka} to the
 * classpath; everyone else gets the lean Community jar.
 *
 * <h2>Status (0.7 Sprint 5b)</h2>
 * <p>The Kafka driver ships as a working binding: {@link KafkaEventConfig},
 * {@link KafkaEventCodec} (48-byte fixed wire header + payload tail),
 * {@link KafkaEventBrokerPort} (Core {@code OutboxBrokerPort} adapter — built but not
 * yet wired into a runtime path; reserved for the outbox-driven delivery follow-up),
 * {@link KafkaEventEngine} ({@code KafkaPublishBus} + virtual-thread {@code ConsumerLoop}
 * + {@code NoOpQueue}), and {@link KafkaEventProvider} (ServiceLoader, priority 100 —
 * outranks the in-memory Community provider). Coverage:
 * {@link eu.exeris.kernel.tck.contract.events.AbstractKafkaEventEngineTck}
 * (publish/consume roundtrip + idempotent close, Testcontainers Kafka 3.x).
 *
 * <p>The durable event-log bindings {@link KafkaEventStreamAppender} and
 * {@link KafkaEventStreamReader} (ADR-049 append-with-expected-version + per-stream ordering,
 * {@code @since 0.10.0}) are implemented; see {@code docs/subsystems/events.md}.
 *
 * <h2>Deferred</h2>
 * <ul>
 *   <li>Replay by wall-clock timestamp (offset-based tail read is implemented in the reader binding).</li>
 *   <li>Outbox-orchestrator-driven delivery wiring on top of {@link KafkaEventBrokerPort}.</li>
 *   <li>{@code enable.auto.commit=false} with manual commit-after-handler (today the
 *       loop runs auto-commit, which yields at-most-once semantics on consume).</li>
 *   <li>Consumer-rebalance behavior under in-flight choreography (TCK extension).</li>
 * </ul>
 *
 * @since 0.7.0
 */
package eu.exeris.kernel.community.kafka;
