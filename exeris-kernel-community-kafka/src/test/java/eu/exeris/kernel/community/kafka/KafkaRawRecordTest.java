/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.community.kafka.KafkaLoopTestSupport.CallLog;
import eu.exeris.kernel.community.kafka.KafkaLoopTestSupport.RecordingConsumer;
import eu.exeris.kernel.community.kafka.KafkaLoopTestSupport.RecordingProducer;
import eu.exeris.kernel.spi.events.EventEngineConfig;
import eu.exeris.kernel.spi.events.EventTypeSpec;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static eu.exeris.kernel.community.kafka.KafkaLoopTestSupport.ORDINAL;
import static eu.exeris.kernel.community.kafka.KafkaLoopTestSupport.TOPIC;
import static eu.exeris.kernel.community.kafka.KafkaLoopTestSupport.TYPE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The raw record channel: headerless records reach the handler untouched, sends are written as given,
 * and an asynchronous raw send failure is counted by the channel alone and stops a manual-commit loop.
 */
@DisplayName("Kafka engine — raw record channel")
class KafkaRawRecordTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final String RAW = "raw.headerless";

    private final CallLog log = new CallLog();
    private KafkaEventEngine engine;

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    private static ConsumerRecord<byte[], byte[]> rawRecord(String topic, int partition, long offset,
                                                             byte[] key, byte[] value) {
        return new ConsumerRecord<>(topic, partition, offset, 1_700_000_000_123L, TimestampType.CREATE_TIME,
                key == null ? -1 : key.length, value == null ? -1 : value.length, key, value,
                new RecordHeaders(), Optional.empty());
    }

    private RecordingConsumer start(boolean manual, RecordingProducer producer) {
        RecordingConsumer consumer = new RecordingConsumer(log);
        engine = new KafkaEventEngine(EventEngineConfig.communityDefaults(),
                KafkaLoopTestSupport.config(KafkaLoopTestSupport.group(), manual, Duration.ofSeconds(5)),
                producer, _ -> consumer);
        return consumer;
    }

    @Test
    @DisplayName("a headerless value the framed decoder would misread reaches the handler intact")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void headerlessRecordReachesHandlerIntact() throws Exception {
        RecordingConsumer consumer = start(false, new RecordingProducer(log, true));
        byte[] value = new byte[1024];
        new Random(42L).nextBytes(value);
        byte[] key = {1, 2, 3};
        AtomicReference<ConsumerRecord<byte[], byte[]>> received = new AtomicReference<>();
        CountDownLatch handled = new CountDownLatch(1);
        engine.records().subscribe(RAW, consumerRecord -> {
            received.set(consumerRecord);
            handled.countDown();
        });
        consumer.deliverRecords(List.of(new TopicPartition(RAW, 1)), rawRecord(RAW, 1, 7L, key, value));
        engine.start();

        assertThat(handled.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();

        ConsumerRecord<byte[], byte[]> got = received.get();
        assertThat(got.topic()).isEqualTo(RAW);
        assertThat(got.partition()).isEqualTo(1);
        assertThat(got.offset()).isEqualTo(7L);
        assertThat(got.timestamp()).isEqualTo(1_700_000_000_123L);
        assertThat(got.key()).containsExactly(key);
        assertThat(got.value()).containsExactly(value);
        assertThat(engine.stats().processedTotal()).as("raw records are not counted").isZero();
        assertThat(engine.loopFailure()).isEmpty();
    }

    @Test
    @DisplayName("a null value is still delivered; the handler decides")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void nullValueIsDelivered() throws Exception {
        RecordingConsumer consumer = start(false, new RecordingProducer(log, true));
        AtomicReference<ConsumerRecord<byte[], byte[]>> received = new AtomicReference<>();
        CountDownLatch handled = new CountDownLatch(1);
        engine.records().subscribe(RAW, consumerRecord -> {
            received.set(consumerRecord);
            handled.countDown();
        });
        consumer.deliverRecords(List.of(new TopicPartition(RAW, 0)), rawRecord(RAW, 0, 0L, null, null));
        engine.start();

        assertThat(handled.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        assertThat(received.get().value()).isNull();
    }

    @Test
    @DisplayName("a framed event type on another topic keeps working next to a raw topic")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void framedTypeWorksAlongsideRawTopic() throws Exception {
        RecordingConsumer consumer = start(false, new RecordingProducer(log, true));
        engine.registry().register(EventTypeSpec.of(TYPE, ORDINAL));
        CountDownLatch framedHandled = new CountDownLatch(1);
        CountDownLatch rawHandled = new CountDownLatch(1);
        engine.bus().subscribe(TYPE, (descriptor, payload) -> {
            try (payload) {
                framedHandled.countDown();
            }
        });
        engine.records().subscribe(RAW, _ -> rawHandled.countDown());
        consumer.deliverRecords(List.of(new TopicPartition(TOPIC, 0), new TopicPartition(RAW, 0)),
                new ConsumerRecord<>(TOPIC, 0, 0L, null, KafkaLoopTestSupport.frame()),
                rawRecord(RAW, 0, 0L, null, new byte[] {9, 9, 9}));
        engine.start();

        assertThat(framedHandled.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        assertThat(rawHandled.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        assertThat(engine.stats().processedTotal()).as("only the framed record counts").isEqualTo(1L);
        assertThat(engine.loopFailure()).isEmpty();
    }

    @Test
    @DisplayName("a subscription made after start is picked up on the next iteration")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void subscribeAfterStart() throws Exception {
        RecordingConsumer consumer = start(false, new RecordingProducer(log, true));
        engine.start();
        assertThat(KafkaLoopTestSupport.await(() -> engine.loop().isRunning(), WAIT)).isTrue();
        CountDownLatch handled = new CountDownLatch(1);
        engine.records().subscribe(RAW, _ -> handled.countDown());
        // Scheduled only now: the loop subscribes before its next poll runs the task.
        consumer.deliverRecords(List.of(new TopicPartition(RAW, 0)), rawRecord(RAW, 0, 0L, null, new byte[] {1}));

        assertThat(handled.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("send writes the given value, key and partition and adds no header; a null callback works")
    void sendWritesRecordAsGiven() {
        Cluster cluster = clusterWith("raw.out", 4);
        MockProducer<byte[], byte[]> producer = new MockProducer<>(cluster, true, null,
                new ByteArraySerializer(), new ByteArraySerializer());
        engine = new KafkaEventEngine(EventEngineConfig.communityDefaults(),
                KafkaLoopTestSupport.config(KafkaLoopTestSupport.group(), false, Duration.ofSeconds(5)), producer);
        byte[] key = {4, 5};
        byte[] value = {6, 7, 8};
        List<RecordMetadata> acked = new CopyOnWriteArrayList<>();

        engine.records().send(new ProducerRecord<>("raw.out", 3, key, value), null);
        engine.records().send(new ProducerRecord<>("raw.out", 2, key, value),
                (metadata, exception) -> acked.add(metadata));

        List<ProducerRecord<byte[], byte[]>> history = producer.history();
        assertThat(history).hasSize(2);
        assertThat(history.get(0).topic()).isEqualTo("raw.out");
        assertThat(history.get(0).partition()).isEqualTo(3);
        assertThat(history.get(0).key()).containsExactly(key);
        assertThat(history.get(0).value()).containsExactly(value);
        assertThat(history.get(0).headers().toArray()).as("no header is added").isEmpty();
        assertThat(history.get(1).partition()).isEqualTo(2);
        assertThat(history.get(1).headers().toArray()).isEmpty();
        assertThat(acked).hasSize(1);
        assertThat(engine.records().sendFailedTotal()).isZero();
        assertThat(engine.sendFailureLatch().get()).isFalse();
    }

    @Test
    @DisplayName("partitionsFor returns the producer's partition count")
    void partitionsForReturnsCount() {
        MockProducer<byte[], byte[]> producer = new MockProducer<>(clusterWith("raw.out", 5), true, null,
                new ByteArraySerializer(), new ByteArraySerializer());
        engine = new KafkaEventEngine(EventEngineConfig.communityDefaults(),
                KafkaLoopTestSupport.config(KafkaLoopTestSupport.group(), false, Duration.ofSeconds(5)), producer);

        assertThat(engine.records().partitionsFor("raw.out")).isEqualTo(5);
    }

    @Test
    @DisplayName("an async raw send failure is counted by the channel only, then stops a manual-commit loop")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void asyncFailureCountsLocallyAndStopsManualLoop() throws Exception {
        RecordingProducer producer = new RecordingProducer(log, false);
        RecordingConsumer consumer = start(true, producer);
        AtomicLong countSeenByCallback = new AtomicLong(-1L);
        AtomicReference<Exception> callbackError = new AtomicReference<>();
        CountDownLatch callbackRan = new CountDownLatch(1);
        engine.records().subscribe(RAW, _ -> {
            engine.records().send(new ProducerRecord<>("raw.out", new byte[] {1}), (metadata, exception) -> {
                countSeenByCallback.set(engine.records().sendFailedTotal());
                callbackError.set(exception);
                callbackRan.countDown();
            });
            producer.errorNext(new KafkaException("broker unavailable"));
        });
        CountDownLatch dead = new CountDownLatch(1);
        engine.onLoopFailure(_ -> dead.countDown());
        consumer.deliverRecords(List.of(new TopicPartition(RAW, 0)), rawRecord(RAW, 0, 0L, null, new byte[] {1}));
        engine.start();

        assertThat(dead.await(WAIT.toSeconds(), TimeUnit.SECONDS)).as("the loop died").isTrue();

        assertThat(callbackRan.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        assertThat(callbackError.get()).isInstanceOf(KafkaException.class);
        assertThat(countSeenByCallback.get()).as("counted before the user callback runs").isEqualTo(1L);
        assertThat(engine.records().sendFailedTotal()).isEqualTo(1L);
        assertThat(engine.stats().publishFailedTotal()).as("SPI statistic unchanged").isZero();
        assertThat(engine.sendFailureLatch().get()).isTrue();
        assertThat(engine.loopFailure()).get().asString().contains("asynchronous");
        assertThat(log.snapshot()).doesNotContain("consumer.commitAsync", "consumer.commitSync");
    }

    @Test
    @DisplayName("manual commit: a batch of raw records is committed after its handlers returned")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void manualModeCommitsAfterRawBatch() throws Exception {
        RecordingConsumer consumer = start(true, new RecordingProducer(log, true));
        engine.records().subscribe(RAW, _ -> log.add("raw"));
        consumer.deliverRecords(List.of(new TopicPartition(RAW, 0)),
                rawRecord(RAW, 0, 0L, null, new byte[] {1}), rawRecord(RAW, 0, 1L, null, new byte[] {2}));
        engine.start();

        assertThat(log.awaitCall("consumer.commitAsync", WAIT)).isTrue();

        assertThat(log.snapshot()).startsWith("raw", "raw", "consumer.commitAsync");
        assertThat(engine.stats().processedTotal()).isZero();
    }

    private static Cluster clusterWith(String topic, int partitions) {
        Node node = new Node(0, "localhost", 1);
        List<PartitionInfo> infos = new ArrayList<>();
        for (int p = 0; p < partitions; p++) {
            infos.add(new PartitionInfo(topic, p, node, new Node[] {node}, new Node[] {node}));
        }
        return new Cluster("test", List.of(node), infos, Set.of(), Set.of());
    }
}
