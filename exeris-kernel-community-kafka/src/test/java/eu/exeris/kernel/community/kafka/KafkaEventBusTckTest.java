/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventEngineConfig;
import eu.exeris.kernel.tck.contract.events.AbstractEventBusTck;
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
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Community binding for {@link AbstractEventBusTck} over the Kafka engine's bus, with no broker.
 *
 * <p>A {@link MockProducer} that completes every send stands in for the broker's acknowledgement,
 * and the consumer loop runs over a {@link MockConsumer} that holds no records, so starting the
 * engine reaches no broker and no handler is ever invoked. The bus reports itself brokered, which
 * skips the five cases that assert in-process fan-out; the cases every bus owes run here, in the
 * default build.
 */
@DisplayName("Community Kafka: EventBus TCK (mock producer and consumer, no broker)")
class KafkaEventBusTckTest extends AbstractEventBusTck {

    private final CountDownLatch consumerBuilt = new CountDownLatch(1);
    private KafkaEventEngine engine;

    @Override
    protected EventEngine createEngine() {
        engine = new KafkaEventEngine(
                EventEngineConfig.communityDefaults(),
                KafkaEventConfig.defaults("localhost:1", "exeris-kafka-bus-tck-" + UUID.randomUUID()),
                new MockProducer<>(true, null, new ByteArraySerializer(), new ByteArraySerializer()),
                _ -> {
                    consumerBuilt.countDown();
                    return new ParkingConsumer();
                });
        return engine;
    }

    @Test
    @DisplayName("the Kafka bus is brokered, and its started loop polls a consumer that reaches no broker")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void busIsBrokeredAndLoopUsesTheInjectedConsumer() throws InterruptedException {
        assertThat(engine.bus().isBrokered())
                .as("the Kafka bus MUST report itself brokered — it selects the cases this binding skips")
                .isTrue();
        assertThat(consumerBuilt.await(5, TimeUnit.SECONDS))
                .as("the started consumer loop MUST build its consumer through the injected factory")
                .isTrue();
    }

    /**
     * A consumer with no records whose {@code poll} waits out its timeout, as a broker-backed
     * consumer does when nothing arrives; returning at once would turn the loop into a busy spin.
     */
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
