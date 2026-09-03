/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK: Abstract base for {@link EventStore} (Outbox Pattern) contract verification.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@code append(OutboxEvent)} persists the event within the current transaction</li>
 *   <li>{@code pollPending(int)} returns pending events in insertion order</li>
 *   <li>{@code markPublished(UUID)} is idempotent — calling twice is safe</li>
 *   <li>Transactional atomicity: event is visible only after commit</li>
 * </ul>
 *
 * @since 0.5.0
 */
public abstract class AbstractEventStoreTck {

    /**
     * Creates a fully bootstrapped {@link PersistenceEngine}.
     */
    protected abstract PersistenceEngine createEngine();

    /**
     * Creates an {@link EventStore} bound to the given connection.
     */
    protected abstract EventStore createEventStore(PersistenceConnection connection);

    private PersistenceEngine engine;

    @BeforeEach
    final void setUpEngine() {
        engine = createEngine();
    }

    @AfterEach
    final void tearDownEngine() {
        engine.close();
    }

    private OutboxEvent testEvent(String eventType) {
        return new OutboxEvent(
                UUID.randomUUID(),
                "aggregate-1",
                "Order",
                eventType,
                ("{\"type\":\"" + eventType + "\"}").getBytes(StandardCharsets.UTF_8),
                System.currentTimeMillis()
        );
    }

    // =========================================================================
    // append() contract
    // =========================================================================

    @Nested
    @DisplayName("append() contract")
    class AppendContract {

        @Test
        @DisplayName("append() persists event within transaction")
        void appendPersistsEvent() {
            try (PersistenceConnection conn = engine.openConnection()) {
                EventStore store = createEventStore(conn);
                conn.beginTransaction();
                OutboxEvent event = testEvent("OrderPlaced");
                store.append(event);
                conn.commit();

                conn.beginTransaction();
                List<OutboxEvent> pending = store.pollPending(10);
                assertThat(pending)
                        .as("pollPending() MUST return the appended event after commit")
                        .isNotEmpty()
                        .extracting(OutboxEvent::eventType)
                        .contains("OrderPlaced");
                conn.rollback();
            }
        }

        @Test
        @DisplayName("append() outside transaction throws IllegalStateException")
        void appendOutsideTransactionThrows() {
            try (PersistenceConnection conn = engine.openConnection()) {
                EventStore store = createEventStore(conn);
                OutboxEvent event = testEvent("BadEvent");
                assertThatThrownBy(() -> store.append(event))
                        .isInstanceOf(IllegalStateException.class);
            }
        }
    }

    // =========================================================================
    // pollPending() contract
    // =========================================================================

    @Nested
    @DisplayName("pollPending() contract")
    class PollPendingContract {

        @Test
        @DisplayName("pollPending() returns events in insertion order")
        void pollReturnsInOrder() {
            try (PersistenceConnection conn = engine.openConnection()) {
                EventStore store = createEventStore(conn);
                conn.beginTransaction();
                store.append(testEvent("First"));
                store.append(testEvent("Second"));
                store.append(testEvent("Third"));
                conn.commit();

                conn.beginTransaction();
                List<OutboxEvent> pending = store.pollPending(10);
                assertThat(pending)
                        .as("pollPending() MUST return all 3 appended events in insertion order")
                        .isNotEmpty()
                        .extracting(OutboxEvent::eventType)
                        .containsExactly("First", "Second", "Third");
                conn.rollback();
            }
        }

        @Test
        @DisplayName("pollPending() respects maxBatchSize")
        void pollRespectsMaxBatch() {
            try (PersistenceConnection conn = engine.openConnection()) {
                EventStore store = createEventStore(conn);
                conn.beginTransaction();
                store.append(testEvent("A"));
                store.append(testEvent("B"));
                store.append(testEvent("C"));
                conn.commit();

                conn.beginTransaction();
                List<OutboxEvent> batch = store.pollPending(2);
                assertThat(batch).hasSizeLessThanOrEqualTo(2);
                conn.rollback();
            }
        }
    }

    // =========================================================================
    // markPublished() contract
    // =========================================================================

    @Nested
    @DisplayName("markPublished() contract")
    class MarkPublishedContract {

        @Test
        @DisplayName("markPublished() removes event from pending")
        void markPublishedRemovesFromPending() {
            try (PersistenceConnection conn = engine.openConnection()) {
                EventStore store = createEventStore(conn);

                conn.beginTransaction();
                OutboxEvent event = testEvent("ToPublish");
                store.append(event);
                conn.commit();

                conn.beginTransaction();
                store.markPublished(event.eventId());
                conn.commit();

                conn.beginTransaction();
                List<OutboxEvent> pending = store.pollPending(100);
                assertThat(pending)
                        .as("After markPublished(), the event MUST no longer appear in pollPending()")
                        .noneSatisfy(e -> assertThat(e.eventId()).isEqualTo(event.eventId()));
                conn.rollback();
            }
        }

        @Test
        @DisplayName("markPublished() is idempotent")
        void markPublishedIsIdempotent() {
            try (PersistenceConnection conn = engine.openConnection()) {
                EventStore store = createEventStore(conn);
                conn.beginTransaction();
                OutboxEvent event = testEvent("IdempotentTest");
                store.append(event);
                conn.commit();

                conn.beginTransaction();
                store.markPublished(event.eventId());
                conn.commit();

                // Second call — must not throw
                conn.beginTransaction();
                store.markPublished(event.eventId());
                conn.commit();
            }
        }
    }

    // =========================================================================
    // Transactional atomicity
    // =========================================================================

    @Nested
    @DisplayName("Transactional atomicity")
    class TransactionalAtomicity {

        @Test
        @DisplayName("rollback discards appended event")
        void rollbackDiscardsEvent() {
            try (PersistenceConnection conn = engine.openConnection()) {
                EventStore store = createEventStore(conn);
                conn.beginTransaction();
                OutboxEvent event = testEvent("RolledBack");
                store.append(event);
                conn.rollback();

                conn.beginTransaction();
                List<OutboxEvent> pending = store.pollPending(100);
                assertThat(pending)
                        .as("After rollback, the appended event MUST NOT be visible in pollPending()")
                        .noneSatisfy(e -> assertThat(e.eventId()).isEqualTo(event.eventId()));
                conn.rollback();
            }
        }
    }
}
