/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

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
 * @param producerOverrides Kafka producer properties applied after the binding's own, so an entry
 *                          wins over them; must not carry {@code bootstrap.servers},
 *                          {@code key.serializer}, {@code value.serializer} or
 *                          {@code transactional.id}; defensively copied; may be empty
 * @param consumerOverrides Kafka consumer properties applied after the binding's own, so an entry
 *                          wins over them; must not carry {@code bootstrap.servers},
 *                          {@code group.id}, {@code key.deserializer}, {@code value.deserializer}
 *                          or {@code transactional.id}; defensively copied; may be empty
 * @param clientId          Kafka {@code client.id} for both clients; empty means not set, and a
 *                          {@code client.id} in an override map wins over it
 * @param loopThread        the kind of thread the consumer loop runs on
 * @param closeTimeout      the bound on each blocking step of an engine shutdown (the final
 *                          offset commit, the consumer close, the producer close), and the base of
 *                          the time {@code close()} waits for the loop thread; must be positive
 *                          ({@value #DEFAULT_CLOSE_TIMEOUT_MS} ms by default)
 *
 * @since 0.7
 */
public record KafkaEventConfig(
        String   bootstrapServers,
        String   consumerGroupId,
        String   topicPrefix,
        boolean  requireAllAcks,
        long     producerLingerMs,
        Duration consumerPollTimeout,
        Map<String, String> producerOverrides,
        Map<String, String> consumerOverrides,
        String   clientId,
        LoopThread loopThread,
        Duration closeTimeout) {

    /**
     * The kind of thread the consumer loop runs on.
     *
     * @since 0.13
     */
    public enum LoopThread {
        /** A virtual thread (the default). */
        VIRTUAL,
        /** A daemon platform thread. */
        PLATFORM
    }

    /** Empty topic prefix — events publish under the bare event-type name. */
    public static final String EMPTY_TOPIC_PREFIX = "";

    /** Default {@link #closeTimeout()} in milliseconds. @since 0.13 */
    public static final long DEFAULT_CLOSE_TIMEOUT_MS = 30_000L;

    private static final Set<String> SHARED_FORBIDDEN =
            Set.of("bootstrap.servers", "transactional.id");
    private static final Set<String> PRODUCER_FORBIDDEN =
            Set.of("key.serializer", "value.serializer");
    private static final Set<String> CONSUMER_FORBIDDEN =
            Set.of("key.deserializer", "value.deserializer", "group.id");

    /**
     * Creates a configuration with no property overrides, no client id, a virtual consumer-loop
     * thread and the default close timeout.
     *
     * @param bootstrapServers    comma-separated {@code host:port} list
     * @param consumerGroupId     consumer group id
     * @param topicPrefix         topic prefix; may be empty
     * @param requireAllAcks      whether the producer uses {@code acks=all}
     * @param producerLingerMs    producer linger bound in milliseconds
     * @param consumerPollTimeout consumer poll timeout
     */
    public KafkaEventConfig(String bootstrapServers, String consumerGroupId, String topicPrefix,
                            boolean requireAllAcks, long producerLingerMs,
                            Duration consumerPollTimeout) {
        this(bootstrapServers, consumerGroupId, topicPrefix, requireAllAcks, producerLingerMs,
                consumerPollTimeout, Map.of(), Map.of(), "", LoopThread.VIRTUAL,
                Duration.ofMillis(DEFAULT_CLOSE_TIMEOUT_MS));
    }

    /**
     * Compact constructor — validates non-null / non-blank invariants eagerly so a
     * misconfigured engine fails at bootstrap rather than on the first publish.
     */
    public KafkaEventConfig {
        Objects.requireNonNull(bootstrapServers, "bootstrapServers must not be null");
        Objects.requireNonNull(consumerGroupId,  "consumerGroupId must not be null");
        Objects.requireNonNull(topicPrefix,      "topicPrefix must not be null — use EMPTY_TOPIC_PREFIX");
        Objects.requireNonNull(consumerPollTimeout, "consumerPollTimeout must not be null");
        Objects.requireNonNull(producerOverrides, "producerOverrides must not be null");
        Objects.requireNonNull(consumerOverrides, "consumerOverrides must not be null");
        Objects.requireNonNull(clientId,          "clientId must not be null — use the empty string");
        Objects.requireNonNull(loopThread,        "loopThread must not be null");
        Objects.requireNonNull(closeTimeout,      "closeTimeout must not be null");
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
        if (closeTimeout.isNegative() || closeTimeout.isZero()) {
            throw new IllegalArgumentException(
                    "closeTimeout must be > 0, got: " + closeTimeout);
        }
        producerOverrides = Map.copyOf(producerOverrides);
        consumerOverrides = Map.copyOf(consumerOverrides);
        rejectForbidden("producerOverrides", producerOverrides, PRODUCER_FORBIDDEN);
        rejectForbidden("consumerOverrides", consumerOverrides, CONSUMER_FORBIDDEN);
    }

    private static void rejectForbidden(String component, Map<String, String> overrides,
                                        Set<String> specific) {
        for (String key : new TreeSet<>(overrides.keySet())) {
            if (SHARED_FORBIDDEN.contains(key) || specific.contains(key)) {
                throw new IllegalArgumentException(
                        component + " must not contain '" + key + "' — the binding owns it");
            }
        }
    }

    /**
     * Returns a sensible Community default for a single-broker local development setup.
     *
     * @param bootstrapServers comma-separated {@code host:port} list
     * @param consumerGroupId  consumer group id
     * @return a {@link KafkaEventConfig} with an empty topic prefix, {@code requireAllAcks=true},
     *         a 5&nbsp;ms producer linger, and a 250&nbsp;ms consumer poll timeout
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
     * Returns a copy with the given producer property overrides.
     *
     * @param overrides producer properties; validated and copied
     * @return the new configuration
     * @since 0.13
     */
    public KafkaEventConfig withProducerOverrides(Map<String, String> overrides) {
        return new KafkaEventConfig(bootstrapServers, consumerGroupId, topicPrefix, requireAllAcks,
                producerLingerMs, consumerPollTimeout, overrides, consumerOverrides, clientId,
                loopThread, closeTimeout);
    }

    /**
     * Returns a copy with the given consumer property overrides.
     *
     * @param overrides consumer properties; validated and copied
     * @return the new configuration
     * @since 0.13
     */
    public KafkaEventConfig withConsumerOverrides(Map<String, String> overrides) {
        return new KafkaEventConfig(bootstrapServers, consumerGroupId, topicPrefix, requireAllAcks,
                producerLingerMs, consumerPollTimeout, producerOverrides, overrides, clientId,
                loopThread, closeTimeout);
    }

    /**
     * Returns a copy with the given Kafka {@code client.id}.
     *
     * @param newClientId the client id; empty means not set
     * @return the new configuration
     * @since 0.13
     */
    public KafkaEventConfig withClientId(String newClientId) {
        return new KafkaEventConfig(bootstrapServers, consumerGroupId, topicPrefix, requireAllAcks,
                producerLingerMs, consumerPollTimeout, producerOverrides, consumerOverrides,
                newClientId, loopThread, closeTimeout);
    }

    /**
     * Returns a copy with the given consumer-loop thread kind.
     *
     * @param newLoopThread the thread kind
     * @return the new configuration
     * @since 0.13
     */
    public KafkaEventConfig withLoopThread(LoopThread newLoopThread) {
        return new KafkaEventConfig(bootstrapServers, consumerGroupId, topicPrefix, requireAllAcks,
                producerLingerMs, consumerPollTimeout, producerOverrides, consumerOverrides,
                clientId, newLoopThread, closeTimeout);
    }

    /**
     * Returns a copy with the given shutdown bound.
     *
     * @param newCloseTimeout the bound on each blocking step of a shutdown; must be positive
     * @return the new configuration
     * @since 0.13
     */
    public KafkaEventConfig withCloseTimeout(Duration newCloseTimeout) {
        return new KafkaEventConfig(bootstrapServers, consumerGroupId, topicPrefix, requireAllAcks,
                producerLingerMs, consumerPollTimeout, producerOverrides, consumerOverrides,
                clientId, loopThread, newCloseTimeout);
    }

    /**
     * Describes this configuration without printing override values, which can carry secrets
     * such as {@code sasl.jaas.config}: only the override key names appear.
     *
     * @return a description with override key names only
     */
    @Override
    public String toString() {
        return "KafkaEventConfig[bootstrapServers=" + bootstrapServers
                + ", consumerGroupId=" + consumerGroupId
                + ", topicPrefix=" + topicPrefix
                + ", requireAllAcks=" + requireAllAcks
                + ", producerLingerMs=" + producerLingerMs
                + ", consumerPollTimeout=" + consumerPollTimeout
                + ", producerOverrideKeys=" + new TreeSet<>(producerOverrides.keySet())
                + ", consumerOverrideKeys=" + new TreeSet<>(consumerOverrides.keySet())
                + ", clientId=" + clientId
                + ", loopThread=" + loopThread
                + ", closeTimeout=" + closeTimeout + ']';
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
