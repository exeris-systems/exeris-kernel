/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.core.events.outbox.OutboxBrokerPort;
import eu.exeris.kernel.spi.events.EventDescriptor;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.function.IntFunction;

/**
 * Community Kafka adapter implementing Core's {@link OutboxBrokerPort}.
 *
 * <h2>Role</h2>
 * <p>The Outbox Orchestrator (Core) drains pending events from {@code OutboxEventStore} and
 * delegates physical delivery to a broker port. This adapter routes those entries through a
 * Kafka {@link Producer} so events that survived the database commit are published to the
 * shared event log with at-least-once semantics.
 *
 * <h2>The Wall</h2>
 * <p>The {@code Producer} interface is owned and configured outside this class
 * (typically by {@code KafkaEventEngine}); this port only sends and reports back the
 * prefix length per the {@link OutboxBrokerPort} contract. Core never sees
 * {@code org.apache.kafka.clients.*}.
 *
 * <h2>Failure Semantics</h2>
 * <p>Each entry is sent synchronously ({@code producer.send(record).get()}). On the first
 * failure the loop stops and returns the count of consecutively published entries — Core's
 * {@code OutboxOrchestrator} requeues / DLQ-routes the unsent suffix. This trade is intentional
 * for v0.7.0: it preserves the strictly-ordered prefix contract documented on
 * {@link OutboxBrokerPort#publish(List)} at the cost of pipelining throughput. A future
 * iteration may switch to async send + barrier flush once the orchestrator can absorb gaps.
 *
 * @since 0.7
 * @see OutboxBrokerPort
 * @see KafkaEventEngine
 */
public final class KafkaEventBrokerPort implements OutboxBrokerPort {

    private static final String BROKER_ID = "kafka";

    private final Producer<byte[], byte[]> producer;
    private final IntFunction<String>      ordinalToTopic;

    /**
     * Constructs a port that publishes outbox entries through {@code producer}, resolving each
     * entry's destination topic via {@code ordinalToTopic}.
     *
     * @param producer        a configured Kafka producer; the port does NOT close it
     * @param ordinalToTopic  resolves a registered event-type ordinal to its destination topic.
     *                        To stay consistent with the {@code KafkaEventEngine} publish path,
     *                        the resolver should honour the ADR-050 topic override — i.e. the
     *                        registered spec's {@code topic} when present, else the name-derived
     *                        default (e.g. {@code config.topicFor(spec.hasTopic() ? spec.topic() : spec.name())})
     */
    public KafkaEventBrokerPort(Producer<byte[], byte[]> producer,
                                IntFunction<String>      ordinalToTopic) {
        this.producer       = Objects.requireNonNull(producer, "producer must not be null");
        this.ordinalToTopic = Objects.requireNonNull(ordinalToTopic, "ordinalToTopic must not be null");
    }

    @Override
    public int publish(List<OutboxEntry> batch) {
        Objects.requireNonNull(batch, "batch must not be null");
        if (batch.isEmpty()) {
            throw new IllegalArgumentException("batch must not be empty");
        }
        int published = 0;
        for (OutboxEntry entry : batch) {
            if (!sendOne(entry)) {
                break;
            }
            published++;
        }
        return published;
    }

    @Override
    public String brokerId() {
        return BROKER_ID;
    }

    // PMD.AvoidCatchingGenericException — producer.send().get() may surface KafkaException;
    // broker port treats it generically (per-entry boolean contract, not exception propagation).
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private boolean sendOne(OutboxEntry entry) {
        EventDescriptor descriptor = entry.descriptor();
        String topic = ordinalToTopic.apply(descriptor.eventTypeOrdinal());
        if (topic == null || topic.isBlank()) {
            // No registered name — treat as a soft failure; orchestrator will DLQ on max-retries.
            return false;
        }
        byte[] key   = KafkaEventCodec.streamKey(descriptor);
        byte[] value = KafkaEventCodec.encode(descriptor, entry.payload());
        ProducerRecord<byte[], byte[]> producerRecord = new ProducerRecord<>(topic, key, value);
        try {
            producer.send(producerRecord).get();
            return true;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | RuntimeException _) {
            return false;
        }
    }
}
