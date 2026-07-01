/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.kafka;

import java.time.Duration;
import java.util.Objects;

/**
 * Community-Kafka-internal configuration for the Kafka {@code EventEngine} binding.
 *
 * <h2>The Wall</h2>
 * <p>This record is loaded only inside {@code exeris-kernel-community-kafka}. No
 * {@code org.apache.kafka.*} type appears in its surface — the Kafka client config
 * map is built inside the binding from these fields.
 *
 * <h2>Why a Separate Config Record</h2>
 * <p>The SPI {@link eu.exeris.kernel.spi.events.EventEngineConfig} stays implementation-blind;
 * Kafka-specific knobs (bootstrap servers, consumer group, topic prefix, ack policy) live here
 * so adding them does not pollute the SPI. The {@code KafkaEventProvider} reads the values
 * from the active {@link eu.exeris.kernel.spi.config.ConfigProvider} during bootstrap.
 *
 * @param bootstrapServers comma-separated {@code host:port} list (Kafka {@code bootstrap.servers});
 *                         must not be blank
 * @param consumerGroupId  consumer group id used for at-least-once delivery; must not be blank
 * @param topicPrefix      topic prefix prepended to the SPI event type name when computing the
 *                         topic to publish/subscribe to (e.g. {@code "exeris."} +
 *                         {@code "OrderPlaced"} → {@code "exeris.OrderPlaced"}); may be empty
 * @param requireAllAcks   when {@code true} (default), the producer is configured with
 *                         {@code acks=all} for at-least-once delivery; when {@code false},
 *                         {@code acks=1} (leader-only) trades durability for latency
 * @param producerLingerMs upper bound on producer batching latency; {@code 0} disables linger
 * @param consumerPollTimeout how long each {@code consumer.poll} call may block; bounded so
 *                            shutdown remains responsive even under empty topics
 *
 * @since 0.7.0
 */
public record KafkaEventConfig(
        String   bootstrapServers,
        String   consumerGroupId,
        String   topicPrefix,
        boolean  requireAllAcks,
        long     producerLingerMs,
        Duration consumerPollTimeout) {

    /** Empty topic prefix — events publish under the bare event-type name. */
    public static final String EMPTY_TOPIC_PREFIX = "";

    /**
     * Compact constructor — validates non-null / non-blank invariants eagerly so a
     * misconfigured engine fails at bootstrap rather than on the first publish.
     */
    public KafkaEventConfig {
        Objects.requireNonNull(bootstrapServers, "bootstrapServers must not be null");
        Objects.requireNonNull(consumerGroupId,  "consumerGroupId must not be null");
        Objects.requireNonNull(topicPrefix,      "topicPrefix must not be null — use EMPTY_TOPIC_PREFIX");
        Objects.requireNonNull(consumerPollTimeout, "consumerPollTimeout must not be null");
        if (bootstrapServers.isBlank()) {
            throw new IllegalArgumentException("bootstrapServers must not be blank");
        }
        if (consumerGroupId.isBlank()) {
            throw new IllegalArgumentException("consumerGroupId must not be blank");
        }
        if (producerLingerMs < 0) {
            throw new IllegalArgumentException(
                    "producerLingerMs must be >= 0, got: " + producerLingerMs);
        }
        if (consumerPollTimeout.isNegative() || consumerPollTimeout.isZero()) {
            throw new IllegalArgumentException(
                    "consumerPollTimeout must be > 0, got: " + consumerPollTimeout);
        }
    }

    /**
     * Returns a sensible Community default for a single-broker local development setup.
     *
     * @param bootstrapServers comma-separated {@code host:port} list
     * @param consumerGroupId  consumer group id
     */
    public static KafkaEventConfig defaults(String bootstrapServers, String consumerGroupId) {
        return new KafkaEventConfig(
                bootstrapServers,
                consumerGroupId,
                EMPTY_TOPIC_PREFIX,
                true,                // requireAllAcks — at-least-once by default
                5L,                  // producerLingerMs — 5 ms is the upstream Kafka default
                Duration.ofMillis(250L));
    }

    /**
     * The dedicated durable event-log topic (ADR-049) — a single topic keyed by {@code streamId}
     * so a stream's events (across types) stay totally ordered on one partition. Distinct from the
     * per-event-type outbox-delivery topics computed by {@link #topicFor(String)}.
     *
     * <p>Reserved name (hyphenated, unlike PascalCase event types) so it cannot collide with an
     * event-type topic.
     *
     * <p><b>Operational note:</b> per-stream ordering relies on a <b>stable partition count</b>.
     * Kafka's default partitioner routes {@code hash(key) % partitions}, so increasing this topic's
     * partition count after first use reroutes new records for existing streams onto a different
     * partition — old records stay put, so the stream's per-stream order (and thus replay) breaks.
     * Do not repartition the event-log topic once it is in use.
     *
     * @return the event-log topic name (prefixed by {@link #topicPrefix})
     */
    public String eventLogTopic() {
        return topicPrefix + "exeris-event-log";
    }

    /**
     * Computes the topic name for a given event type by joining {@link #topicPrefix} and the
     * event type name.
     *
     * @param eventType event type name as registered in {@code EventRegistry}
     * @return non-blank topic name
     */
    public String topicFor(String eventType) {
        Objects.requireNonNull(eventType, "eventType must not be null");
        if (eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        return topicPrefix + eventType;
    }
}
