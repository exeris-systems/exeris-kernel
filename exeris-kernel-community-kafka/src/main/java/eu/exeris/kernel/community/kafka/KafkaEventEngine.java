/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.core.events.InMemoryEventBus;
import eu.exeris.kernel.spi.events.EventBatchProcessor;
import eu.exeris.kernel.spi.events.EventBus;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventEngineConfig;
import eu.exeris.kernel.spi.events.EventEngineStats;
import eu.exeris.kernel.spi.events.EventHandler;
import eu.exeris.kernel.spi.events.EventLoop;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventQueue;
import eu.exeris.kernel.spi.events.EventRegistry;
import eu.exeris.kernel.spi.events.SubscriptionToken;
import eu.exeris.kernel.spi.exceptions.events.EventBusException;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;

import java.util.HashSet;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Community Kafka {@link EventEngine} binding (since 0.7.0).
 *
 * <h2>Wire Model</h2>
 * <ul>
 *   <li>{@link EventBus#publish} delegates to a Kafka {@link Producer}; the Kafka topic is
 *       {@code KafkaEventConfig.topicFor(registry.nameOfOrdinal(...))}, the partition key is
 *       the 16-byte stream UUID (so events for the same saga land on the same partition).</li>
 *   <li>Subscribers register against an internal {@link InMemoryEventBus}; nothing is delivered
 *       locally on publish — events surface to local subscribers only after a Kafka roundtrip,
 *       which gives the same semantics as a remote subscriber.</li>
 *   <li>{@link EventLoop#start} spins up a virtual thread that runs a Kafka
 *       {@link KafkaConsumer} poll loop. Each polled record is decoded, wrapped in a fresh
 *       {@link KafkaHeapEventPayload}, and published onto the internal in-memory bus for
 *       local fan-out.</li>
 * </ul>
 *
 * <h2>The Wall</h2>
 * <p>{@code org.apache.kafka.clients.*} is referenced ONLY in this package
 * ({@code eu.exeris.kernel.community.kafka}). Core's {@code OutboxBrokerPort} and the SPI
 * remain implementation-blind; the {@link KafkaEventBrokerPort} adapter bridges the two
 * worlds without exposing Kafka types upstream.
 *
 * <h2>MVP Scope</h2>
 * <p>This first release covers publish + consume roundtrip, at-least-once on <em>produce</em>
 * via {@code acks=all} (when {@link KafkaEventConfig#requireAllAcks()} is true), and a
 * virtual-thread poll loop that runs with {@code enable.auto.commit=true}. Note that
 * auto-commit yields <em>at-most-once</em> semantics on the consume side: if a local
 * subscriber's handler crashes after the consumer publishes onto the in-process delegate
 * but before the offset commit can be replayed, the event is lost. A future revision will
 * flip to {@code enable.auto.commit=false} with manual commit-after-handler.
 *
 * <p>Replay (seek by timestamp / offset),
 * {@link eu.exeris.kernel.spi.events.EventStreamReader} / {@code EventStreamAppender}
 * implementations, DLQ rebalance handling, and the
 * {@link KafkaEventBrokerPort}-driven outbox-orchestrator delivery path
 * (the adapter ships in this PR but is not yet wired into a runtime path —
 * {@link KafkaPublishBus} goes producer&nbsp;→&nbsp;consumer directly) are all deferred
 * to a follow-up.
 *
 * @since 0.7.0
 */
@SuppressWarnings({
        // KafkaEventEngine bundles publish + consume + producer + consumer wiring intentionally;
        // splitting it would dilute the single Kafka-binding entry point. Sprint 8 SQ-006 may
        // re-evaluate alongside other large engines.
        "PMD.ExcessiveImports",
        "PMD.CouplingBetweenObjects"
})
public final class KafkaEventEngine implements EventEngine {

    private final EventEngineConfig spiConfig;
    private final KafkaEventRegistry registry;
    private final InMemoryEventBus  localDelegate;
    private final EventBus          publishBus;
    private final NoOpQueue         queue;
    private final ConsumerLoop      loop;
    private final Producer<byte[], byte[]> producer;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicLong publishedTotal = new AtomicLong(0L);

    public KafkaEventEngine(EventEngineConfig spiConfig, KafkaEventConfig kafkaConfig) {
        this.spiConfig = Objects.requireNonNull(spiConfig, "spiConfig");
        Objects.requireNonNull(kafkaConfig, "kafkaConfig");
        this.registry      = new KafkaEventRegistry();
        this.localDelegate = new InMemoryEventBus(registry);
        this.queue         = new NoOpQueue(spiConfig.queueCapacity());
        this.producer      = createProducer(kafkaConfig);
        this.publishBus    = new KafkaPublishBus(producer, registry, kafkaConfig,
                                                 localDelegate, publishedTotal);
        this.loop          = new ConsumerLoop(spiConfig.engineName(), kafkaConfig,
                                              registry, localDelegate);
    }

    @Override
    public EventBus bus() {
        return publishBus;
    }

    @Override
    public EventQueue queue() {
        return queue;
    }

    @Override
    public EventLoop loop() {
        return loop;
    }

    @Override
    public EventRegistry registry() {
        return registry;
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        loop.start();
    }

    @Override
    public void close() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        loop.stop();
        try {
            producer.close();
        } catch (RuntimeException _) { //NOPMD AvoidCatchingGenericException — best-effort producer shutdown
            // best-effort producer shutdown
        }
    }

    @Override
    public EventEngineStats stats() {
        return new EventEngineStats(
                publishedTotal.get(),
                loop.processedTotal(),
                0L,                       // failed deliveries — no DLQ in MVP
                0,                        // local queue depth — Kafka-driven
                spiConfig.queueCapacity(),
                0L,                       // no local dispatch latency tracked
                loop.isRunning());
    }

    private static Producer<byte[], byte[]> createProducer(KafkaEventConfig cfg) {
        Properties props = new Properties();
        props.put("bootstrap.servers",  cfg.bootstrapServers());
        props.put("key.serializer",     ByteArraySerializer.class.getName());
        props.put("value.serializer",   ByteArraySerializer.class.getName());
        props.put("acks",               cfg.requireAllAcks() ? "all" : "1");
        props.put("linger.ms",          Long.toString(cfg.producerLingerMs()));
        return new KafkaProducer<>(props);
    }

    // =========================================================================
    // Internal: KafkaPublishBus — publish via producer, subscribe via local delegate
    // =========================================================================

    private static final class KafkaPublishBus implements EventBus {

        private final Producer<byte[], byte[]> producer;
        private final KafkaEventRegistry       registry;
        private final KafkaEventConfig         config;
        private final EventBus                 localDelegate;
        private final AtomicLong               publishedTotal;

        private KafkaPublishBus(Producer<byte[], byte[]> producer,
                                KafkaEventRegistry registry,
                                KafkaEventConfig config,
                                EventBus localDelegate,
                                AtomicLong publishedTotal) {
            this.producer       = producer;
            this.registry       = registry;
            this.config         = config;
            this.localDelegate  = localDelegate;
            this.publishedTotal = publishedTotal;
        }

        @Override
        public void publish(EventDescriptor descriptor, EventPayload payload) {
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(payload,    "payload");
            // Kafka surfaces driver errors as KafkaException (RuntimeException); the bus
            // contract is to wrap them in EventBusException. payload.close() is auto-run
            // via try-with-resources to satisfy the RAII ownership transfer. publishedTotal
            // is incremented only after producer.send returns so failed publishes do not
            // inflate the counter.
            try (payload) {
                ProducerRecord<byte[], byte[]> producerRecord = buildRecord(descriptor, payload);
                producer.send(producerRecord);
                publishedTotal.incrementAndGet();
            } catch (EventBusException ex) {
                // buildRecord can throw EventBusException for unregistered ordinals — propagate
                // it unchanged; the generic Kafka-failure wrap below would mask the real cause.
                throw ex;
            } catch (RuntimeException ex) { //NOPMD AvoidCatchingGenericException
                throw new EventBusException("Kafka producer.send failed", ex);
            }
        }

        @Override
        public void publishAndAwait(EventDescriptor descriptor, EventPayload payload)
                throws InterruptedException {
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(payload,    "payload");
            try (payload) {
                ProducerRecord<byte[], byte[]> producerRecord = buildRecord(descriptor, payload);
                producer.send(producerRecord).get();
                publishedTotal.incrementAndGet();
            } catch (EventBusException ex) {
                // Surface unregistered-ordinal failures unchanged — generic Kafka-failure wrap
                // below would mask the real cause.
                throw ex;
            } catch (ExecutionException | RuntimeException ex) { //NOPMD KafkaException is RuntimeException
                throw new EventBusException("Kafka producer.send failed", ex);
            }
        }

        @Override
        public SubscriptionToken subscribe(String eventType, EventHandler handler) {
            return localDelegate.subscribe(eventType, handler);
        }

        @Override
        public void unsubscribe(SubscriptionToken token) {
            localDelegate.unsubscribe(token);
        }

        private ProducerRecord<byte[], byte[]> buildRecord(EventDescriptor descriptor, EventPayload payload) {
            String typeName = registry.nameOfOrdinal(descriptor.eventTypeOrdinal());
            if (typeName == null || typeName.isBlank()) {
                throw new EventBusException(
                        "Cannot publish event with unregistered ordinal: "
                        + descriptor.eventTypeOrdinal());
            }
            String topic = config.topicFor(typeName);
            byte[] key   = KafkaEventCodec.streamKey(descriptor);
            byte[] value = KafkaEventCodec.encode(descriptor, payload);
            return new ProducerRecord<>(topic, key, value);
        }
    }

    // =========================================================================
    // Internal: ConsumerLoop — virtual-thread Kafka consumer poll loop
    // =========================================================================

    private static final class ConsumerLoop implements EventLoop {

        private final String              engineName;
        private final KafkaEventConfig    config;
        private final KafkaEventRegistry  registry;
        private final EventBus            localDelegate;
        private final AtomicReference<Thread> loopThread = new AtomicReference<>();
        private final AtomicBoolean       running = new AtomicBoolean(false);
        private final AtomicLong          processedTotal = new AtomicLong(0L);
        private final Set<String>         subscribedTopics = new HashSet<>();
        private int                       lastRegisteredVersion = -1;

        private ConsumerLoop(String engineName,
                             KafkaEventConfig config,
                             KafkaEventRegistry registry,
                             EventBus localDelegate) {
            this.engineName    = engineName;
            this.config        = config;
            this.registry      = registry;
            this.localDelegate = localDelegate;
        }

        @Override
        public void start() {
            if (!running.compareAndSet(false, true)) {
                return;
            }
            Thread thread = Thread.ofVirtual()
                    .name("exeris-kafka-consumer-" + config.consumerGroupId())
                    .start(this::run);
            loopThread.set(thread);
        }

        @Override
        public void stop() {
            if (!running.compareAndSet(true, false)) {
                return;
            }
            Thread thread = loopThread.getAndSet(null);
            if (thread != null) {
                thread.interrupt();
                try {
                    thread.join(5_000L);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public boolean isRunning() {
            return running.get();
        }

        @Override
        public void registerProcessor(String eventType, EventBatchProcessor processor) {
            // Kafka driver dispatches directly via localDelegate.subscribe(...);
            // batch processors are not part of the Kafka MVP path. This is a no-op so
            // higher-level wiring (e.g., outbox flush) can register without throwing.
        }

        /* default */ long processedTotal() {
            return processedTotal.get();
        }

        private void run() {
            Properties props = new Properties();
            props.put("bootstrap.servers",  config.bootstrapServers());
            props.put("group.id",           config.consumerGroupId());
            props.put("key.deserializer",   ByteArrayDeserializer.class.getName());
            props.put("value.deserializer", ByteArrayDeserializer.class.getName());
            props.put("enable.auto.commit", "true");
            props.put("auto.offset.reset",  "earliest");

            try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(props)) {
                while (running.get() && !Thread.currentThread().isInterrupted()) {
                    refreshSubscriptions(consumer);
                    ConsumerRecords<byte[], byte[]> batch = consumer.poll(config.consumerPollTimeout());
                    for (ConsumerRecord<byte[], byte[]> consumerRecord : batch) {
                        dispatch(consumerRecord);
                    }
                }
            } catch (RuntimeException ex) { //NOPMD AvoidCatchingGenericException — KafkaException is a RuntimeException
                // Operators rely on this JFR event to detect silent consumer-thread death;
                // without it isRunning() simply flips false and events stop arriving with no
                // further signal. Payload is secret-safe — bootstrap.servers / credentials are
                // never included.
                KafkaConsumerLoopFailedEvent.emit(
                        engineName,
                        config.consumerGroupId(),
                        ex.getClass().getName(),
                        ex.getMessage());
            } finally {
                running.set(false);
            }
        }

        // Fast exit on unchanged registry version: the steady-state poll path (registry stable)
        // hits this method ~4x/sec per engine and previously allocated a fresh HashSet plus a
        // Set.copyOf() inside registry.registeredTypes() on every call. The version counter is
        // bumped only when register() truly mutates state, so unchanged === no allocation.
        private void refreshSubscriptions(KafkaConsumer<byte[], byte[]> consumer) {
            int currentVersion = registry.registeredVersion();
            if (currentVersion == lastRegisteredVersion) {
                return;
            }
            Set<String> registered = registry.registeredTypes();
            Set<String> desired = HashSet.newHashSet(registered.size());
            for (String type : registered) {
                desired.add(config.topicFor(type));
            }
            if (!desired.equals(subscribedTopics)) {
                consumer.subscribe(desired);
                subscribedTopics.clear();
                subscribedTopics.addAll(desired);
            }
            lastRegisteredVersion = currentVersion;
        }

        // PMD.CloseResource — payload ownership is transferred to localDelegate.publish()
        // per the EventBus RAII broadcast protocol; close happens in handlers.
        @SuppressWarnings("PMD.CloseResource")
        private void dispatch(ConsumerRecord<byte[], byte[]> consumerRecord) {
            byte[] frame = consumerRecord.value();
            if (frame == null || frame.length == 0) {
                return;
            }
            EventDescriptor descriptor = KafkaEventCodec.decodeDescriptor(frame);
            byte[] payloadBytes = KafkaEventCodec.decodePayloadBytes(frame);
            KafkaHeapEventPayload payload = KafkaHeapEventPayload.wrap(payloadBytes);
            localDelegate.publish(descriptor, payload);
            processedTotal.incrementAndGet();
        }
    }

    // =========================================================================
    // Internal: NoOpQueue — degenerate EventQueue (Kafka drives delivery, not local queue)
    //
    // The Kafka driver does not use a local EventQueue — Kafka itself is the durable queue
    // and KafkaPublishBus.publish goes producer → consumer directly. NoOpQueue.push therefore
    // fails loud rather than silently returning true: any caller that reaches it has reached
    // it by mistake (the SPI's queue() slot is a contract leak for this driver). The chosen
    // failure mode is EventBusException — the kernel's documented refusal exception that
    // generic callers already catch from bus().publish(...) — rather than
    // UnsupportedOperationException, which sits outside the SPI's declared error hierarchy.
    // Callers that expect a queue-backed engine should use the in-memory CommunityEventEngine
    // instead.
    // =========================================================================

    private record NoOpQueue(int capacity) implements EventQueue {

        private static final String BYPASS_MESSAGE =
                "KafkaEventEngine bypasses the local EventQueue (Kafka itself is the durable "
                + "queue); publish via bus() instead of queue().push(...).";

        @Override
        public boolean push(EventDescriptor descriptor, EventPayload payload) {
            throw new EventBusException(BYPASS_MESSAGE);
        }

        @Override
        public EventDescriptor poll(java.util.function.Consumer<EventPayload> sink) {
            return null;
        }

        @Override
        public int drain(java.util.function.BiConsumer<EventDescriptor, EventPayload> sink, int maxItems) {
            return 0;
        }

        @Override
        public int size() {
            return 0;
        }
    }
}
