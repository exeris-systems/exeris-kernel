/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.events;

import eu.exeris.kernel.spi.events.EventBatchProcessor;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventTypeSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK: Abstract base for Outbox Orchestration contract verification — SPI-only.
 *
 * <h2>Architecture note</h2>
 * <p>The TCK tests only against {@link EventEngine} SPI contracts. The outbox
 * orchestration contract is exercised through the {@link eu.exeris.kernel.spi.events.EventLoop}
 * batch-processor registration: implementations that integrate the Outbox Orchestrator
 * hook it as an {@link EventBatchProcessor} on the loop. This keeps the TCK free of
 * any {@code exeris-kernel-core} implementation dependency.
 *
 * <h2>Verified Constraints</h2>
 * <ol>
 *   <li>GOLDEN: persistent events (FLAG_PERSISTENT) reach the registered
 *       {@link EventBatchProcessor} — the outbox hook fires.</li>
 *   <li>Non-persistent events are NOT delivered to the outbox processor.</li>
 *   <li>EventLoop batch processor receives correct descriptor metadata.</li>
 *   <li>At-least-once: processor receives all persisted events even under
 *       rapid sequential publish.</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * class CommunityOutboxTckTest extends AbstractOutboxOrchestratorTck {
 *     \@Override
 *     protected EventEngine createEngine() {
 *         return new CommunityEventProvider().createEngine(EventEngineConfig.communityDefaults());
 *     }
 * }
 * }</pre>
 *
 * @since 0.5.0
 */
public abstract class AbstractOutboxOrchestratorTck {

    private static final String TYPE_PERSISTENT     = "OrderPersisted";
    private static final String TYPE_NON_PERSISTENT = "HeartbeatSignal";
    private static final int    ORD_PERSISTENT      = 300;
    private static final int    ORD_NON_PERSISTENT  = 301;

    private EventEngine engine;

    /**
     * Creates a fully configured, not-yet-started {@link EventEngine} under test.
     *
     * @return non-null engine instance
     */
    protected abstract EventEngine createEngine();

    @AfterEach
    final void afterEach() {
        if (engine != null) {
            engine.close();
            engine = null;
        }
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    @DisplayName("GOLDEN: batch processor registered on loop receives persistent events")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void batchProcessorReceivesPersistentEvents() throws InterruptedException {
        engine = createEngine();
        engine.registry().register(EventTypeSpec.ofPersistent(TYPE_PERSISTENT, ORD_PERSISTENT));
        engine.registry().register(EventTypeSpec.of(TYPE_NON_PERSISTENT, ORD_NON_PERSISTENT));

        CountDownLatch batchLatch = new CountDownLatch(1);
        AtomicInteger  receivedCount = new AtomicInteger(0);

        engine.loop().registerProcessor(TYPE_PERSISTENT,
                (descriptors, payloads) -> {
                    payloads.forEach(EventPayload::close);
                    receivedCount.addAndGet(descriptors.size());
                    batchLatch.countDown();
                });

        engine.start();

        EventDescriptor descriptor = persistentDescriptor(ORD_PERSISTENT);
        engine.bus().publish(descriptor, EventPayload.empty());

        assertThat(batchLatch.await(4, TimeUnit.SECONDS))
                .as("Batch processor MUST be invoked for persistent events within 4 seconds")
                .isTrue();
        assertThat(receivedCount.get())
                .as("At least 1 persistent event must reach the batch processor")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Batch processor receives correct descriptor ordinal")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void batchProcessorReceivesCorrectOrdinal() throws InterruptedException {
        engine = createEngine();
        engine.registry().register(EventTypeSpec.ofPersistent(TYPE_PERSISTENT, ORD_PERSISTENT));

        CountDownLatch latch = new CountDownLatch(1);
        List<EventDescriptor> captured = new ArrayList<>();

        engine.loop().registerProcessor(TYPE_PERSISTENT,
                (descriptors, payloads) -> {
                    payloads.forEach(EventPayload::close);
                    captured.addAll(descriptors);
                    latch.countDown();
                });

        engine.start();
        engine.bus().publish(persistentDescriptor(ORD_PERSISTENT), EventPayload.empty());

        assertThat(latch.await(4, TimeUnit.SECONDS)).isTrue();
        assertThat(captured).isNotEmpty();
        assertThat(captured.get(0).eventTypeOrdinal()).isEqualTo(ORD_PERSISTENT);
    }

    @Test
    @DisplayName("At-least-once: 5 sequential persistent publishes reach the processor")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void atLeastOnceForSequentialPublishes() throws InterruptedException {
        engine = createEngine();
        engine.registry().register(EventTypeSpec.ofPersistent(TYPE_PERSISTENT, ORD_PERSISTENT));

        int expectedCount = 5;
        CountDownLatch latch = new CountDownLatch(expectedCount);
        AtomicInteger total  = new AtomicInteger(0);

        engine.loop().registerProcessor(TYPE_PERSISTENT,
                (descriptors, payloads) -> {
                    payloads.forEach(EventPayload::close);
                    int received = descriptors.size();
                    total.addAndGet(received);
                    for (int i = 0; i < received; i++) {
                        latch.countDown();
                    }
                });

        engine.start();

        for (int i = 0; i < expectedCount; i++) {
            engine.bus().publish(persistentDescriptor(ORD_PERSISTENT), EventPayload.empty());
        }

        assertThat(latch.await(8, TimeUnit.SECONDS))
                .as("All " + expectedCount + " persistent events must reach the batch processor. "
                    + "Received so far: " + total.get())
                .isTrue();
    }

    @Test
    @DisplayName("Non-persistent events are NOT delivered to the outbox batch processor")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void nonPersistentEventsNotDeliveredToOutbox() throws InterruptedException {
        engine = createEngine();
        engine.registry().register(EventTypeSpec.ofPersistent(TYPE_PERSISTENT, ORD_PERSISTENT));
        engine.registry().register(EventTypeSpec.of(TYPE_NON_PERSISTENT, ORD_NON_PERSISTENT));

        AtomicBoolean processorInvoked = new AtomicBoolean(false);
        engine.loop().registerProcessor(TYPE_PERSISTENT, (descriptors, payloads) -> {
            payloads.forEach(EventPayload::close);
            processorInvoked.set(true);
        });

        engine.start();

        // Publish ONLY a non-persistent event — MUST NOT reach the batch processor
        engine.bus().publish(nonPersistentDescriptor(ORD_NON_PERSISTENT), EventPayload.empty());

        Thread.sleep(500);
        assertThat(processorInvoked.get())
                .as("Non-persistent events MUST NOT be delivered to the outbox batch processor. "
                    + "Only events with FLAG_PERSISTENT are enqueued and processed by the EventLoop.")
                .isFalse();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static EventDescriptor nonPersistentDescriptor(int ordinal) {
        UUID id = UUID.randomUUID();
        return new EventDescriptor(
                id.getMostSignificantBits(), id.getLeastSignificantBits(),
                0L, 0L,
                ordinal,
                EventDescriptor.FLAG_ASYNC,
                System.currentTimeMillis());
    }

    private static EventDescriptor persistentDescriptor(int ordinal) {
        UUID id = UUID.randomUUID();
        return new EventDescriptor(
                id.getMostSignificantBits(), id.getLeastSignificantBits(),
                0L, 0L,
                ordinal,
                EventDescriptor.FLAG_PERSISTENT | EventDescriptor.FLAG_ASYNC,
                System.currentTimeMillis());
    }
}



