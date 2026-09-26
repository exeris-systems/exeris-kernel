/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.spi.events.EventBus;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventEngineConfig;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventQueue;
import eu.exeris.kernel.spi.events.EventTypeSpec;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.events.EventBusException;
import eu.exeris.kernel.spi.exceptions.events.EventEngineException;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit: {@link KafkaEventEngine}'s publish failures carry {@code EX-EVENT-6009} and
 * {@code rawArgs [eventTypeOrdinal, reason]}, and a push onto its local queue is refused as an
 * engine failure ({@code EX-EVENT-6001}), not a bus failure. A mocked {@link Producer} stands in for
 * the broker, so no Kafka instance is needed.
 */
@DisplayName("KafkaEventEngine — publish failures carry EX-EVENT-6009 (no broker)")
class KafkaEventEnginePublishFailureTest {

    private static final String TYPE = "KafkaPublishFailureEvent";
    private static final int REGISTERED_ORDINAL = 9_201;
    private static final int UNREGISTERED_ORDINAL = 9_202;

    private KafkaEventEngine engine;

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    @DisplayName("publish of an unregistered ordinal -> EX-EVENT-6009 [ordinal, unregistered-type], no send")
    void publishUnregisteredOrdinal() {
        Producer<byte[], byte[]> producer = mockProducer();
        engine = newEngine(producer);
        EventBus bus = engine.bus();
        EventDescriptor descriptor = descriptor(UNREGISTERED_ORDINAL);
        EventPayload payload = EventPayload.empty();

        EventBusException ex = assertThrows(EventBusException.class,
                () -> bus.publish(descriptor, payload));

        assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_EVENT_6009);
        assertThat(ex.rawArgs()).containsExactly(UNREGISTERED_ORDINAL, "unregistered-type");
        assertThat(ex.getCause()).isNull();
        verify(producer, never()).send(any());
    }

    @Test
    @DisplayName("publishAndAwait of an unregistered ordinal -> EX-EVENT-6009 [ordinal, unregistered-type]")
    void publishAndAwaitUnregisteredOrdinal() {
        engine = newEngine(mockProducer());
        EventBus bus = engine.bus();
        EventDescriptor descriptor = descriptor(UNREGISTERED_ORDINAL);
        EventPayload payload = EventPayload.empty();

        EventBusException ex = assertThrows(EventBusException.class,
                () -> bus.publishAndAwait(descriptor, payload));

        assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_EVENT_6009);
        assertThat(ex.rawArgs()).containsExactly(UNREGISTERED_ORDINAL, "unregistered-type");
    }

    @Test
    @DisplayName("publish whose send throws -> EX-EVENT-6009 [ordinal, delivery-failed], Kafka cause")
    @SuppressWarnings("unchecked")
    void publishSendThrows() {
        Producer<byte[], byte[]> producer = mockProducer();
        KafkaException broker = new KafkaException("broker disconnected");
        when(producer.send(any(ProducerRecord.class))).thenThrow(broker);
        engine = newEngine(producer);
        EventBus bus = engine.bus();
        EventDescriptor descriptor = descriptor(REGISTERED_ORDINAL);
        AtomicInteger closes = new AtomicInteger();
        EventPayload payload = trackingPayload(closes);

        EventBusException ex = assertThrows(EventBusException.class,
                () -> bus.publish(descriptor, payload));

        assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_EVENT_6009);
        assertThat(ex.rawArgs()).containsExactly(REGISTERED_ORDINAL, "delivery-failed");
        assertThat(ex.getCause()).isSameAs(broker);
        assertThat(closes.get()).as("the payload is released on the failure path").isEqualTo(1);
    }

    @Test
    @DisplayName("publishAndAwait whose send fails -> EX-EVENT-6009 [ordinal, delivery-failed], cause attached")
    @SuppressWarnings("unchecked")
    void publishAndAwaitSendFails() throws InterruptedException, ExecutionException {
        Producer<byte[], byte[]> producer = mockProducer();
        Future<RecordMetadata> future = mock(Future.class);
        ExecutionException failed = new ExecutionException(new KafkaException("not acknowledged"));
        when(future.get()).thenThrow(failed);
        when(producer.send(any(ProducerRecord.class))).thenReturn(future);
        engine = newEngine(producer);
        EventBus bus = engine.bus();
        EventDescriptor descriptor = descriptor(REGISTERED_ORDINAL);
        EventPayload payload = EventPayload.empty();

        EventBusException ex = assertThrows(EventBusException.class,
                () -> bus.publishAndAwait(descriptor, payload));

        assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_EVENT_6009);
        assertThat(ex.rawArgs()).containsExactly(REGISTERED_ORDINAL, "delivery-failed");
        assertThat(ex.getCause()).isSameAs(failed);
    }

    @Test
    @DisplayName("queue().push is refused as an engine failure: EventEngineException, EX-EVENT-6001")
    void queuePushIsRefusedAsEngineFailure() {
        engine = newEngine(mockProducer());
        EventQueue queue = engine.queue();
        EventDescriptor descriptor = descriptor(REGISTERED_ORDINAL);
        EventPayload payload = EventPayload.empty();

        EventEngineException ex = assertThrows(EventEngineException.class,
                () -> queue.push(descriptor, payload));

        assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_EVENT_6001);
        assertThat(ex.rawArgs()).isEmpty();
    }

    private KafkaEventEngine newEngine(Producer<byte[], byte[]> producer) {
        KafkaEventEngine created = new KafkaEventEngine(
                EventEngineConfig.communityDefaults(),
                KafkaEventConfig.defaults("localhost:9092", "unit"),
                producer);
        created.registry().register(EventTypeSpec.of(TYPE, REGISTERED_ORDINAL));
        return created;
    }

    @SuppressWarnings("unchecked")
    private static Producer<byte[], byte[]> mockProducer() {
        return mock(Producer.class);
    }

    private static EventDescriptor descriptor(int ordinal) {
        UUID id = UUID.randomUUID();
        return new EventDescriptor(
                id.getMostSignificantBits(), id.getLeastSignificantBits(),
                0L, 0L,
                ordinal,
                EventDescriptor.FLAG_ASYNC,
                System.currentTimeMillis());
    }

    private static EventPayload trackingPayload(AtomicInteger closes) {
        return new EventPayload() {
            @Override public MemorySegment segment() { return MemorySegment.NULL; }
            @Override public int length() { return 0; }
            @Override public int refCount() { return 1; }
            @Override public boolean isAlive() { return true; }
            @Override public void retain() { /* tracking only — the engine takes one reference */ }
            @Override public void close() { closes.incrementAndGet(); }
        };
    }
}
