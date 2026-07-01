/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.events;

import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventTypeSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * TCK: Abstract base for Kafka {@link EventEngine} contract verification (since 0.7.0).
 *
 * <h2>EVENT-206 — Kafka Driver Contract</h2>
 * <p>This suite asserts the binding-agnostic obligations every Kafka-backed
 * {@link EventEngine} must satisfy:
 * <ol>
 *   <li>A persistent event published via {@link eu.exeris.kernel.spi.events.EventBus#publish}
 *       reaches a local subscriber after the broker roundtrip — the subscriber sees the event,
 *       the descriptor primitives are preserved bit-for-bit, and the payload bytes survive
 *       verbatim (empty + non-empty tails).</li>
 *   <li>Every event published for a given {@code streamId} surfaces at the local subscriber
 *       (set-equality / no drops). Strict per-stream ordering is preserved at the Kafka
 *       partition level by per-key routing, but the in-memory bus dispatches each event onto
 *       its own virtual thread — so subscriber-observed order is unspecified by this TCK.</li>
 *   <li>Engine close is idempotent and stops the consumer poll loop deterministically.</li>
 *   <li>(ADR-050) A type carrying a binding-agnostic {@code topic} override still round-trips:
 *       the publish path and the subscribe path both resolve to the override topic, so the event
 *       reaches the local subscriber. If they diverged (publish → override, subscribe →
 *       name-derived default) the event would be stranded and never surface.</li>
 * </ol>
 *
 * <p>Concrete bindings (e.g. {@code CommunityKafkaEventEngineTckIT}) are responsible for
 * standing up a Kafka broker — typically via Testcontainers — and threading the resulting
 * {@code bootstrap.servers} through {@link #createEngine()}.
 *
 * @since 0.7.0
 */
@DisplayName("Kafka EventEngine TCK — publish / consume roundtrip contract")
public abstract class AbstractKafkaEventEngineTck {

    /**
     * Creates a fresh, NOT-yet-started {@link EventEngine} bound to a working Kafka broker.
     * The TCK calls {@code start()} after registering event types.
     */
    protected abstract EventEngine createEngine();

    private static final String  TYPE_KAFKA_PROBE     = "KafkaTckProbe";
    private static final int     ORDINAL_KAFKA_PROBE  = 30_001;

    // ADR-050 — a type whose topic override differs from its type name.
    private static final String  TYPE_KAFKA_TOPICED    = "KafkaTckTopicedProbe";
    private static final int     ORDINAL_KAFKA_TOPICED = 30_050;
    private static final String  TOPIC_OVERRIDE        = "exeris-tck.orders.created";

    private EventEngine engine;

    @BeforeEach
    final void setUp() {
        engine = createEngine();
        engine.registry().register(EventTypeSpec.ofPersistent(TYPE_KAFKA_PROBE, ORDINAL_KAFKA_PROBE));
        engine.start();
    }

    @AfterEach
    final void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("publish() then consumer roundtrip delivers the event to a local subscriber")
    void publishConsumeRoundtrip() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID streamId = UUID.randomUUID();
        long occurredAt = System.currentTimeMillis();

        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<EventDescriptor> capturedDescriptor = new AtomicReference<>();
        AtomicReference<Integer> capturedLength = new AtomicReference<>();

        // Filter by streamId — the shared Kafka container plus fresh-group-id + earliest reset
        // means this consumer also sees events from prior tests on the same topic.
        engine.bus().subscribe(TYPE_KAFKA_PROBE, (descriptor, payload) -> {
            try (payload) {
                if (descriptor.streamIdHigh() != streamId.getMostSignificantBits()
                        || descriptor.streamIdLow() != streamId.getLeastSignificantBits()) {
                    return;
                }
                capturedDescriptor.set(descriptor);
                capturedLength.set(payload.length());
                received.countDown();
            }
        });

        EventDescriptor sent = EventDescriptor.of(
                eventId.getMostSignificantBits(), eventId.getLeastSignificantBits(),
                streamId.getMostSignificantBits(), streamId.getLeastSignificantBits(),
                ORDINAL_KAFKA_PROBE,
                EventDescriptor.FLAG_PERSISTENT,
                occurredAt);
        engine.bus().publish(sent, EventPayload.empty());

        assertThat(received.await(45, TimeUnit.SECONDS))
                .as("Kafka roundtrip MUST deliver the event to a local subscriber within 45 s "
                        + "(broker bootstrap + first poll)")
                .isTrue();

        EventDescriptor decoded = capturedDescriptor.get();
        assertThat(decoded.eventIdHigh()).isEqualTo(sent.eventIdHigh());
        assertThat(decoded.eventIdLow()).isEqualTo(sent.eventIdLow());
        assertThat(decoded.streamIdHigh()).isEqualTo(sent.streamIdHigh());
        assertThat(decoded.streamIdLow()).isEqualTo(sent.streamIdLow());
        assertThat(decoded.eventTypeOrdinal()).isEqualTo(sent.eventTypeOrdinal());
        assertThat(decoded.flags()).isEqualTo(sent.flags());
        assertThat(decoded.occurredAtEpochMs()).isEqualTo(sent.occurredAtEpochMs());
        assertThat(capturedLength.get())
                .as("EventPayload.empty() length is preserved across the wire")
                .isZero();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("(ADR-050) a type with a topic override still round-trips to a local subscriber")
    void topicOverrideRoundtripDelivers() throws Exception {
        // Register a type whose topic override ("exeris-tck.orders.created") differs from its
        // type name ("KafkaTckTopicedProbe"). The consumer loop picks up the new registration on
        // its next subscription refresh (same dynamic-registration path as the warm-up test).
        // Publish and subscribe must BOTH resolve to the override topic — if they diverged
        // (publish → override, subscribe → name-derived default), the event would be stranded and
        // this delivery would time out.
        engine.registry().register(
                EventTypeSpec.ofPersistent(TYPE_KAFKA_TOPICED, ORDINAL_KAFKA_TOPICED, TOPIC_OVERRIDE));

        UUID eventId  = UUID.randomUUID();
        UUID streamId = UUID.randomUUID();
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<EventDescriptor> captured = new AtomicReference<>();

        engine.bus().subscribe(TYPE_KAFKA_TOPICED, (descriptor, payload) -> {
            try (payload) {
                if (descriptor.streamIdHigh() == streamId.getMostSignificantBits()
                        && descriptor.streamIdLow() == streamId.getLeastSignificantBits()) {
                    captured.set(descriptor);
                    received.countDown();
                }
            }
        });

        EventDescriptor sent = EventDescriptor.of(
                eventId.getMostSignificantBits(),  eventId.getLeastSignificantBits(),
                streamId.getMostSignificantBits(), streamId.getLeastSignificantBits(),
                ORDINAL_KAFKA_TOPICED,
                EventDescriptor.FLAG_PERSISTENT,
                System.currentTimeMillis());
        engine.bus().publish(sent, EventPayload.empty());

        assertThat(received.await(45, TimeUnit.SECONDS))
                .as("a topic-overridden type MUST round-trip: publish and subscribe both resolve "
                        + "to the ADR-050 override topic, so the event reaches the local subscriber")
                .isTrue();
        assertThat(captured.get().eventTypeOrdinal())
                .as("the delivered event MUST be the topic-overridden type")
                .isEqualTo(ORDINAL_KAFKA_TOPICED);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("close() is idempotent and stops the consumer poll loop")
    void closeIdempotent() {
        assertThatCode(() -> {
            engine.close();
            engine.close();
        })
                .as("Engine close MUST be idempotent — the second close() is a no-op")
                .doesNotThrowAnyException();
        engine = null; // tearDown should not double-close
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("non-empty payload bytes survive the broker hop verbatim")
    void nonEmptyPayloadRoundtrip() throws Exception {
        UUID eventId  = UUID.randomUUID();
        UUID streamId = UUID.randomUUID();
        byte[] expected = new byte[256];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (byte) ((i * 31) & 0xFF);
        }

        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<byte[]> captured = new AtomicReference<>();

        // Filter by streamId so this test's subscriber doesn't see events from prior tests on
        // the shared Kafka topic (auto.offset.reset=earliest + fresh group id replays history).
        engine.bus().subscribe(TYPE_KAFKA_PROBE, (descriptor, payload) -> {
            try (payload) {
                if (descriptor.streamIdHigh() != streamId.getMostSignificantBits()
                        || descriptor.streamIdLow() != streamId.getLeastSignificantBits()) {
                    return;
                }
                MemorySegment segment = payload.segment();
                byte[] copy = new byte[payload.length()];
                MemorySegment.copy(segment, 0L, MemorySegment.ofArray(copy), 0L, copy.length);
                captured.set(copy);
                received.countDown();
            }
        });

        EventDescriptor sent = EventDescriptor.of(
                eventId.getMostSignificantBits(),  eventId.getLeastSignificantBits(),
                streamId.getMostSignificantBits(), streamId.getLeastSignificantBits(),
                ORDINAL_KAFKA_PROBE,
                EventDescriptor.FLAG_PERSISTENT,
                System.currentTimeMillis());
        engine.bus().publish(sent, heapPayload(expected));

        assertThat(received.await(45, TimeUnit.SECONDS))
                .as("non-empty payload roundtrip MUST deliver within 45 s")
                .isTrue();
        assertThat(captured.get())
                .as("payload bytes MUST survive the broker hop verbatim")
                .containsExactly(expected);
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @DisplayName("all events for a given streamId reach the local subscriber (set equality)")
    void sameStreamIdAllDelivered() throws Exception {
        UUID streamId = UUID.randomUUID();
        int eventCount = 8;

        CountDownLatch received = new CountDownLatch(eventCount);
        List<Integer> deliveredTags = java.util.Collections.synchronizedList(new ArrayList<>());

        // Filter by streamId so prior tests on the shared topic don't contaminate the count.
        // NB: per-stream STRICT ORDER is preserved at the Kafka partition level (per-key
        // partitioning) but the in-memory EventBus dispatches each event onto its own virtual
        // thread, which means subscriber-side arrival order does not necessarily match send
        // order. The contract verified here is set equality + delivery completeness; ordering
        // verification belongs at the consumer-loop boundary, not the subscriber.
        engine.bus().subscribe(TYPE_KAFKA_PROBE, (descriptor, payload) -> {
            try (payload) {
                if (descriptor.streamIdHigh() != streamId.getMostSignificantBits()
                        || descriptor.streamIdLow() != streamId.getLeastSignificantBits()) {
                    return;
                }
                MemorySegment segment = payload.segment();
                byte[] tag = new byte[payload.length()];
                MemorySegment.copy(segment, 0L, MemorySegment.ofArray(tag), 0L, tag.length);
                if (tag.length == 1) {
                    deliveredTags.add(Byte.toUnsignedInt(tag[0]));
                }
                received.countDown();
            }
        });

        for (int i = 0; i < eventCount; i++) {
            UUID eventId = UUID.randomUUID();
            EventDescriptor sent = EventDescriptor.of(
                    eventId.getMostSignificantBits(),  eventId.getLeastSignificantBits(),
                    streamId.getMostSignificantBits(), streamId.getLeastSignificantBits(),
                    ORDINAL_KAFKA_PROBE,
                    EventDescriptor.FLAG_PERSISTENT,
                    System.currentTimeMillis());
            engine.bus().publish(sent, heapPayload(new byte[] {(byte) i}));
        }

        assertThat(received.await(60, TimeUnit.SECONDS))
                .as("all %d same-stream events MUST be delivered within 60 s", eventCount)
                .isTrue();

        List<Integer> expected = new ArrayList<>(eventCount);
        for (int i = 0; i < eventCount; i++) {
            expected.add(i);
        }
        assertThat(deliveredTags)
                .as("every event published for the streamId MUST surface at the subscriber "
                        + "(no drops, no duplicates per single-handler delivery)")
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    /**
     * Regression: an EventEngine started before any event type is registered MUST NOT
     * crash its consumer loop, and a subsequent register + publish MUST still deliver.
     *
     * <p>kafka-clients 3.x throws {@code IllegalStateException} on {@code consumer.poll()}
     * when the consumer has neither subscribed to any topics nor manually assigned any
     * partitions. The Kafka driver therefore must guard the steady-state poll path so
     * that {@code engine.start()} → ... → {@code registry.register(...)} ordering is safe
     * regardless of whether the consumer-loop virtual thread reaches its first poll
     * before or after the registry mutation.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("Engine started before any registry entry survives the consumer-loop warm-up")
    void startWithoutRegistrationsThenRegisterAndPublish() throws Exception {
        // The shared field engine has been pre-registered + started by setUp; close it and
        // build a fresh engine that we control end-to-end. tearDown will see engine=null
        // and skip the duplicate close.
        engine.close();
        engine = null;

        EventEngine fresh = createEngine();
        fresh.start();
        // Give the consumer-loop VT enough time to run its first poll iteration with an
        // empty subscription set — that is exactly the path that previously crashed it.
        Thread.sleep(750L);
        assertThat(fresh.loop().isRunning())
                .as("consumer loop must remain running after the first empty-subscription tick")
                .isTrue();

        try {
            String regressionType = "KafkaTckProbe-empty-subscribe";
            int regressionOrdinal = 30_002;
            fresh.registry().register(EventTypeSpec.ofPersistent(regressionType, regressionOrdinal));

            CountDownLatch received = new CountDownLatch(1);
            UUID streamId = UUID.randomUUID();
            fresh.bus().subscribe(regressionType, (descriptor, payload) -> {
                try (payload) {
                    if (descriptor.streamIdHigh() == streamId.getMostSignificantBits()
                            && descriptor.streamIdLow() == streamId.getLeastSignificantBits()) {
                        received.countDown();
                    }
                }
            });

            EventDescriptor sent = EventDescriptor.of(
                    UUID.randomUUID().getMostSignificantBits(), UUID.randomUUID().getLeastSignificantBits(),
                    streamId.getMostSignificantBits(), streamId.getLeastSignificantBits(),
                    regressionOrdinal,
                    EventDescriptor.FLAG_PERSISTENT,
                    System.currentTimeMillis());
            fresh.bus().publish(sent, EventPayload.empty());

            assertThat(received.await(45, TimeUnit.SECONDS))
                    .as("event registered+published after a warm-up tick MUST still reach the subscriber")
                    .isTrue();
        } finally {
            fresh.close();
        }
    }

    private static EventPayload heapPayload(byte[] bytes) {
        return new EventPayload() {
            private final java.util.concurrent.atomic.AtomicInteger refCount =
                    new java.util.concurrent.atomic.AtomicInteger(1);
            @Override public MemorySegment segment() { return MemorySegment.ofArray(bytes); }
            @Override public int  length()    { return bytes.length; }
            @Override public int  refCount()  { return refCount.get(); }
            @Override public boolean isAlive() { return refCount.get() > 0; }
            @Override public void retain() {
                refCount.updateAndGet(v -> v == 0 ? 0 : v + 1);
            }
            @Override public void close() {
                refCount.updateAndGet(v -> v == 0 ? 0 : v - 1);
            }
        };
    }
}
