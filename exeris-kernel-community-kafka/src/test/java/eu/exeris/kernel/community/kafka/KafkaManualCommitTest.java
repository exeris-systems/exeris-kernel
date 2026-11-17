/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.community.kafka.KafkaLoopTestSupport.CallLog;
import eu.exeris.kernel.community.kafka.KafkaLoopTestSupport.RecordingConsumer;
import eu.exeris.kernel.community.kafka.KafkaLoopTestSupport.RecordingProducer;
import eu.exeris.kernel.spi.events.EventEngineConfig;
import eu.exeris.kernel.spi.events.EventHandler;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventTypeSpec;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static eu.exeris.kernel.community.kafka.KafkaLoopTestSupport.ORDINAL;
import static eu.exeris.kernel.community.kafka.KafkaLoopTestSupport.TYPE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Manual-commit mode: the commit follows the handlers on the poll thread, a failure ends the loop
 * without committing the batch, and auto mode never commits by hand.
 */
@DisplayName("Kafka engine — manual commit after handlers")
class KafkaManualCommitTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private final CallLog log = new CallLog();
    private KafkaEventEngine engine;

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    private RecordingConsumer start(boolean manual, boolean autoCompleteProducer, EventHandler handler,
                                    RecordingProducer[] producerOut) {
        RecordingConsumer consumer = new RecordingConsumer(log);
        RecordingProducer producer = new RecordingProducer(log, autoCompleteProducer);
        if (producerOut != null) {
            producerOut[0] = producer;
        }
        engine = new KafkaEventEngine(EventEngineConfig.communityDefaults(),
                KafkaLoopTestSupport.config(KafkaLoopTestSupport.group(), manual, Duration.ofSeconds(5)),
                producer, _ -> consumer);
        engine.registry().register(EventTypeSpec.of(TYPE, ORDINAL));
        engine.bus().subscribe(TYPE, handler);
        return consumer;
    }

    @Test
    @DisplayName("the batch commit is observed only after the handlers, which run on the poll thread")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void commitFollowsHandlersOnThePollThread() throws Exception {
        AtomicReference<Thread> handlerThread = new AtomicReference<>();
        RecordingConsumer consumer = start(true, true, (descriptor, payload) -> {
            try (payload) {
                handlerThread.set(Thread.currentThread());
                log.add("handler");
            }
        }, null);
        consumer.deliver(KafkaLoopTestSupport.frame(), KafkaLoopTestSupport.frame());
        engine.start();

        assertThat(log.awaitCall("consumer.commitAsync", WAIT)).as("the batch is committed").isTrue();

        assertThat(log.snapshot()).startsWith("handler", "handler", "consumer.commitAsync");
        assertThat(handlerThread.get()).as("handler runs on the loop thread").isSameAs(consumer.pollThread());
        assertThat(engine.stats().processedTotal()).isEqualTo(2L);
        assertThat(engine.loopFailure()).isEmpty();
    }

    @Test
    @DisplayName("an empty batch commits nothing")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void emptyBatchCommitsNothing() throws Exception {
        RecordingConsumer consumer = start(true, true, (descriptor, payload) -> payload.close(), null);
        engine.start();
        assertThat(consumer.awaitFirstPoll(WAIT)).isTrue();
        // Two more polls have certainly happened once a later, non-empty one is committed.
        consumer.deliver(KafkaLoopTestSupport.frame());
        assertThat(log.awaitCall("consumer.commitAsync", WAIT)).isTrue();

        assertThat(log.snapshot()).containsOnlyOnce("consumer.commitAsync");
    }

    @Test
    @DisplayName("a throwing handler ends the loop with a cause, a failed count of 1 and no commit")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void handlerFailureEndsLoopWithoutCommit() throws Exception {
        RecordingConsumer consumer = start(true, true, (descriptor, payload) -> {
            try (payload) {
                throw new IllegalStateException("handler boom");
            }
        }, null);
        CountDownLatch dead = new CountDownLatch(1);
        engine.onLoopFailure(_ -> dead.countDown());
        consumer.deliver(KafkaLoopTestSupport.frame());
        engine.start();

        assertThat(dead.await(10, TimeUnit.SECONDS)).as("the loop died").isTrue();

        assertThat(engine.loopFailure()).isPresent();
        assertThat(engine.stats().failedTotal()).isEqualTo(1L);
        assertThat(engine.loop().isRunning()).isFalse();
        assertThat(log.snapshot()).doesNotContain("consumer.commitAsync", "consumer.commitSync");
    }

    @Test
    @DisplayName("an asynchronous send failure trips the latch: the batch is not committed, the loop stops")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void sendFailureLatchStopsTheLoop() throws Exception {
        RecordingProducer[] producerOut = new RecordingProducer[1];
        RecordingConsumer consumer = start(true, false, (descriptor, payload) -> {
            try (payload) {
                engine.bus().publish(KafkaLoopTestSupport.descriptor(), EventPayload.empty());
                producerOut[0].errorNext(new KafkaException("broker unavailable"));
            }
        }, producerOut);
        CountDownLatch dead = new CountDownLatch(1);
        engine.onLoopFailure(_ -> dead.countDown());
        consumer.deliver(KafkaLoopTestSupport.frame());
        engine.start();

        assertThat(dead.await(10, TimeUnit.SECONDS)).as("the loop died").isTrue();

        assertThat(engine.sendFailureLatch().get()).isTrue();
        assertThat(engine.stats().publishFailedTotal()).isEqualTo(1L);
        assertThat(engine.loopFailure()).get().asString().contains("asynchronous");
        assertThat(engine.stats().failedTotal()).as("no handler failed").isZero();
        assertThat(engine.loop().isRunning()).isFalse();
        assertThat(log.snapshot()).doesNotContain("consumer.commitAsync", "consumer.commitSync");
    }

    @Test
    @DisplayName("auto mode dispatches off the poll thread and never commits by hand")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void autoModeNeverCommitsAndDispatchesOffThread() throws Exception {
        AtomicReference<Thread> handlerThread = new AtomicReference<>();
        CountDownLatch handled = new CountDownLatch(1);
        RecordingConsumer consumer = start(false, true, (descriptor, payload) -> {
            try (payload) {
                handlerThread.set(Thread.currentThread());
                handled.countDown();
            }
        }, null);
        consumer.deliver(KafkaLoopTestSupport.frame());
        engine.start();

        assertThat(handled.await(10, TimeUnit.SECONDS)).isTrue();
        engine.close();

        assertThat(handlerThread.get()).isNotSameAs(consumer.pollThread());
        assertThat(handlerThread.get().isVirtual()).isTrue();
        assertThat(log.snapshot()).doesNotContain("consumer.commitAsync", "consumer.commitSync");
        assertThat(log.snapshot()).isEqualTo(List.of("producer.flush", "consumer.close", "producer.close"));
    }
}
