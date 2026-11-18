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
 *       concurrently with itself or with another raw record handler. In manual-commit mode that
 *       holds for event handlers too; in auto-commit mode event handlers run on their own virtual
 *       threads and can overlap it;</li>
 *   <li>it must not block for long: it holds up polling, and a handler that runs past
 *       {@code max.poll.interval.ms} gets the consumer evicted from its group;</li>
 *   <li>an exception thrown from it ends the consumer loop (see
 *       {@link KafkaEventEngine#loopFailure()}). In manual-commit mode the batch is not committed
 *       and the record is delivered again to the next consumer of the group. In auto-commit mode
 *       the Kafka client commits consumed positions on its own schedule and when the consumer
 *       closes, so the failing batch is committed all the same: that mode is at-most-once;</li>
 *   <li>its failures are not counted in {@code EventEngineStats.failedTotal}, which counts events,
 *       and its records are not counted in {@code processedTotal};</li>
 *   <li>the kernel's {@code ScopedValue} bindings are not present on that thread.</li>
 * </ul>
 *
 * <p>The record's value may be {@code null} (a tombstone) or empty; the handler decides what that
 * means.
 *
 * @since 0.13
 */
@FunctionalInterface
public interface KafkaRecordHandler {

    /**
     * Handles one record.
     *
     * @param consumerRecord the consumed record, with its topic, partition, offset, key, value, headers and
     *               timestamp
     */
    void onRecord(ConsumerRecord<byte[], byte[]> consumerRecord);
}
