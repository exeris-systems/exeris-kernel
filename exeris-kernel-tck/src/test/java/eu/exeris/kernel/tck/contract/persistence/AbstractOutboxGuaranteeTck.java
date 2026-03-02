/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.tck.contract.persistence;

import eu.exeris.kernel.spi.persistence.EventStore;
import eu.exeris.kernel.spi.persistence.EventStore.OutboxEvent;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK: Abstract base for Outbox at-least-once delivery guarantee verification.
 *
 * <h2>Contract (#25)</h2>
 * <p>Extends the basic {@link AbstractEventStoreTck} with three failure-mode scenarios:
 * <ol>
 *   <li><b>Broker outage</b> — events accumulate in {@link EventStore} when the broker
 *       is unavailable; all are delivered once it recovers.</li>
 *   <li><b>Engine rebuild</b> — committed events survive a force-close + rebuild
 *       (simulates JVM restart).</li>
 *   <li><b>Replay idempotency</b> — {@link EventStore#markPublished(UUID)} events
 *       must never reappear in {@link EventStore#pollPending(int)} after rebuild.</li>
 * </ol>
 *
 * <h2>SPI used — nothing invented</h2>
 * <ul>
 *   <li>{@link PersistenceEngine#openConnection()} / {@link PersistenceConnection}</li>
 *   <li>{@link EventStore#append(OutboxEvent)}</li>
 *   <li>{@link EventStore#pollPending(int)}</li>
 *   <li>{@link EventStore#markPublished(UUID)}</li>
 * </ul>
 *
 * <h2>Broker simulation</h2>
 * <p>TCK is broker-blind. {@link #simulateBrokerDown()} and {@link #simulateBrokerUp()}
 * are template methods — the subclass decides the mechanism (mock, flag, socket close).
 *
 * <h2>Engine rebuild simulation</h2>
 * <p>{@link #rebuildEngine()} returns a fresh {@link PersistenceEngine} pointing at the
 * same durable store — simulates JVM restart without wiping data.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * class CommunityOutboxGuaranteeTck extends AbstractOutboxGuaranteeTck {
 *     \@Override protected PersistenceEngine createEngine()  { return new CommunityPersistenceEngine(...); }
 *     \@Override protected PersistenceEngine rebuildEngine() { return new CommunityPersistenceEngine(...); }
 *     \@Override protected EventStore createEventStore(PersistenceConnection c) { return new CommunityEventStore(c); }
 *     \@Override protected void simulateBrokerDown() { publisher.pause(); }
 *     \@Override protected void simulateBrokerUp()   { publisher.resume(); }
 *     \@Override protected int  deliveredToBroker()  { return publisher.receivedCount(); }
 * }
 * }</pre>
 *
 * @since 0.5.0
 * @see AbstractEventStoreTck
 */
@DisplayName("Outbox Guarantee TCK")
public abstract class AbstractOutboxGuaranteeTck {

    private static final int BROKER_EVENT_COUNT = 10_000;

    // =========================================================================
    // Template methods
    // =========================================================================

    /** Creates a fresh {@link PersistenceEngine} (first boot). */
    protected abstract PersistenceEngine createEngine();

    /**
     * Creates a second {@link PersistenceEngine} backed by the <em>same durable store</em>
     * as the first — simulates JVM restart without data loss.
     */
    protected abstract PersistenceEngine rebuildEngine();

    /** Creates an {@link EventStore} bound to the given connection. */
    protected abstract EventStore createEventStore(PersistenceConnection connection);

    /**
     * Simulates the broker going offline.
     * The outbox publisher must stop delivering but MUST NOT drop events from the store.
     */
    protected abstract void simulateBrokerDown();

    /** Restores the broker; the outbox publisher must resume delivery. */
    protected abstract void simulateBrokerUp();

    /**
     * Returns the total count of events successfully delivered to the broker
     * (acknowledged) since the last engine start.
     */
    protected abstract int deliveredToBroker();

    // =========================================================================
    // Lifecycle
    // =========================================================================

    private PersistenceEngine engine;

    @BeforeEach
    final void setUpEngine() {
        engine = createEngine();
    }

    @AfterEach
    final void tearDownEngine() {
        if (engine != null) engine.close();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private OutboxEvent event(String aggregateId, String type) {
        return new OutboxEvent(
                UUID.randomUUID(), aggregateId, "Order", type,
                ("{\"type\":\"" + type + "\"}").getBytes(StandardCharsets.UTF_8),
                System.currentTimeMillis());
    }

    private void appendAndCommit(EventStore store, PersistenceConnection conn, OutboxEvent ev) {
        conn.beginTransaction();
        store.append(ev);
        conn.commit();
    }

    private int drainPollPending(EventStore store, PersistenceConnection conn) {
        conn.beginTransaction();
        int total = 0;
        List<OutboxEvent> batch;
        do {
            batch = store.pollPending(1_000);
            total += batch.size();
        } while (!batch.isEmpty());
        conn.commit();
        return total;
    }

    // =========================================================================
    // Test 1 — Pull-The-Plug Broker Test
    // =========================================================================

    @Nested
    @DisplayName("Pull-The-Plug Broker — events survive broker outage")
    class PullThePlugBrokerTest {

        @Test
        @Timeout(value = 120, unit = TimeUnit.SECONDS)
        @DisplayName("10 000 events accumulate in pollPending() while broker is offline")
        void eventsAccumulateInEventStoreWhenBrokerDown() {
            simulateBrokerDown();
            try {
                try (PersistenceConnection conn = engine.openConnection()) {
                    EventStore store = createEventStore(conn);
                    for (int i = 0; i < BROKER_EVENT_COUNT; i++) {
                        appendAndCommit(store, conn, event("order-" + i, "OrderPlaced"));
                    }
                    int found = drainPollPending(store, conn);
                    assertThat(found)
                            .as(
                                "AT-LEAST-ONCE VIOLATION: broker was offline, but only %d of %d " +
                                "events were in pollPending().\n" +
                                "Events MUST accumulate in EventStore when broker is unavailable.",
                                found, BROKER_EVENT_COUNT
                            )
                            .isGreaterThanOrEqualTo(BROKER_EVENT_COUNT);
                }
            } finally {
                simulateBrokerUp();
            }
        }

        @Test
        @Timeout(value = 120, unit = TimeUnit.SECONDS)
        @DisplayName("All events are delivered after broker is restored")
        void eventsDeliveredAfterBrokerRestore() {
            simulateBrokerDown();
            try (PersistenceConnection conn = engine.openConnection()) {
                EventStore store = createEventStore(conn);
                for (int i = 0; i < BROKER_EVENT_COUNT; i++) {
                    appendAndCommit(store, conn, event("order-" + i, "OrderPlaced"));
                }
            }

            simulateBrokerUp();

            // Poll until outbox poller delivers all pending events (max 30 s)
            int delivered = 0;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline) {
                delivered = deliveredToBroker();
                if (delivered >= BROKER_EVENT_COUNT) break;
                LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(1));
            }

            assertThat(delivered)
                    .as(
                        "AT-LEAST-ONCE VIOLATION: after broker restore, only %d of %d events " +
                        "were confirmed delivered.",
                        delivered, BROKER_EVENT_COUNT
                    )
                    .isGreaterThanOrEqualTo(BROKER_EVENT_COUNT);
        }
    }

    // =========================================================================
    // Test 2 — EventStore Durability Test
    // =========================================================================

    @Nested
    @DisplayName("EventStore Durability — committed events survive engine rebuild")
    class EventStoreDurabilityTest {

        @Test
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        @DisplayName("Committed events are visible in a fresh engine instance (simulates JVM restart)")
        void committedEventsSurviveEngineRebuild() {
            List<UUID> writtenIds = new ArrayList<>();

            try (PersistenceConnection conn = engine.openConnection()) {
                EventStore store = createEventStore(conn);
                for (int i = 0; i < 200; i++) {
                    OutboxEvent ev = event("agg-" + i, "SurvivalEvent");
                    appendAndCommit(store, conn, ev);
                    writtenIds.add(ev.eventId());
                }
            }

            engine.close();
            engine = rebuildEngine();

            List<UUID> foundIds = new ArrayList<>();
            try (PersistenceConnection conn = engine.openConnection()) {
                EventStore store = createEventStore(conn);
                conn.beginTransaction();
                List<OutboxEvent> batch;
                do {
                    batch = store.pollPending(200);
                    batch.forEach(e -> foundIds.add(e.eventId()));
                } while (!batch.isEmpty());
                conn.rollback();
            }

            assertThat(foundIds)
                    .as(
                        "DURABILITY VIOLATION: after engine rebuild, %d of %d committed events " +
                        "are missing from pollPending().",
                        writtenIds.size() - foundIds.size(), writtenIds.size()
                    )
                    .containsAll(writtenIds);
        }
    }

    // =========================================================================
    // Test 3 — Outbox Replay Idempotency
    // =========================================================================

    @Nested
    @DisplayName("Outbox Replay Idempotency — markPublished() prevents duplicate delivery")
    class OutboxReplayIdempotencyTest {

        @Test
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        @DisplayName("markPublished() events do NOT reappear in pollPending() after engine rebuild")
        void publishedEventsDoNotReappearAfterRebuild() {
            int total  = 100;
            int marked = 60;
            List<UUID> publishedIds = new ArrayList<>();
            List<UUID> pendingIds   = new ArrayList<>();

            try (PersistenceConnection conn = engine.openConnection()) {
                EventStore store = createEventStore(conn);
                for (int i = 0; i < total; i++) {
                    OutboxEvent ev = event("idem-" + i, "IdempotentEvent");
                    appendAndCommit(store, conn, ev);
                    if (i < marked) publishedIds.add(ev.eventId());
                    else            pendingIds.add(ev.eventId());
                }
            }

            try (PersistenceConnection conn = engine.openConnection()) {
                EventStore store = createEventStore(conn);
                conn.beginTransaction();
                publishedIds.forEach(store::markPublished);
                conn.commit();
            }

            engine.close();
            engine = rebuildEngine();

            List<UUID> foundAfterRebuild = new ArrayList<>();
            try (PersistenceConnection conn = engine.openConnection()) {
                EventStore store = createEventStore(conn);
                conn.beginTransaction();
                List<OutboxEvent> batch;
                do {
                    batch = store.pollPending(100);
                    batch.forEach(e -> foundAfterRebuild.add(e.eventId()));
                } while (!batch.isEmpty());
                conn.rollback();
            }

            assertThat(foundAfterRebuild)
                    .as(
                        "IDEMPOTENCY VIOLATION: markPublished() events reappeared in pollPending() "
                        + "after engine rebuild. markPublished() MUST be durable."
                    )
                    .doesNotContainAnyElementsOf(publishedIds)
                    .as("All %d un-published events MUST still be in pollPending() after rebuild",
                            pendingIds.size())
                    .containsAll(pendingIds);
        }

        @Test
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        @DisplayName("Concurrent markPublished() calls for the same eventId are idempotent")
        void markPublishedIsIdempotentUnderConcurrentCalls() throws InterruptedException {
            OutboxEvent ev = event("idem-concurrent", "ConcurrentMark");
            try (PersistenceConnection conn = engine.openConnection()) {
                appendAndCommit(createEventStore(conn), conn, ev);
            }

            int concurrency     = 20;
            CountDownLatch done  = new CountDownLatch(concurrency);
            AtomicInteger errors = new AtomicInteger();

            for (int i = 0; i < concurrency; i++) {
                Thread.ofVirtual().name("exeris-mark-", i).start(() -> {
                    try (PersistenceConnection conn = engine.openConnection()) {
                        EventStore store = createEventStore(conn);
                        conn.beginTransaction();
                        store.markPublished(ev.eventId());
                        conn.commit();
                    } catch (Exception _) {
                        errors.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
            assertThat(errors.get())
                    .as("Concurrent markPublished() calls MUST be idempotent — zero exceptions expected")
                    .isZero();

            try (PersistenceConnection conn = engine.openConnection()) {
                EventStore store = createEventStore(conn);
                conn.beginTransaction();
                List<OutboxEvent> pending = store.pollPending(100);
                assertThat(pending).isNotNull();
                if (!pending.isEmpty()) {
                    assertThat(pending)
                            .extracting(OutboxEvent::eventId)
                            .as("Event must not reappear in pollPending() after concurrent markPublished()")
                            .doesNotContain(ev.eventId());
                }
                conn.rollback();
            }
        }
    }
}




