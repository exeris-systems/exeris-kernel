/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.spi.events.EventBus;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventEngineConfig;
import eu.exeris.kernel.spi.events.EventEngineStats;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventTypeSpec;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.events.EventBusException;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit: how {@link KafkaEventEngine}'s publish treats a send the producer completes exceptionally.
 * A failure that arrives after {@link EventBus#publish} has returned is never thrown: it is recorded
 * as a {@code KafkaPublishFailedEvent} with {@code publishMode "publishAsync"} and counted in
 * {@link EventEngineStats#publishFailedTotal()}. A failure the producer reports on the calling thread,
 * before {@code send} returns, is refused as {@code EX-EVENT-6009} exactly like a synchronous throw.
 * A failure reported from another thread while {@code send} is still running is asynchronous too.
 * No broker is needed: the producers here stand in for the client.
 */
@DisplayName("KafkaEventEngine — an exceptional send is recorded after return, refused before it")
class KafkaEventEnginePublishAsyncFailureTest {

    private static final String EVENT = "eu.exeris.kernel.events.kafka.PublishFailed";
    private static final String TYPE = "KafkaPublishAsyncFailureEvent";
    private static final int ORDINAL = 9_301;

    private KafkaEventEngine engine;

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("publish returns; the failed send is a publishAsync JFR event and a counter of 1")
    void asyncSendFailureIsRecordedNotThrown() throws Exception {
        EventEngineConfig spiConfig = EventEngineConfig.communityDefaults();
        KafkaEventConfig kafkaConfig = KafkaEventConfig.defaults("localhost:9092", "unit");
        MockProducer<byte[], byte[]> producer =
                new MockProducer<>(false, null, new ByteArraySerializer(), new ByteArraySerializer());
        engine = new KafkaEventEngine(spiConfig, kafkaConfig, producer);
        engine.registry().register(EventTypeSpec.of(TYPE, ORDINAL));
        EventBus bus = engine.bus();

        BlockingQueue<RecordedEvent> seen = new LinkedBlockingQueue<>();
        try (RecordingStream rs = new RecordingStream()) {
            rs.enable(EVENT);
            rs.onEvent(EVENT, seen::add);
            rs.startAsync();

            bus.publish(descriptor(), EventPayload.empty());

            producer.errorNext(new KafkaException("broker unavailable"));

            RecordedEvent event = nextOwnEvent(seen);
            assertThat(event.getString("engineName")).isEqualTo(spiConfig.engineName());
            assertThat(event.getString("topic")).isEqualTo(kafkaConfig.topicFor(TYPE));
            assertThat(event.getInt("eventTypeOrdinal")).isEqualTo(ORDINAL);
            assertThat(event.getString("publishMode")).isEqualTo("publishAsync");
            assertThat(event.getString("exceptionClass")).isEqualTo(KafkaException.class.getName());
            assertThat(event.getString("exceptionMessage")).contains("broker unavailable");
        }

        EventEngineStats stats = engine.stats();
        assertThat(stats.publishedTotal()).as("publish accepted the record").isEqualTo(1L);
        assertThat(stats.publishFailedTotal()).as("one asynchronous failure is counted").isEqualTo(1L);
        assertThat(stats.failedTotal()).as("handler errors are counted separately").isZero();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("a rejection reported on another thread during send is recorded, not thrown")
    void completionOnAnotherThreadDuringSendIsRecordedNotThrown() throws Exception {
        KafkaException failure = new KafkaException("broker unavailable");
        Producer<byte[], byte[]> producer = mock(Producer.class);
        when(producer.send(any(ProducerRecord.class), any(Callback.class))).thenAnswer(invocation -> {
            Callback callback = invocation.getArgument(1);
            Thread io = new Thread(() -> callback.onCompletion(null, failure), "fake-kafka-io");
            io.start();
            io.join();
            return CompletableFuture.failedFuture(failure);
        });
        engine = new KafkaEventEngine(EventEngineConfig.communityDefaults(),
                KafkaEventConfig.defaults("localhost:9092", "unit"), producer);
        engine.registry().register(EventTypeSpec.of(TYPE, ORDINAL));
        EventBus bus = engine.bus();

        BlockingQueue<RecordedEvent> seen = new LinkedBlockingQueue<>();
        try (RecordingStream rs = new RecordingStream()) {
            rs.enable(EVENT);
            rs.onEvent(EVENT, seen::add);
            rs.startAsync();

            assertThatCode(() -> bus.publish(descriptor(), EventPayload.empty()))
                    .as("a failure from another thread is asynchronous: publish must not throw")
                    .doesNotThrowAnyException();

            RecordedEvent event = nextOwnEvent(seen);
            assertThat(event.getString("publishMode")).isEqualTo("publishAsync");
        }

        EventEngineStats stats = engine.stats();
        assertThat(stats.publishedTotal()).as("publish accepted the record").isEqualTo(1L);
        assertThat(stats.publishFailedTotal()).as("the failure is counted once").isEqualTo(1L);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("a send rejected on the calling thread -> EX-EVENT-6009 thrown; no async record, no count")
    void synchronousRejectionIsRefusedNotRecorded() throws Exception {
        RecordTooLargeException rejected = new RecordTooLargeException("record too large");
        Producer<byte[], byte[]> producer = rejectingOnCallingThread(rejected);
        engine = new KafkaEventEngine(EventEngineConfig.communityDefaults(),
                KafkaEventConfig.defaults("localhost:9092", "unit"), producer);
        engine.registry().register(EventTypeSpec.of(TYPE, ORDINAL));
        EventBus bus = engine.bus();

        BlockingQueue<RecordedEvent> seen = new LinkedBlockingQueue<>();
        try (RecordingStream rs = new RecordingStream()) {
            rs.enable(EVENT);
            rs.onEvent(EVENT, seen::add);
            rs.startAsync();

            EventBusException ex = assertThrows(EventBusException.class,
                    () -> bus.publish(descriptor(), EventPayload.empty()));

            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_EVENT_6009);
            assertThat(ex.rawArgs()).containsExactly(ORDINAL, "delivery-failed");
            assertThat(ex.getCause()).isSameAs(rejected);

            // The synchronous refusal emits the "publish" mode event; events arrive in emission
            // order, so once it is seen no "publishAsync" event can still be on its way.
            List<String> modes = new ArrayList<>();
            String mode;
            do {
                mode = nextOwnEvent(seen).getString("publishMode");
                modes.add(mode);
            } while (!"publish".equals(mode));
            assertThat(modes).doesNotContain("publishAsync");
        }

        EventEngineStats stats = engine.stats();
        assertThat(stats.publishedTotal()).as("a refused publish is not accepted").isZero();
        assertThat(stats.publishFailedTotal()).as("a refused publish is not an async failure").isZero();
    }

    /**
     * Waits for the next {@code KafkaPublishFailedEvent} this test's engine emitted, identified by
     * its event-type ordinal, so the assertions do not depend on other events in the JVM.
     */
    private static RecordedEvent nextOwnEvent(BlockingQueue<RecordedEvent> seen) throws InterruptedException {
        while (true) {
            RecordedEvent event = seen.poll(10, TimeUnit.SECONDS);
            assertThat(event).as("a KafkaPublishFailedEvent MUST be recorded").isNotNull();
            if (event.getInt("eventTypeOrdinal") == ORDINAL) {
                return event;
            }
        }
    }

    /**
     * A producer that behaves as {@code KafkaProducer.doSend} does for an {@code ApiException}: it
     * completes the callback with the exception on the calling thread, then returns a failed future.
     * {@link MockProducer} cannot stand in for this path, because its {@code sendException} is thrown
     * from {@code send} and never reaches the callback.
     */
    @SuppressWarnings("unchecked")
    private static Producer<byte[], byte[]> rejectingOnCallingThread(RecordTooLargeException rejected) {
        Producer<byte[], byte[]> producer = mock(Producer.class);
        when(producer.send(any(ProducerRecord.class), any(Callback.class))).thenAnswer(invocation -> {
            Callback callback = invocation.getArgument(1);
            callback.onCompletion(null, rejected);
            return CompletableFuture.failedFuture(rejected);
        });
        return producer;
    }

    private static EventDescriptor descriptor() {
        UUID id = UUID.randomUUID();
        return new EventDescriptor(
                id.getMostSignificantBits(), id.getLeastSignificantBits(),
                0L, 0L, ORDINAL, EventDescriptor.FLAG_ASYNC, System.currentTimeMillis());
    }
}
