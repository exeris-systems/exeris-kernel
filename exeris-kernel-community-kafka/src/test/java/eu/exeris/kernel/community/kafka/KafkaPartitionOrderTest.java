/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.community.kafka.KafkaLoopTestSupport.CallLog;
import eu.exeris.kernel.community.kafka.KafkaLoopTestSupport.RecordingConsumer;
import eu.exeris.kernel.community.kafka.KafkaLoopTestSupport.RecordingProducer;
import eu.exeris.kernel.spi.events.EventEngineConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Raw records arrive on the loop thread, one at a time, in offset order within each partition. */
@DisplayName("Kafka engine — raw records keep partition order on the loop thread")
class KafkaPartitionOrderTest {

    private static final String RAW = "raw.ordered";
    private static final int PARTITIONS = 3;
    private static final int PER_PARTITION = 20;

    private final CallLog log = new CallLog();
    private KafkaEventEngine engine;

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    @DisplayName("auto commit: strictly increasing offsets per partition, all on the loop thread")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void autoMode() throws Exception {
        run(false);
        assertThat(log.snapshot()).doesNotContain("consumer.commitAsync", "consumer.commitSync");
    }

    @Test
    @DisplayName("manual commit: the same, and the batch commit follows the last handler")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void manualMode() throws Exception {
        run(true);
        assertThat(log.awaitCall("consumer.commitAsync", Duration.ofSeconds(10))).isTrue();

        List<String> calls = log.snapshot();
        int commit = calls.indexOf("consumer.commitAsync");
        assertThat(commit).isEqualTo(PARTITIONS * PER_PARTITION);
        assertThat(calls.subList(0, commit)).containsOnly("handler");
    }

    private void run(boolean manual) throws Exception {
        RecordingConsumer consumer = new RecordingConsumer(log);
        engine = new KafkaEventEngine(EventEngineConfig.communityDefaults(),
                KafkaLoopTestSupport.config(KafkaLoopTestSupport.group(), manual, Duration.ofSeconds(5)),
                new RecordingProducer(log, true), _ -> consumer);
        List<ConsumerRecord<byte[], byte[]>> seen = new CopyOnWriteArrayList<>();
        AtomicReference<Thread> offThread = new AtomicReference<>();
        CountDownLatch all = new CountDownLatch(PARTITIONS * PER_PARTITION);
        engine.records().subscribe(RAW, consumerRecord -> {
            if (!Thread.currentThread().equals(consumer.pollThread())) {
                offThread.set(Thread.currentThread());
            }
            seen.add(consumerRecord);
            log.add("handler");
            all.countDown();
        });
        List<TopicPartition> partitions = new ArrayList<>();
        List<ConsumerRecord<byte[], byte[]>> records = new ArrayList<>();
        for (int p = 0; p < PARTITIONS; p++) {
            partitions.add(new TopicPartition(RAW, p));
        }
        // Interleaved across partitions, as a broker fetch would deliver them.
        for (int offset = 0; offset < PER_PARTITION; offset++) {
            for (int p = 0; p < PARTITIONS; p++) {
                records.add(new ConsumerRecord<>(RAW, p, offset, null, new byte[] {(byte) offset}));
            }
        }
        @SuppressWarnings("unchecked")
        ConsumerRecord<byte[], byte[]>[] batch = records.toArray(new ConsumerRecord[0]);
        consumer.deliverRecords(partitions, batch);
        engine.start();

        assertThat(all.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(offThread.get()).as("every handler call ran on the loop thread").isNull();
        for (int p = 0; p < PARTITIONS; p++) {
            int partition = p;
            List<Long> offsets = seen.stream().filter(r -> r.partition() == partition)
                    .map(ConsumerRecord::offset).toList();
            assertThat(offsets).hasSize(PER_PARTITION).isSorted().doesNotHaveDuplicates();
        }
        assertThat(engine.loopFailure()).isEmpty();
    }
}
