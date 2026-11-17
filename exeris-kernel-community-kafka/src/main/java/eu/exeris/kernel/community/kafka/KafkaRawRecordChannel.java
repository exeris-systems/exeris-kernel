/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.spi.events.EventTypeSpec;
import eu.exeris.kernel.spi.exceptions.events.EventRegistryException;

import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The engine's single {@link KafkaRecordChannel}. Its subscription map is written by application
 * threads and read, without locking, by the consumer loop thread; {@link #version()} is the cheap
 * change signal the loop reads once per iteration.
 *
 * @since 0.13.2
 */
final class KafkaRawRecordChannel implements KafkaRecordChannel {

    private final ConcurrentMap<String, KafkaRecordHandler> handlers = new ConcurrentHashMap<>();
    private final AtomicInteger version = new AtomicInteger();
    private final AtomicLong sendFailedTotal = new AtomicLong();
    private final KafkaEventConfig config;
    private final Producer<byte[], byte[]> producer;
    private final AtomicBoolean sendFailed;
    // One shared, stateless callback for a send that brings none of its own.
    private final Callback failureOnly = new FailureTracking(null);
    private volatile ConsumerRebalanceListener listener;
    private final KafkaFramedTopics framedTopics;

    /* default */ KafkaRawRecordChannel(KafkaEventRegistry registry, KafkaEventConfig config,
                                        Producer<byte[], byte[]> producer, AtomicBoolean sendFailed) {
        this.config = config;
        this.producer = producer;
        this.sendFailed = sendFailed;
        this.framedTopics = new KafkaFramedTopics(registry, config);
    }

    @Override
    public void subscribe(String topic, KafkaRecordHandler handler) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(handler, "handler");
        if (topic.isEmpty()) {
            throw new IllegalArgumentException("topic must not be empty");
        }
        String clash = framedTopics.describe(topic);
        if (clash != null) {
            throw new IllegalStateException("topic '" + topic + "' is " + clash
                    + " and cannot carry raw records");
        }
        if (handlers.putIfAbsent(topic, handler) != null) {
            throw new IllegalStateException("topic '" + topic + "' already has a raw record handler");
        }
        // Published after the map write: a loop that reads the new version also sees the topic.
        version.incrementAndGet();
    }

    @Override
    public void rebalanceListener(ConsumerRebalanceListener rebalanceListener) {
        this.listener = Objects.requireNonNull(rebalanceListener, "listener");
    }

    @Override
    public void send(ProducerRecord<byte[], byte[]> record, Callback callback) {
        Objects.requireNonNull(record, "record");
        // The send path: one set lookup against a snapshot rebuilt only when a type is registered.
        if (framedTopics.contains(record.topic())) {
            throw new IllegalArgumentException("topic '" + record.topic() + "' is "
                    + framedTopics.describe(record.topic()) + "; a raw send cannot write into it");
        }
        producer.send(record, callback == null ? failureOnly : new FailureTracking(callback));
    }

    @Override
    public long sendFailedTotal() {
        return sendFailedTotal.get();
    }

    @Override
    public int partitionsFor(String topic) {
        return producer.partitionsFor(Objects.requireNonNull(topic, "topic")).size();
    }

    /* default */ ConcurrentMap<String, KafkaRecordHandler> handlers() {
        return handlers;
    }

    /* default */ int version() {
        return version.get();
    }

    /* default */ ConsumerRebalanceListener listener() {
        return listener;
    }

    /**
     * The registration guard handed to the registry: refuses an event type whose effective topic is
     * already subscribed raw. Best effort against a concurrent raw subscribe; registration is
     * expected to precede the raw subscription or follow it, not race it.
     */
    /* default */ void rejectRawTopic(EventTypeSpec spec) {
        String topic = KafkaEventEngine.effectiveTopic(spec, config);
        if (handlers.containsKey(topic)) {
            throw new EventRegistryException("Event type '" + spec.name() + "' maps to topic '" + topic
                    + "', which is subscribed as a raw record topic");
        }
    }

    private final class FailureTracking implements Callback {

        private final Callback delegate;

        private FailureTracking(Callback delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onCompletion(RecordMetadata metadata, Exception exception) {
            if (exception != null) {
                sendFailedTotal.incrementAndGet();
                sendFailed.set(true);
            }
            if (delegate != null) {
                delegate.onCompletion(metadata, exception);
            }
        }
    }
}
