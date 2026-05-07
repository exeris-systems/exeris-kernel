/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.core.events.outbox.OutboxBrokerPort;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventPayload;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link KafkaEventBrokerPort} — the load-bearing OutboxBrokerPort contract is
 * "publish the successful prefix and stop on first failure".
 */
@DisplayName("KafkaEventBrokerPort — outbox prefix-on-failure semantics")
class KafkaEventBrokerPortTest {

    private static final int ORDINAL_OK   = 1;
    private static final int ORDINAL_MISS = 999_999;

    @Test
    @DisplayName("happy path — every send succeeds; published count == batch size")
    @SuppressWarnings("unchecked")
    void allSucceed() {
        Producer<byte[], byte[]> producer = mock(Producer.class);
        when(producer.send(any(ProducerRecord.class))).thenReturn(succeededFuture());

        KafkaEventBrokerPort port = new KafkaEventBrokerPort(producer, ord -> "topic-" + ord);

        int published = port.publish(List.of(entry(ORDINAL_OK), entry(ORDINAL_OK), entry(ORDINAL_OK)));

        assertThat(published).isEqualTo(3);
        verify(producer, org.mockito.Mockito.times(3)).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("third send fails — published == 2 (strict prefix; suffix not attempted)")
    @SuppressWarnings("unchecked")
    void thirdSendFailsReturnsTwo() {
        Producer<byte[], byte[]> producer = mock(Producer.class);
        AtomicInteger callsBeforeFailure = new AtomicInteger(0);
        when(producer.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            int n = callsBeforeFailure.incrementAndGet();
            return n < 3 ? succeededFuture() : failedFuture();
        });

        KafkaEventBrokerPort port = new KafkaEventBrokerPort(producer, ord -> "topic-" + ord);

        int published = port.publish(List.of(
                entry(ORDINAL_OK), entry(ORDINAL_OK), entry(ORDINAL_OK), entry(ORDINAL_OK)));

        assertThat(published).as("strict prefix — only the two successes count").isEqualTo(2);
        // The fourth entry was never sent — the loop exits on the first failure.
        verify(producer, org.mockito.Mockito.times(3)).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("unresolved ordinal stops the loop without calling the producer")
    @SuppressWarnings("unchecked")
    void unresolvedTopicStopsLoop() {
        Producer<byte[], byte[]> producer = mock(Producer.class);
        // ordinalToTopic returns null for ORDINAL_MISS — the loop must stop without sending.
        KafkaEventBrokerPort port = new KafkaEventBrokerPort(
                producer, ord -> ord == ORDINAL_OK ? "topic-ok" : null);

        int published = port.publish(List.of(entry(ORDINAL_MISS), entry(ORDINAL_OK)));

        assertThat(published).isZero();
        verify(producer, org.mockito.Mockito.never()).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("brokerId() identifies the adapter")
    void brokerIdConstant() {
        Producer<byte[], byte[]> producer = mock(Producer.class);
        KafkaEventBrokerPort port = new KafkaEventBrokerPort(producer, ord -> "topic-" + ord);
        assertThat(port.brokerId()).isEqualTo("kafka");
    }

    @Test
    @DisplayName("empty batch is rejected — IllegalArgumentException per OutboxBrokerPort contract")
    void emptyBatchRejected() {
        Producer<byte[], byte[]> producer = mock(Producer.class);
        KafkaEventBrokerPort port = new KafkaEventBrokerPort(producer, ord -> "topic-" + ord);
        assertThatThrownBy(() -> port.publish(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static OutboxBrokerPort.OutboxEntry entry(int ordinal) {
        UUID eventId  = UUID.randomUUID();
        UUID streamId = UUID.randomUUID();
        EventDescriptor descriptor = EventDescriptor.of(
                eventId.getMostSignificantBits(),  eventId.getLeastSignificantBits(),
                streamId.getMostSignificantBits(), streamId.getLeastSignificantBits(),
                ordinal, EventDescriptor.FLAG_PERSISTENT, 0L);
        return new OutboxBrokerPort.OutboxEntry(descriptor, EventPayload.empty());
    }

    private static Future<RecordMetadata> succeededFuture() {
        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition("topic", 0), 0L, 0, 0L, 0, 0);
        return CompletableFuture.completedFuture(metadata);
    }

    private static Future<RecordMetadata> failedFuture() {
        CompletableFuture<RecordMetadata> failed = new CompletableFuture<>();
        failed.completeExceptionally(new KafkaException("simulated broker failure"));
        return failed;
    }

}
