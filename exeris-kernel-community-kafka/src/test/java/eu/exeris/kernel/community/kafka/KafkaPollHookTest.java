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
import org.apache.kafka.clients.consumer.RetriableCommitFailedException;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the after-poll hook of the raw record channel and for the failed-commit counter,
 * driven through a {@code MockConsumer}.
 */
@DisplayName("Kafka engine — after-poll hook and failed-commit counter")
class KafkaPollHookTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final String RAW = "raw.poll-hook";
    private static final TopicPartition PARTITION = new TopicPartition(RAW, 0);

    private final CallLog log = new CallLog();
    private KafkaEventEngine engine;

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    private RecordingConsumer build(boolean manual) {
        RecordingConsumer consumer = new RecordingConsumer(log);
        engine = new KafkaEventEngine(EventEngineConfig.communityDefaults(),
                KafkaLoopTestSupport.config(KafkaLoopTestSupport.group(), manual, Duration.ofSeconds(5)),
                new RecordingProducer(log, true), _ -> consumer);
        return consumer;
    }

    private static ConsumerRecord<byte[], byte[]> rawRecord(long offset) {
        return new ConsumerRecord<>(RAW, 0, offset, null, new byte[] {1});
    }

    @Test
    @DisplayName("the hook runs on the loop thread after polls that return no record")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void hookRunsOnEmptyPolls() throws Exception {
        RecordingConsumer consumer = build(true);
        AtomicInteger runs = new AtomicInteger();
        AtomicReference<Thread> hookThread = new AtomicReference<>();
        engine.records().subscribe(RAW, _ -> log.add("handler"));
        engine.records().afterPoll(() -> {
            hookThread.set(Thread.currentThread());
            runs.incrementAndGet();
        });
        engine.start();

        assertThat(KafkaLoopTestSupport.await(() -> runs.get() >= 3, WAIT))
                .as("the hook ran after several empty polls").isTrue();

        assertThat(log.contains("handler")).as("no record was delivered").isFalse();
        assertThat(hookThread.get()).isSameAs(consumer.pollThread());
        assertThat(engine.loopFailure()).isEmpty();
    }

    @Test
    @DisplayName("for a batch with records the order is handler, hook, commit")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void hookRunsBetweenHandlerAndCommit() throws Exception {
        RecordingConsumer consumer = build(true);
        AtomicBoolean handled = new AtomicBoolean();
        AtomicBoolean noted = new AtomicBoolean();
        engine.records().subscribe(RAW, _ -> {
            log.add("handler");
            handled.set(true);
        });
        // The hook runs after empty polls too; only its first run after the record is logged.
        engine.records().afterPoll(() -> {
            if (handled.get() && noted.compareAndSet(false, true)) {
                log.add("afterPoll");
            }
        });
        consumer.deliverRecords(List.of(PARTITION), rawRecord(0L));
        engine.start();

        assertThat(log.awaitCall("consumer.commitAsync", WAIT)).isTrue();

        assertThat(log.snapshot()).startsWith("handler", "afterPoll", "consumer.commitAsync");
    }

    @Test
    @DisplayName("a hook set after start is picked up by the running loop")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void hookSetAfterStartIsPickedUp() throws Exception {
        RecordingConsumer consumer = build(false);
        engine.records().subscribe(RAW, _ -> { });
        engine.start();
        assertThat(consumer.awaitFirstPoll(WAIT)).isTrue();
        CountDownLatch ran = new CountDownLatch(1);

        engine.records().afterPoll(ran::countDown);

        assertThat(ran.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("an exception from the hook ends the loop with that cause and the batch is not committed")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void throwingHookEndsTheLoopWithoutCommit() throws Exception {
        RecordingConsumer consumer = build(true);
        AtomicBoolean handled = new AtomicBoolean();
        IllegalStateException boom = new IllegalStateException("hook boom");
        engine.records().subscribe(RAW, _ -> handled.set(true));
        engine.records().afterPoll(() -> {
            if (handled.get()) {
                throw boom;
            }
        });
        CountDownLatch dead = new CountDownLatch(1);
        engine.onLoopFailure(_ -> dead.countDown());
        consumer.deliverRecords(List.of(PARTITION), rawRecord(0L));
        engine.start();

        assertThat(dead.await(WAIT.toSeconds(), TimeUnit.SECONDS)).as("the loop died").isTrue();

        assertThat(engine.loopFailure()).containsSame(boom);
        assertThat(engine.loop().isRunning()).isFalse();
        assertThat(log.snapshot()).doesNotContain("consumer.commitAsync", "consumer.commitSync");
    }

    @Test
    @DisplayName("a failed asynchronous commit is counted and does not end the loop")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void failedAsyncCommitIsCountedAndTheLoopGoesOn() throws Exception {
        RecordingConsumer consumer = build(true);
        CountDownLatch second = new CountDownLatch(2);
        engine.records().subscribe(RAW, _ -> second.countDown());
        consumer.failNextCommitAsync(new RetriableCommitFailedException("commit refused"));
        consumer.deliverRecords(List.of(PARTITION), rawRecord(0L));
        engine.start();

        assertThat(KafkaLoopTestSupport.await(() -> engine.commitFailedTotal() == 1L, WAIT))
                .as("the refused commit was counted").isTrue();
        consumer.deliverRecords(List.of(PARTITION), rawRecord(1L));

        assertThat(second.await(WAIT.toSeconds(), TimeUnit.SECONDS))
                .as("the loop handled a record after the failed commit").isTrue();
        assertThat(engine.loopFailure()).isEmpty();
        assertThat(engine.loop().isRunning()).isTrue();
        assertThat(engine.commitFailedTotal()).as("the second commit succeeded").isEqualTo(1L);
    }

    @Test
    @DisplayName("commitFailedTotal starts at zero and a null hook is rejected")
    void defaultsAndNullHook() {
        build(true);
        KafkaRecordChannel records = engine.records();

        assertThat(engine.commitFailedTotal()).isZero();
        assertThatThrownBy(() -> records.afterPoll(null)).isInstanceOf(NullPointerException.class);
    }
}
