/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.community.kafka.KafkaLoopTestSupport.CallLog;
import eu.exeris.kernel.community.kafka.KafkaLoopTestSupport.RecordingConsumer;
import eu.exeris.kernel.community.kafka.KafkaLoopTestSupport.RecordingProducer;
import eu.exeris.kernel.spi.events.EventEngineConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rebalance hooks of the raw channel. {@code MockConsumer} (kafka-clients 4.4.0) calls the subscribed
 * {@link ConsumerRebalanceListener} from {@code rebalance(...)} (revoked for the partitions that left, then
 * assigned for the ones that arrived, the delta only), but it has no way to lose partitions. So
 * {@code onPartitionsLost} is driven by calling the listener the engine's loop passed to {@code subscribe}
 * (the real driver listener) from inside a poll task, which runs on the loop thread exactly where the
 * client would call it.
 */
@DisplayName("Kafka engine — raw channel rebalance hooks")
class KafkaRebalanceHookTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final String RAW = "raw.rebalance";
    private static final TopicPartition P0 = new TopicPartition(RAW, 0);
    private static final TopicPartition P1 = new TopicPartition(RAW, 1);

    private final CallLog log = new CallLog();
    private RecordingConsumer consumer;
    private KafkaEventEngine engine;

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    private void newEngine() {
        consumer = new RecordingConsumer(log);
        engine = new KafkaEventEngine(EventEngineConfig.communityDefaults(),
                KafkaLoopTestSupport.config(KafkaLoopTestSupport.group(), false, Duration.ofSeconds(5)),
                new RecordingProducer(log, true), _ -> consumer);
        engine.records().subscribe(RAW, _ -> { });
    }

    private void startAndAwaitSubscribed() {
        engine.start();
        assertThat(KafkaLoopTestSupport.await(() -> consumer.driverListener() != null, WAIT))
                .as("the loop subscribed with its driver listener").isTrue();
    }

    /** Runs {@code task} inside one poll on the loop thread and waits for it. */
    private void onLoopThread(Runnable task) throws InterruptedException {
        CountDownLatch ran = new CountDownLatch(1);
        consumer.onNextPoll(() -> {
            task.run();
            ran.countDown();
        });
        assertThat(ran.await(WAIT.toSeconds(), TimeUnit.SECONDS)).as("poll task ran").isTrue();
    }

    @Test
    @DisplayName("assigned, revoked and lost reach the listener on the loop thread with their partitions")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void callbacksReachListener() throws Exception {
        newEngine();
        Recorder recorder = new Recorder();
        engine.records().rebalanceListener(recorder);
        startAndAwaitSubscribed();

        ConsumerRebalanceListener driver = consumer.driverListener();
        onLoopThread(() -> {
            consumer.rebalance(List.of(P0, P1));
            consumer.rebalance(List.of(P1));
            driver.onPartitionsLost(List.of(P1));
        });

        // MockConsumer also reports an empty "assigned" delta for the second rebalance; not under test.
        assertThat(recorder.events()).filteredOn(e -> !e.partitions().isEmpty()).containsExactly(
                new Event("assigned", Set.of(P0, P1)), new Event("revoked", Set.of(P0)), new Event("lost", Set.of(P1)));
        assertThat(recorder.threads()).containsOnly(consumer.pollThread());
    }

    @Test
    @DisplayName("lost does not fall back to revoked, and revoked commits nothing")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void lostIsNotRevokedAndNothingIsCommitted() throws Exception {
        newEngine();
        Recorder recorder = new Recorder();
        engine.records().rebalanceListener(recorder);
        startAndAwaitSubscribed();

        ConsumerRebalanceListener driver = consumer.driverListener();
        onLoopThread(() -> {
            driver.onPartitionsRevoked(List.of(P0));
            driver.onPartitionsLost(List.of(P1));
        });

        assertThat(recorder.events()).containsExactly(new Event("revoked", Set.of(P0)), new Event("lost", Set.of(P1)));
        assertThat(log.snapshot()).doesNotContain("consumer.commitAsync", "consumer.commitSync");
    }

    @Test
    @DisplayName("callbacks are harmless while no listener is set")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void noListenerIsANoOp() throws Exception {
        newEngine();
        startAndAwaitSubscribed();

        ConsumerRebalanceListener driver = consumer.driverListener();
        onLoopThread(() -> {
            driver.onPartitionsAssigned(List.of(P0));
            driver.onPartitionsRevoked(List.of(P0));
            driver.onPartitionsLost(List.of(P0));
        });

        assertThat(engine.loopFailure()).isEmpty();
    }

    @Test
    @DisplayName("a listener set after the assignment gets one replay of it, not a second")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void lateListenerGetsOneReplay() throws Exception {
        newEngine();
        startAndAwaitSubscribed();
        onLoopThread(() -> consumer.assignAll(List.of(P0, P1)));

        Recorder recorder = new Recorder();
        engine.records().rebalanceListener(recorder);
        assertThat(recorder.awaitEvents(1)).isTrue();
        // A task scheduled now runs in a later iteration than the replay, so a duplicate would be there.
        onLoopThread(() -> { });

        assertThat(recorder.events()).containsExactly(new Event("assigned", Set.of(P0, P1)));
        assertThat(recorder.threads()).containsOnly(consumer.pollThread());
    }

    @Test
    @DisplayName("a listener set before the assignment gets the callback and no replay on top")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void earlyListenerGetsNoReplay() throws Exception {
        newEngine();
        Recorder recorder = new Recorder();
        engine.records().rebalanceListener(recorder);
        startAndAwaitSubscribed();

        // MockConsumer.rebalance delivers the assigned callback itself, on the loop thread.
        onLoopThread(() -> consumer.assignAll(List.of(P0)));
        onLoopThread(() -> { });
        onLoopThread(() -> { });

        assertThat(recorder.events()).containsExactly(new Event("assigned", Set.of(P0)));
    }

    @Test
    @DisplayName("replacing the listener replays the assignment to the new one only")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void replacementListenerGetsReplay() throws Exception {
        newEngine();
        Recorder first = new Recorder();
        engine.records().rebalanceListener(first);
        startAndAwaitSubscribed();
        onLoopThread(() -> consumer.assignAll(List.of(P0)));
        assertThat(first.awaitEvents(1)).isTrue();

        Recorder second = new Recorder();
        engine.records().rebalanceListener(second);
        assertThat(second.awaitEvents(1)).isTrue();
        onLoopThread(() -> { });

        assertThat(second.events()).containsExactly(new Event("assigned", Set.of(P0)));
        assertThat(first.events()).containsExactly(new Event("assigned", Set.of(P0)));
    }

    @Test
    @DisplayName("an exception from the listener ends the loop with that cause")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void listenerExceptionEndsTheLoop() throws Exception {
        newEngine();
        IllegalStateException boom = new IllegalStateException("listener boom");
        engine.records().rebalanceListener(new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                throw boom;
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                // not under test
            }
        });
        CountDownLatch dead = new CountDownLatch(1);
        engine.onLoopFailure(_ -> dead.countDown());
        startAndAwaitSubscribed();

        ConsumerRebalanceListener driver = consumer.driverListener();
        consumer.onNextPoll(() -> driver.onPartitionsRevoked(List.of(P0)));

        assertThat(dead.await(WAIT.toSeconds(), TimeUnit.SECONDS)).as("the loop died").isTrue();
        assertThat(engine.loopFailure()).containsSame(boom);
        assertThat(engine.loop().isRunning()).isFalse();
    }

    private record Event(String kind, Set<TopicPartition> partitions) {
    }

    /** Records each callback as an {@link Event} together with the calling thread. */
    private static final class Recorder implements ConsumerRebalanceListener {
        private final List<Event> events = new CopyOnWriteArrayList<>();
        private final List<Thread> threads = new CopyOnWriteArrayList<>();

        @Override
        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            note("assigned", partitions);
        }

        @Override
        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            note("revoked", partitions);
        }

        @Override
        public void onPartitionsLost(Collection<TopicPartition> partitions) {
            note("lost", partitions);
        }

        private void note(String kind, Collection<TopicPartition> partitions) {
            threads.add(Thread.currentThread());
            events.add(new Event(kind, Set.copyOf(partitions)));
        }

        List<Event> events() {
            return List.copyOf(events);
        }

        List<Thread> threads() {
            return List.copyOf(threads);
        }

        boolean awaitEvents(int count) {
            return KafkaLoopTestSupport.await(() -> events.size() >= count, WAIT);
        }
    }
}
