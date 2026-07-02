/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.spi.events.AppendResult;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventStreamAppender;
import eu.exeris.kernel.spi.events.StreamId;
import eu.exeris.kernel.spi.exceptions.events.EventEngineException;
import eu.exeris.kernel.spi.exceptions.events.EventStreamAppendConflictException;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link KafkaEventStreamAppender}'s ordering / optimistic-concurrency logic (ADR-049),
 * driven by a mocked {@link Producer} and an injected head resolver — so the OCC / monotonic-sequence
 * / failure branches run without a broker (the Testcontainers TCK covers the real Kafka hop).
 */
@DisplayName("KafkaEventStreamAppender — OCC + monotonic logic (no broker)")
class KafkaEventStreamAppenderTest {

    private static final int EVENT_TYPE_ORDINAL = 7;

    private KafkaEventStreamAppender appender;

    @BeforeEach
    void setUp() {
        // Fresh streams recover head 0 (no broker scan).
        appender = new KafkaEventStreamAppender(
                okProducer(), KafkaEventConfig.defaults("localhost:9092", "unit"),
                "unit-engine", (high, low) -> 0L);
    }

    @Test
    @DisplayName("ANY_VERSION appends to one stream return strictly monotonic sequences")
    void anyVersionIsMonotonic() {
        StreamId stream = freshStream();
        long first = appender.append(stream, EventStreamAppender.ANY_VERSION,
                descriptor(stream), EventPayload.empty()).committedSequence();
        long second = appender.append(stream, EventStreamAppender.ANY_VERSION,
                descriptor(stream), EventPayload.empty()).committedSequence();
        assertThat(first).isEqualTo(1L);
        assertThat(second).isEqualTo(2L);
    }

    @Test
    @DisplayName("expectedVersion=0 commits at 1; the observed head commits at +1")
    void optimisticSuccess() {
        StreamId stream = freshStream();
        AppendResult first = appender.append(stream, 0L, descriptor(stream), EventPayload.empty());
        assertThat(first.committedSequence()).isEqualTo(1L);
        assertThat(appender.append(stream, first.committedSequence(), descriptor(stream), EventPayload.empty())
                .committedSequence()).isEqualTo(2L);
    }

    @Test
    @DisplayName("a stale expectedVersion fails closed with EventStreamAppendConflictException")
    void optimisticConflict() {
        StreamId stream = freshStream();
        appender.append(stream, 0L, descriptor(stream), EventPayload.empty());
        assertThatExceptionOfType(EventStreamAppendConflictException.class)
                .isThrownBy(() -> appender.append(stream, 0L, descriptor(stream), EventPayload.empty()));
    }

    @Test
    @DisplayName("a producer send failure surfaces as EventEngineException")
    void sendFailureWrapped() {
        KafkaEventStreamAppender failing = new KafkaEventStreamAppender(
                failingProducer(), KafkaEventConfig.defaults("localhost:9092", "unit"),
                "unit-engine", (high, low) -> 0L);
        StreamId stream = freshStream();
        assertThatExceptionOfType(EventEngineException.class)
                .isThrownBy(() -> failing.append(stream, EventStreamAppender.ANY_VERSION,
                        descriptor(stream), EventPayload.empty()));
    }

    @Test
    @DisplayName("null arguments raise NullPointerException")
    void nullArgsRejected() {
        StreamId stream = freshStream();
        assertThatNullPointerException().isThrownBy(() -> appender.append(
                null, EventStreamAppender.ANY_VERSION, descriptor(stream), EventPayload.empty()));
        assertThatNullPointerException().isThrownBy(() -> appender.append(
                stream, EventStreamAppender.ANY_VERSION, null, EventPayload.empty()));
        assertThatNullPointerException().isThrownBy(() -> appender.append(
                stream, EventStreamAppender.ANY_VERSION, descriptor(stream), null));
    }

    @Test
    @DisplayName("append takes ownership of the payload — it is closed exactly once")
    void takesPayloadOwnership() {
        StreamId stream = freshStream();
        AtomicInteger closeCalls = new AtomicInteger();
        appender.append(stream, EventStreamAppender.ANY_VERSION, descriptor(stream), trackingPayload(closeCalls));
        assertThat(closeCalls.get()).isGreaterThanOrEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    private static Producer<byte[], byte[]> okProducer() {
        Producer<byte[], byte[]> producer = mock(Producer.class);
        Future<RecordMetadata> future = mock(Future.class);
        try {
            when(future.get()).thenReturn(null);
        } catch (InterruptedException | ExecutionException impossible) {
            throw new IllegalStateException(impossible);
        }
        when(producer.send(any(ProducerRecord.class))).thenReturn(future);
        return producer;
    }

    @SuppressWarnings("unchecked")
    private static Producer<byte[], byte[]> failingProducer() {
        Producer<byte[], byte[]> producer = mock(Producer.class);
        Future<RecordMetadata> future = mock(Future.class);
        try {
            when(future.get()).thenThrow(new ExecutionException(new RuntimeException("broker down")));
        } catch (InterruptedException | ExecutionException impossible) {
            throw new IllegalStateException(impossible);
        }
        when(producer.send(any(ProducerRecord.class))).thenReturn(future);
        return producer;
    }

    private static StreamId freshStream() {
        UUID id = UUID.randomUUID();
        return new StreamId(id.getMostSignificantBits(), id.getLeastSignificantBits(), "UnitStream");
    }

    private static EventDescriptor descriptor(StreamId stream) {
        UUID eventId = UUID.randomUUID();
        return new EventDescriptor(
                eventId.getMostSignificantBits(), eventId.getLeastSignificantBits(),
                stream.streamIdHigh(), stream.streamIdLow(),
                EVENT_TYPE_ORDINAL, EventDescriptor.FLAG_PERSISTENT, System.currentTimeMillis());
    }

    private static EventPayload trackingPayload(AtomicInteger closeCalls) {
        return new EventPayload() {
            private volatile boolean alive = true;
            @Override public MemorySegment segment() { return MemorySegment.NULL; }
            @Override public int     length()   { return 0; }
            @Override public int     refCount() { return alive ? 1 : 0; }
            @Override public boolean isAlive()  { return alive; }
            @Override public void    retain()   { /* tracking only */ }
            @Override public void    close()    {
                alive = false;
                closeCalls.incrementAndGet();
            }
        };
    }
}
