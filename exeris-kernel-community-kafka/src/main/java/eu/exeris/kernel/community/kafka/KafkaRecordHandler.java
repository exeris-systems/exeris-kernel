/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * Receives the records of a topic subscribed through {@link KafkaRecordChannel#subscribe}: records
 * that carry no Exeris wire header and are handed over exactly as the Kafka client delivered them.
 *
 * <p>Invocation contract:
 * <ul>
 *   <li>called on the consumer loop thread, synchronously, in offset order per partition, and never
 *       concurrently with itself or with any other handler of the engine;</li>
 *   <li>it must not block for long: it holds up polling, and a handler that runs past
 *       {@code max.poll.interval.ms} gets the consumer evicted from its group;</li>
 *   <li>an exception thrown from it ends the consumer loop without committing the batch (see
 *       {@link KafkaEventEngine#loopFailure()});</li>
 *   <li>the kernel's {@code ScopedValue} bindings are not present on that thread.</li>
 * </ul>
 *
 * <p>The record's value may be {@code null} (a tombstone) or empty; the handler decides what that
 * means.
 *
 * @since 0.13.2
 */
@FunctionalInterface
public interface KafkaRecordHandler {

    /**
     * Handles one record.
     *
     * @param record the consumed record, with its topic, partition, offset, key, value, headers and
     *               timestamp
     */
    void onRecord(ConsumerRecord<byte[], byte[]> record);
}
