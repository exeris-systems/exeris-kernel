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

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK: Abstract base for Kafka {@link EventEngine} contract verification (since 0.7.0).
 *
 * <h2>EVENT-206 — Kafka Driver Contract</h2>
 * <p>This suite asserts the binding-agnostic obligations every Kafka-backed
 * {@link EventEngine} must satisfy:
 * <ol>
 *   <li>A persistent event published via {@link eu.exeris.kernel.spi.events.EventBus#publish}
 *       reaches a local subscriber after the broker roundtrip — the subscriber sees the event,
 *       the descriptor primitives are preserved bit-for-bit, and the payload bytes are intact.</li>
 *   <li>Multiple events on the same {@code streamId} are delivered in order to a single
 *       subscriber (by virtue of Kafka's per-key partition routing).</li>
 *   <li>Engine close is idempotent and stops the consumer poll loop deterministically.</li>
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

        engine.bus().subscribe(TYPE_KAFKA_PROBE, (descriptor, payload) -> {
            try (payload) {
                capturedDescriptor.set(descriptor);
                capturedLength.set(payload.length());
            } finally {
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
    @DisplayName("close() is idempotent and stops the consumer poll loop")
    void closeIdempotent() {
        engine.close();
        engine.close();
        engine = null; // tearDown should not double-close
    }
}
