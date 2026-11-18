/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.ProducerRecord;

/**
 * The raw record channel of a {@link KafkaEventEngine}: consume and produce on topics whose records
 * carry <em>no</em> Exeris header. Obtained from {@link KafkaEventEngine#records()}.
 *
 * <p>The channel bypasses the engine's in-memory event bus, and with it the ADR-101 isolation scoping
 * and the 48-byte Exeris wire header: a raw record is neither stamped nor scoped, and what it
 * contains is the application's responsibility. To keep that out of Exeris-framed topics, a topic
 * that belongs to a registered event type, or the event-log topic, can be neither subscribed nor sent
 * to through this channel.
 *
 * <p>These types deliberately expose {@code kafka-clients} types.
 *
 * @since 0.13
 */
public interface KafkaRecordChannel {

    /**
     * Subscribes {@code handler} to {@code topic}. The topic name is used literally: the
     * {@link KafkaEventConfig#topicPrefix() topic prefix} is not applied. There is one handler per
     * topic. May be called before or after {@link KafkaEventEngine#start()}; the consumer loop picks
     * the subscription up on its next iteration.
     *
     * @param topic   the Kafka topic, literally
     * @param handler receives every record of the topic on the consumer loop thread
     * @throws IllegalStateException if the topic already has a raw handler, is the effective topic of
     *                               a registered event type, or is the event-log topic
     */
    void subscribe(String topic, KafkaRecordHandler handler);

    /**
     * Sets the engine's rebalance listener, replacing any earlier one. May be called at any time,
     * before or after {@link KafkaEventEngine#start()}. Its methods are called on the consumer loop
     * thread. If partitions are already assigned when a listener is set, the loop calls
     * {@code onPartitionsAssigned} once with the current assignment on its next iteration, so a late
     * listener does not miss it.
     *
     * <p>The hooks are an observation surface for state owned by a partition, not a coordination
     * mechanism: the kernel commits nothing in {@code onPartitionsRevoked}. An exception thrown from
     * a listener method ends the consumer loop.
     *
     * @param listener the listener, never {@code null}
     */
    void rebalanceListener(ConsumerRebalanceListener listener);

    /**
     * Hands {@code producerRecord} to the engine's producer as it is: no Exeris header and no isolation header
     * is added. When the send completes exceptionally the channel counts it in
     * {@link #sendFailedTotal()} and sets the engine's send-failure latch (which, in manual-commit
     * mode, ends the consumer loop before its next commit), and only then invokes {@code callback}.
     *
     * @param producerRecord the record to send
     * @param callback invoked on completion; may be {@code null}
     * @throws IllegalArgumentException if the record's topic is the effective topic of a registered
     *                                  event type or the event-log topic
     */
    void send(ProducerRecord<byte[], byte[]> producerRecord, Callback callback);

    /**
     * Returns the number of raw sends that failed asynchronously. The counter belongs to the channel:
     * it is not part of the engine's published-failed statistic.
     *
     * @return the failed raw send count
     */
    long sendFailedTotal();

    /**
     * Returns the number of partitions of {@code topic}. May block up to the producer's
     * {@code max.block.ms} and, depending on the broker's settings, may auto-create the topic.
     *
     * @param topic the Kafka topic, literally
     * @return the partition count
     */
    int partitionsFor(String topic);
}
