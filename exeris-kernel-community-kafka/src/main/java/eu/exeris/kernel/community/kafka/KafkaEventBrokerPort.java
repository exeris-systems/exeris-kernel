/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.core.events.outbox.OutboxBrokerPort;
import eu.exeris.kernel.spi.events.EventDescriptor;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.nio.ByteBuffer;
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
 * @since 0.7.0
 * @see OutboxBrokerPort
 * @see KafkaEventEngine
 */
public final class KafkaEventBrokerPort implements OutboxBrokerPort {

    private static final String BROKER_ID = "kafka";

    private final Producer<byte[], byte[]> producer;
    private final IntFunction<String>      ordinalToTopic;

    /**
     * @param producer        a configured Kafka producer; the port does NOT close it
     * @param ordinalToTopic  resolves a registered event-type ordinal to its destination topic
     *                        (typically {@code KafkaEventConfig.topicFor(registry.nameOfOrdinal(o))})
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
        byte[] key   = streamKey(descriptor);
        byte[] value = KafkaEventCodec.encode(descriptor, entry.payload());
        ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(topic, key, value);
        try {
            producer.send(record).get();
            return true;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | RuntimeException _) {
            return false;
        }
    }

    /**
     * Returns the Kafka partition key — a 16-byte big-endian rendering of the stream UUID.
     * Same stream → same partition → ordered consumer delivery for a given saga / aggregate.
     */
    private static byte[] streamKey(EventDescriptor descriptor) {
        byte[] key = new byte[Long.BYTES * 2];
        ByteBuffer.wrap(key)
                .putLong(descriptor.streamIdHigh())
                .putLong(descriptor.streamIdLow());
        return key;
    }
}
