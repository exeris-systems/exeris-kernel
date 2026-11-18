/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.spi.events.EventEngineConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The consumer loop runs on the thread kind {@link KafkaEventConfig#loopThread()} selects. The
 * consumer factory runs on the loop thread, so it records what kind of thread that is.
 */
@DisplayName("Kafka consumer loop — thread kind")
class KafkaConsumerLoopThreadTest {

    @Test
    @DisplayName("the loop runs on a virtual thread by default")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void virtualByDefault() throws Exception {
        Boolean virtual = loopThreadIsVirtual(KafkaEventConfig.defaults("localhost:1", group()));

        assertThat(virtual).isTrue();
    }

    @Test
    @DisplayName("the loop runs on a daemon platform thread when configured")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void platformWhenConfigured() throws Exception {
        KafkaEventConfig cfg = KafkaEventConfig.defaults("localhost:1", group())
                .withLoopThread(KafkaEventConfig.LoopThread.PLATFORM);

        assertThat(loopThreadIsVirtual(cfg)).isFalse();
    }

    private static String group() {
        return "loop-thread-" + UUID.randomUUID();
    }

    private static boolean loopThreadIsVirtual(KafkaEventConfig cfg) throws Exception {
        CountDownLatch built = new CountDownLatch(1);
        AtomicReference<Boolean> virtualSeen = new AtomicReference<>();
        AtomicReference<Boolean> daemon = new AtomicReference<>();
        try (KafkaEventEngine engine = new KafkaEventEngine(
                EventEngineConfig.communityDefaults(),
                cfg,
                new MockProducer<>(true, null, new ByteArraySerializer(), new ByteArraySerializer()),
                _ -> {
                    daemon.set(Thread.currentThread().isDaemon());
                    virtualSeen.set(Thread.currentThread().isVirtual());
                    built.countDown();
                    return new ParkingConsumer();
                })) {
            engine.start();
            assertThat(built.await(5, TimeUnit.SECONDS)).as("loop built its consumer").isTrue();
            boolean virtual = virtualSeen.get();
            if (!virtual) {
                assertThat(daemon.get()).as("a platform loop thread is a daemon").isTrue();
            }
            return virtual;
        }
    }

    /** A consumer with no records whose {@code poll} waits out its timeout instead of spinning. */
    private static final class ParkingConsumer extends MockConsumer<byte[], byte[]> {

        private ParkingConsumer() {
            super("earliest");
        }

        @Override
        public synchronized ConsumerRecords<byte[], byte[]> poll(Duration timeout) {
            LockSupport.parkNanos(timeout.toNanos());
            return super.poll(Duration.ZERO);
        }
    }
}
