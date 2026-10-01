/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.events;

import eu.exeris.kernel.spi.events.EventBus;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventEngineConfig;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventTypeSpec;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.events.EventBusException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.foreign.MemorySegment;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit: the persistent-publish failures of {@link CommunityEventEngine}'s bus that are not a full
 * queue carry {@code EX-EVENT-6009} and {@code rawArgs [eventTypeOrdinal, reason]}.
 *
 * <p>The engine is never started, so nothing drains the queue: a persistent publish stays queued
 * and the next one meets a full queue deterministically.
 */
@DisplayName("CommunityEventEngine — publish failures carry EX-EVENT-6009")
class CommunityEventEnginePublishFailureTest {

    private static final String TYPE = "PublishFailureEvent";
    private static final int ORDINAL = 9_101;

    private CommunityEventEngine engine;

    @AfterEach
    void tearDown() {
        // A test that fails between interrupting itself and its own clear must not leak the flag.
        Thread.interrupted();
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("a queue push that throws -> EX-EVENT-6009 [ordinal, delivery-failed], cause attached")
    void pushThrowingRaisesPublishFailed() {
        engine = newEngine(16);
        EventBus bus = engine.bus();
        IllegalStateException refused = new IllegalStateException("payload already released");
        AtomicInteger closes = new AtomicInteger();
        EventPayload payload = payload(() -> {
            throw refused;
        }, closes);
        EventDescriptor descriptor = persistentDescriptor();

        EventBusException ex = assertThrows(EventBusException.class,
                () -> bus.publish(descriptor, payload));

        assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_EVENT_6009);
        assertThat(ex.rawArgs()).containsExactly(ORDINAL, "delivery-failed");
        assertThat(ex.getCause()).isSameAs(refused);
        assertThat(closes.get())
                .as("the caller's reference is released on the failure path")
                .isEqualTo(1);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("an interrupted blocking push -> EX-EVENT-6009 [ordinal, interrupted], interrupt kept")
    void interruptedBlockingPushRaisesPublishFailed() {
        engine = newEngine(1);
        EventBus bus = engine.bus();
        bus.publish(persistentDescriptor(), EventPayload.empty());   // fills the one-slot queue
        EventDescriptor descriptor = persistentDescriptor();
        EventPayload payload = EventPayload.empty();

        Thread.currentThread().interrupt();
        EventBusException ex = assertThrows(EventBusException.class,
                () -> bus.publish(descriptor, payload));
        boolean interruptKept = Thread.interrupted();

        assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_EVENT_6009);
        assertThat(ex.rawArgs()).containsExactly(ORDINAL, "interrupted");
        assertThat(ex.getCause()).isNull();
        assertThat(interruptKept)
                .as("the queue restores the interrupt status and the bus leaves it set")
                .isTrue();
    }

    private CommunityEventEngine newEngine(int queueCapacity) {
        EventEngineConfig defaults = EventEngineConfig.communityDefaults();
        CommunityEventEngine created = new CommunityEventEngine(new EventEngineConfig(
                defaults.engineName(),
                queueCapacity,
                defaults.batchSize(),
                defaults.partitionName(),
                defaults.partitionBytes(),
                defaults.slabDescriptorCount(),
                defaults.slabPayloadSmall(),
                defaults.slabPayloadMedium(),
                defaults.slabPayloadLarge(),
                false,                            // outboxEnabled — no PersistenceEngine wiring
                defaults.outboxBatchSize(),
                false));                          // busPublishFailFast — blocking mode
        created.registry().register(EventTypeSpec.ofPersistent(TYPE, ORDINAL));
        return created;
    }

    private static EventDescriptor persistentDescriptor() {
        UUID id = UUID.randomUUID();
        return new EventDescriptor(
                id.getMostSignificantBits(), id.getLeastSignificantBits(),
                0L, 0L,
                ORDINAL,
                EventDescriptor.FLAG_PERSISTENT,
                System.currentTimeMillis());
    }

    private static EventPayload payload(Runnable onRetain, AtomicInteger closes) {
        return new EventPayload() {
            @Override public MemorySegment segment() { return MemorySegment.NULL; }
            @Override public int length() { return 0; }
            @Override public int refCount() { return 1; }
            @Override public boolean isAlive() { return true; }
            @Override public void retain() { onRetain.run(); }
            @Override public void close() { closes.incrementAndGet(); }
        };
    }
}
