/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
/**
 * Community Kafka / Redpanda binding for the Exeris Events SPI.
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
 * <h2>Status</h2>
 * <p>The Kafka driver is a working binding: {@link KafkaEventConfig},
 * {@link KafkaEventCodec} (48-byte fixed wire header + payload tail),
 * {@link KafkaEventBrokerPort} (Core {@code OutboxBrokerPort} adapter — built but not
 * yet wired into a runtime path; reserved for the outbox-driven delivery follow-up),
 * {@link KafkaEventEngine} ({@code KafkaPublishBus} + virtual-thread {@code ConsumerLoop}
 * + {@code NoOpQueue}), and {@link KafkaEventProvider} (ServiceLoader, priority 50 —
 * outranks the in-memory Community provider at 0, stays below the Enterprise tier slot at 100;
 * see {@link KafkaEventProvider#PRIORITY}). Coverage:
 * {@link eu.exeris.kernel.tck.contract.events.AbstractKafkaEventEngineTck}
 * (publish/consume roundtrip + idempotent close, Testcontainers Kafka 3.x).
 *
 * <p>The durable event-log bindings {@link KafkaEventStreamAppender} and
 * {@link KafkaEventStreamReader} (ADR-049 append-with-expected-version + per-stream ordering,
 * {@code @since 0.10}) are implemented, including replay by wall-clock timestamp and by
 * committed version; see {@code docs/subsystems/events.md}.
 *
 * <h2>Commit Modes</h2>
 * <p>The consumer loop auto-commits by default, which is at-most-once on consume. With
 * {@code enable.auto.commit=false} in {@link KafkaEventConfig#consumerOverrides()} it delivers each
 * record synchronously and commits after every handled batch, which is at-least-once on
 * consumption; it does not cover the output a handler produces. A failure ends the loop with a
 * recorded cause ({@link KafkaEventEngine#loopFailure()}) and the batch is not committed.
 *
 * <h2>Raw Records</h2>
 * <p>{@link KafkaEventEngine#records()} returns a {@link KafkaRecordChannel} for topics whose records
 * carry no Exeris header: {@link KafkaRecordHandler} receives the Kafka {@code ConsumerRecord}
 * as delivered, and the channel sends records as given. It bypasses the in-memory event bus and
 * therefore the ADR-101 isolation scoping and the Exeris wire header; a topic of a registered event
 * type or the event-log topic is refused in both directions. Handlers run on the consumer loop thread,
 * and ordering is per partition. The channel's rebalance hooks observe partition-owned state; they
 * are not a coordination mechanism. Off unless used.
 *
 * <h2>Deferred</h2>
 * <ul>
 *   <li>Outbox-orchestrator-driven delivery wiring on top of {@link KafkaEventBrokerPort}.</li>
 *   <li>TCK coverage of consumer-rebalance behavior under in-flight choreography. The raw channel's
 *       rebalance hooks only report assignment changes; they add no such coverage.</li>
 * </ul>
 *
 * @since 0.7
 */
package eu.exeris.kernel.community.kafka;
