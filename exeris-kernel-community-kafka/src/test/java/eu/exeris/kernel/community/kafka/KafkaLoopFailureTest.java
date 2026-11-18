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
import eu.exeris.kernel.spi.events.EventTypeSpec;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static eu.exeris.kernel.community.kafka.KafkaLoopTestSupport.ORDINAL;
import static eu.exeris.kernel.community.kafka.KafkaLoopTestSupport.TYPE;
import static org.assertj.core.api.Assertions.assertThat;

/** The loop-failure signal: its cause, its listeners, and its JFR event. */
@DisplayName("Kafka engine — loop failure signal")
class KafkaLoopFailureTest {

    private static final String EVENT = "eu.exeris.kernel.events.kafka.ConsumerLoopFailed";
    private static final Duration WAIT = Duration.ofSeconds(10);

    private final List<KafkaEventEngine> engines = new ArrayList<>();

    @AfterEach
    void tearDown() {
        engines.forEach(KafkaEventEngine::close);
    }

    private KafkaEventEngine failingEngine(String group, CallLog log, EventHandler handler,
                                           boolean failing) {
        RecordingConsumer consumer = new RecordingConsumer(log);
        KafkaEventEngine engine = new KafkaEventEngine(EventEngineConfig.communityDefaults(),
                KafkaLoopTestSupport.config(group, true, Duration.ofSeconds(5)),
                new RecordingProducer(log, true), _ -> consumer);
        engines.add(engine);
        engine.registry().register(EventTypeSpec.of(TYPE, ORDINAL));
        engine.bus().subscribe(TYPE, handler);
        if (failing) {
            consumer.deliver(KafkaLoopTestSupport.frame());
        }
        return engine;
    }

    private static EventHandler throwing() {
        return (descriptor, payload) -> {
            try (payload) {
                throw new IllegalStateException("handler boom");
            }
        };
    }

    @Test
    @DisplayName("a listener gets the cause once, after the consumer is closed")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void listenerRunsOnceAfterConsumerClose() throws Exception {
        CallLog log = new CallLog();
        KafkaEventEngine engine = failingEngine(KafkaLoopTestSupport.group(), log, throwing(), true);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Throwable> seen = new AtomicReference<>();
        CountDownLatch notified = new CountDownLatch(1);
        engine.onLoopFailure(cause -> {
            calls.incrementAndGet();
            seen.set(cause);
            log.add("listener");
            notified.countDown();
        });
        engine.start();

        assertThat(notified.await(10, TimeUnit.SECONDS)).isTrue();
        engine.close();

        assertThat(calls.get()).isEqualTo(1);
        assertThat(seen.get()).isSameAs(engine.loopFailure().orElseThrow());
        List<String> order = log.snapshot();
        assertThat(order.indexOf("consumer.close")).isGreaterThanOrEqualTo(0)
                .isLessThan(order.indexOf("listener"));
    }

    @Test
    @DisplayName("a listener registered after the failure is invoked immediately on the registering thread")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void lateListenerIsInvokedImmediately() throws Exception {
        KafkaEventEngine engine = failingEngine(KafkaLoopTestSupport.group(), new CallLog(), throwing(), true);
        CountDownLatch dead = new CountDownLatch(1);
        engine.onLoopFailure(_ -> dead.countDown());
        engine.start();
        assertThat(dead.await(10, TimeUnit.SECONDS)).isTrue();

        AtomicReference<Thread> thread = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        engine.onLoopFailure(_ -> {
            calls.incrementAndGet();
            thread.set(Thread.currentThread());
        });

        assertThat(calls.get()).isEqualTo(1);
        assertThat(thread.get()).isSameAs(Thread.currentThread());
    }

    @Test
    @DisplayName("a clean stop records no failure and emits no event; a failure emits one")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void jfrEventOnlyForFailure() throws Exception {
        String cleanGroup = KafkaLoopTestSupport.group();
        String failingGroup = KafkaLoopTestSupport.group();
        BlockingQueue<RecordedEvent> seen = new LinkedBlockingQueue<>();
        try (RecordingStream rs = new RecordingStream()) {
            rs.enable(EVENT);
            rs.onEvent(EVENT, seen::add);
            rs.startAsync();

            CallLog cleanLog = new CallLog();
            RecordingConsumer cleanConsumer = new RecordingConsumer(cleanLog);
            KafkaEventEngine clean = new KafkaEventEngine(EventEngineConfig.communityDefaults(),
                    KafkaLoopTestSupport.config(cleanGroup, true, Duration.ofSeconds(5)),
                    new RecordingProducer(cleanLog, true), _ -> cleanConsumer);
            engines.add(clean);
            clean.registry().register(EventTypeSpec.of(TYPE, ORDINAL));
            clean.start();
            assertThat(cleanConsumer.awaitFirstPoll(WAIT)).isTrue();
            clean.close();
            assertThat(clean.loopFailure()).isEmpty();

            KafkaEventEngine failing = failingEngine(failingGroup, new CallLog(), throwing(), true);
            failing.start();

            // Events arrive in emission order, so once the failing engine's is seen, a clean
            // engine's (which was emitted earlier, if at all) has been seen too.
            List<String> groups = new ArrayList<>();
            RecordedEvent event;
            do {
                event = seen.poll(10, TimeUnit.SECONDS);
                assertThat(event).as("the failing engine's event MUST be recorded").isNotNull();
                groups.add(event.getString("consumerGroupId"));
            } while (!failingGroup.equals(event.getString("consumerGroupId")));

            assertThat(groups).doesNotContain(cleanGroup);
            assertThat(event.getString("exceptionClass")).isNotBlank();
        }
    }
}
