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
import eu.exeris.kernel.spi.events.EventLoop;
import eu.exeris.kernel.spi.events.EventTypeSpec;
import eu.exeris.kernel.spi.exceptions.events.EventEngineException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static eu.exeris.kernel.community.kafka.KafkaLoopTestSupport.ORDINAL;
import static eu.exeris.kernel.community.kafka.KafkaLoopTestSupport.TYPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The ordered, bounded shutdown: flush, commit, consumer close, producer close. */
@DisplayName("Kafka engine — shutdown order and lifecycle")
class KafkaCloseOrderTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private final CallLog log = new CallLog();
    private KafkaEventEngine engine;
    private RecordingConsumer consumer;

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    private void build(boolean manual, Duration closeTimeout, EventHandler handler) {
        consumer = new RecordingConsumer(log);
        engine = new KafkaEventEngine(EventEngineConfig.communityDefaults(),
                KafkaLoopTestSupport.config(KafkaLoopTestSupport.group(), manual, closeTimeout),
                new RecordingProducer(log, true), _ -> consumer);
        engine.registry().register(EventTypeSpec.of(TYPE, ORDINAL));
        if (handler != null) {
            engine.bus().subscribe(TYPE, handler);
        }
    }

    @Test
    @DisplayName("a clean manual-mode stop is flush, commitSync, consumer close, producer close")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void cleanManualStop() throws Exception {
        build(true, Duration.ofSeconds(5), null);
        engine.start();
        assertThat(consumer.awaitFirstPoll(WAIT)).isTrue();

        engine.close();

        assertThat(log.snapshot()).isEqualTo(
                List.of("producer.flush", "consumer.commitSync", "consumer.close", "producer.close"));
        assertThat(engine.loopFailure()).isEmpty();
        assertThat(engine.loop().isRunning()).isFalse();
    }

    @Test
    @DisplayName("auto mode is flush, consumer close, producer close, with no commitSync")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void autoStop() throws Exception {
        build(false, Duration.ofSeconds(5), null);
        engine.start();
        assertThat(consumer.awaitFirstPoll(WAIT)).isTrue();

        engine.close();

        assertThat(log.snapshot()).isEqualTo(List.of("producer.flush", "consumer.close", "producer.close"));
    }

    @Test
    @DisplayName("after a send failure the stop does not commit, and the loop reports the failure")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void sendFailureSuppressesTheFinalCommit() throws Exception {
        build(true, Duration.ofSeconds(5), null);
        engine.start();
        assertThat(consumer.awaitFirstPoll(WAIT)).isTrue();

        engine.sendFailureLatch().set(true);
        engine.close();

        assertThat(log.snapshot()).isEqualTo(List.of("producer.flush", "consumer.close", "producer.close"));
        assertThat(engine.loopFailure()).isPresent();
    }

    @Test
    @DisplayName("close() on an engine that was never started still closes the producer, once")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void neverStartedClosesProducer() {
        build(true, Duration.ofSeconds(5), null);

        engine.close();
        engine.close();

        assertThat(log.snapshot()).isEqualTo(List.of("producer.close"));
    }

    @Test
    @DisplayName("start() after close() throws IllegalStateException")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void startAfterCloseThrows() {
        build(true, Duration.ofSeconds(5), null);
        engine.close();

        assertThatThrownBy(engine::start).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("close() from inside a handler does not deadlock; the loop thread finishes the shutdown")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void closeFromAHandler() throws Exception {
        build(true, Duration.ofSeconds(5), (descriptor, payload) -> {
            try (payload) {
                log.add("handler");
                engine.close();
            }
        });
        consumer.deliver(KafkaLoopTestSupport.frame());
        engine.start();

        assertThat(log.awaitCall("producer.close", WAIT)).as("the producer is closed").isTrue();

        assertThat(log.snapshot()).isEqualTo(List.of("handler", "consumer.commitAsync", "producer.flush",
                "consumer.commitSync", "consumer.close", "producer.close"));
        assertThat(engine.loopFailure()).isEmpty();
    }

    @Test
    @DisplayName("a consumer whose close blocks past closeTimeout still lets close() return and close the producer")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void blockedConsumerCloseIsEscalated() throws Exception {
        build(true, Duration.ofMillis(100), null);
        CountDownLatch gate = new CountDownLatch(1);
        consumer.holdCloseUntil(gate);
        engine.start();
        assertThat(consumer.awaitFirstPoll(WAIT)).isTrue();

        try {
            engine.close();

            assertThat(log.contains("producer.close")).as("the producer is closed regardless").isTrue();
            assertThat(engine.loopFailure()).as("an escalation interrupt is not a failure").isEmpty();
        } finally {
            gate.countDown();
        }
    }

    @Test
    @DisplayName("start() is refused with EX-EVENT-6001 while a loop stopped from its own thread is still closing")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void startWhileTheLoopIsStillStoppingIsRefused() {
        build(true, Duration.ofSeconds(5), null);
        CountDownLatch gate = new CountDownLatch(1);
        consumer.holdCloseUntil(gate);
        EventLoop loop = engine.loop();
        // A stop from the loop thread only signals: it returns before the consumer is closed.
        consumer.onNextPoll(loop::stop);
        engine.start();

        try {
            assertThat(KafkaLoopTestSupport.await(() -> log.contains("consumer.close"), WAIT))
                    .as("the loop reached the held consumer close").isTrue();
            assertThat(loop.isRunning()).isFalse();

            assertThatThrownBy(loop::start)
                    .isInstanceOf(EventEngineException.class)
                    .hasMessageContaining("still stopping")
                    .extracting(e -> ((EventEngineException) e).errorCode())
                    .isEqualTo("EX-EVENT-6001");
            assertThat(loop.isRunning()).as("the refused start changed nothing").isFalse();
        } finally {
            gate.countDown();
        }
    }
}
