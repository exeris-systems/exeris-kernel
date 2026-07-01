/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.LongPredicate;

/**
 * Log-authoritative bounded read over the durable event-log topic (ADR-049). The log itself is the
 * single source of truth for a stream's {@code committedSequence} — this helper reads it to end so
 * the appender can recover a stream's head and the reader can replay it. No separate durable head
 * store, no Kafka transactions.
 *
 * <h2>Scan semantics</h2>
 * <p>Assigns every partition of the log topic, seeks to the beginning, and polls until each
 * partition's position reaches the end offset captured at scan start, returning the matching frames.
 * A stream's records all share one partition (key = {@code streamId}), so per-stream frames come
 * back in offset order == append order == {@code committedSequence} order without a sort.
 *
 * <p><b>Community best-effort (ADR-049):</b> this is an O(topic) read per recovery/replay — correct
 * and simple, but not optimised for scale. A future advisory compacted-head checkpoint can make head
 * lookup O(1) without transactions (the log stays authoritative); deferred until a real Kafka
 * consumer needs it.
 *
 * @since 0.10.0
 */
final class KafkaEventLog {

    // Safety bound: stop after this many consecutive empty polls even if end-offset math is off,
    // so a scan can never hang the caller indefinitely.
    private static final int MAX_EMPTY_POLLS = 3;

    private KafkaEventLog() {
        // utility
    }

    /** Reads every frame for the given stream key, in ascending {@code committedSequence} order. */
    /* default */ static List<byte[]> readStreamFrames(KafkaEventConfig config, long streamIdHigh, long streamIdLow) {
        return read(config, high -> high == streamIdHigh, low -> low == streamIdLow);
    }

    /** Reads every frame in the log (offset order per partition); the caller orders across streams. */
    /* default */ static List<byte[]> readAllFrames(KafkaEventConfig config) {
        return read(config, high -> true, low -> true);
    }

    private static List<byte[]> read(KafkaEventConfig config, LongPredicate highMatch, LongPredicate lowMatch) {
        String topic = config.eventLogTopic();
        List<byte[]> out = new ArrayList<>();
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(consumerProps(config))) {
            List<PartitionInfo> partitions = consumer.partitionsFor(topic);
            if (partitions == null || partitions.isEmpty()) {
                return out; // topic not created yet → empty log
            }
            List<TopicPartition> assigned = new ArrayList<>(partitions.size());
            for (PartitionInfo info : partitions) {
                assigned.add(new TopicPartition(topic, info.partition()));
            }
            consumer.assign(assigned);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(assigned);
            consumer.seekToBeginning(assigned);
            drain(consumer, endOffsets, config.consumerPollTimeout(), highMatch, lowMatch, out);
        }
        return out;
    }

    private static void drain(KafkaConsumer<byte[], byte[]> consumer,
                              Map<TopicPartition, Long> endOffsets,
                              Duration pollTimeout,
                              LongPredicate highMatch,
                              LongPredicate lowMatch,
                              List<byte[]> out) {
        int emptyPolls = 0;
        while (!allReached(consumer, endOffsets)) {
            ConsumerRecords<byte[], byte[]> records = consumer.poll(pollTimeout);
            if (records.isEmpty()) {
                emptyPolls++;
                if (emptyPolls >= MAX_EMPTY_POLLS) {
                    return;
                }
                continue;
            }
            emptyPolls = 0;
            collectMatching(records, highMatch, lowMatch, out);
        }
    }

    private static void collectMatching(ConsumerRecords<byte[], byte[]> records,
                                        LongPredicate highMatch,
                                        LongPredicate lowMatch,
                                        List<byte[]> out) {
        for (ConsumerRecord<byte[], byte[]> record : records) {
            byte[] frame = record.value();
            if (frame == null || frame.length < KafkaEventLogCodec.HEADER_SIZE) {
                continue;
            }
            if (highMatch.test(KafkaEventLogCodec.decodeStreamIdHigh(frame))
                    && lowMatch.test(KafkaEventLogCodec.decodeStreamIdLow(frame))) {
                out.add(frame);
            }
        }
    }

    private static boolean allReached(KafkaConsumer<byte[], byte[]> consumer,
                                      Map<TopicPartition, Long> endOffsets) {
        for (Map.Entry<TopicPartition, Long> entry : endOffsets.entrySet()) {
            if (entry.getValue() > 0L && consumer.position(entry.getKey()) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    private static Properties consumerProps(KafkaEventConfig config) {
        Properties props = new Properties();
        props.put("bootstrap.servers",         config.bootstrapServers());
        props.put("group.id",                  "exeris-eventlog-scan-" + UUID.randomUUID());
        props.put("key.deserializer",          ByteArrayDeserializer.class.getName());
        props.put("value.deserializer",        ByteArrayDeserializer.class.getName());
        props.put("enable.auto.commit",        "false");
        props.put("auto.offset.reset",         "earliest");
        // Read-only scan: never auto-create the topic — a missing topic means an empty log.
        props.put("allow.auto.create.topics",  "false");
        return props;
    }
}
