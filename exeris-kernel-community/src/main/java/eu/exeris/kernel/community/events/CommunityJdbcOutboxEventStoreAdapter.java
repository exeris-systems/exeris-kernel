/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.events;

import eu.exeris.kernel.community.persistence.jdbc.CommunityJdbcEventStore;
import eu.exeris.kernel.core.events.outbox.OutboxBrokerPort;
import eu.exeris.kernel.core.events.outbox.OutboxEventStore;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventTypeSpec;
import eu.exeris.kernel.spi.persistence.EventStore;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.PersistenceStatement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Community binding of {@link OutboxEventStore} over {@link CommunityJdbcEventStore}, giving
 * the Core outbox orchestrator a PostgreSQL-backed pending/delivered/DLQ surface.
 *
 * <p>Each of the three operations opens its own {@link PersistenceConnection} and runs inside
 * one transaction, rolling back on any {@code RuntimeException} and rethrowing.
 *
 * <p>A row whose event type the {@link CommunityEventRegistry} does not (yet) have an ordinal
 * for is assigned one on the fly, drawn from a counter local to this adapter instance and
 * starting at {@code 1_000_000}, and that assignment is registered into the same shared
 * {@link CommunityEventRegistry} this adapter was constructed with — see
 * {@code resolveOrdinal}/{@code registerDynamicType}.
 */
@SuppressWarnings("PMD.AvoidCatchingGenericException")
final class CommunityJdbcOutboxEventStoreAdapter implements OutboxEventStore {

    private static final String SQL_INSERT_DLQ =
            "INSERT INTO exeris_outbox_dlq "
                    + "(id, stream_id, event_type, payload, occurred_at, failure_reason) "
                    + "VALUES ($1, $2, $3, $4, $5, $6)";

    private static final String SQL_DELETE_OUTBOX =
            "DELETE FROM exeris_outbox WHERE id = $1";

    private final PersistenceEngine persistenceEngine;
    private final CommunityEventRegistry eventRegistry;
    private final AtomicInteger dynamicOrdinalSequence = new AtomicInteger(1_000_000);
    private final Map<String, Integer> dynamicOrdinals = new ConcurrentHashMap<>();

    /* default */ CommunityJdbcOutboxEventStoreAdapter(
            PersistenceEngine persistenceEngine, CommunityEventRegistry eventRegistry) {
        this.persistenceEngine = Objects.requireNonNull(persistenceEngine, "persistenceEngine");
        this.eventRegistry = Objects.requireNonNull(eventRegistry, "eventRegistry");
    }

    /**
     * Reads up to {@code maxItems} undelivered rows and commits immediately, releasing the
     * {@code FOR UPDATE SKIP LOCKED} row locks the underlying query takes.
     *
     * <p>Committing here — rather than holding the transaction open across the broker
     * dispatch — is correct for the Community single-orchestrator model, since rows already
     * marked via {@link #markDelivered} or {@link #moveToDlq} are excluded by their
     * {@code published_at IS NULL} filter. A multi-orchestrator deployment would need an
     * inflight/claimed column to prevent a second poller re-reading the same rows between this
     * commit and the corresponding {@link #markDelivered} call.
     *
     * @param maxItems maximum number of events to return (must be &gt; 0)
     * @return ordered list of pending entries; empty list if the outbox is empty
     */
    @SuppressWarnings("PMD.CloseResource")
    @Override
    public List<OutboxBrokerPort.OutboxEntry> pollPending(int maxItems) {
        try (PersistenceConnection connection = persistenceEngine.openConnection()) {
            connection.beginTransaction();
            try {
                EventStore store = new CommunityJdbcEventStore(connection);
                List<EventStore.OutboxEvent> events = store.pollPending(maxItems);
                List<OutboxBrokerPort.OutboxEntry> entries = new ArrayList<>(events.size());
                for (EventStore.OutboxEvent event : events) {
                    EventDescriptor descriptor = toDescriptor(event);
                    EventPayload payload = CommunityHeapEventPayload.wrap(event.payload());
                    entries.add(new OutboxBrokerPort.OutboxEntry(descriptor, payload));
                }
                // NOTE: committing here releases the FOR UPDATE SKIP LOCKED row locks.
                // This is acceptable for the Community single-orchestrator model — rows already
                // marked via markDelivered/moveToDlq are filtered by published_at IS NULL.
                // Multi-orchestrator HA scenarios would require an inflight/claimed column to
                // prevent concurrent re-poll between commit and markDelivered.
                connection.commit();
                return entries;
            } catch (RuntimeException pollException) {
                if (connection.inTransaction()) {
                    connection.rollback();
                }
                throw pollException;
            }
        }
    }

    /**
     * Marks each descriptor's row published, in one transaction; a no-op if {@code delivered}
     * is empty (no connection is opened).
     *
     * @param delivered descriptors of events that have been confirmed delivered
     */
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    @Override
    public void markDelivered(List<EventDescriptor> delivered) {
        if (delivered.isEmpty()) {
            return;
        }
        try (PersistenceConnection connection = persistenceEngine.openConnection()) {
            connection.beginTransaction();
            try {
                EventStore store = new CommunityJdbcEventStore(connection);
                for (EventDescriptor descriptor : delivered) {
                    store.markPublished(new UUID(descriptor.eventIdHigh(), descriptor.eventIdLow()));
                }
                connection.commit();
            } catch (RuntimeException runtimeException) {
                if (connection.inTransaction()) {
                    connection.rollback();
                }
                throw runtimeException;
            }
        }
    }

    /**
     * Inserts a DLQ record and deletes the corresponding outbox row in one transaction, so a
     * failed event never reappears in {@link #pollPending}.
     *
     * @param descriptor the failed event descriptor
     * @param payload    original payload; copied into the DLQ row, caller retains ownership
     * @param reason     human-readable failure reason; recorded as {@code "unknown"} if
     *                   {@code null} or blank
     */
    @Override
    public void moveToDlq(EventDescriptor descriptor, EventPayload payload, String reason) {
        String eventType = eventRegistry.nameOfOrdinal(descriptor.eventTypeOrdinal());
        String streamId = new UUID(descriptor.streamIdHigh(), descriptor.streamIdLow()).toString();
        String failureReason = reason == null || reason.isBlank() ? "unknown" : reason;
        byte[] bytes = CommunityHeapEventPayload.copyBytes(payload);
        UUID eventId = new UUID(descriptor.eventIdHigh(), descriptor.eventIdLow());

        try (PersistenceConnection connection = persistenceEngine.openConnection()) {
            connection.beginTransaction();
            try {
                try (PersistenceStatement statement = connection.prepare(SQL_INSERT_DLQ)) {
                    statement.bindString(0, eventId.toString())
                            .bindString(1, streamId)
                            .bindString(2, eventType)
                            .bindBytes(3, bytes)
                            .bindLong(4, descriptor.occurredAtEpochMs())
                            .bindString(5, failureReason)
                            .executeUpdate();
                }
                // Delete the outbox row in the same transaction so it is never re-polled.
                try (PersistenceStatement deleteStmt = connection.prepare(SQL_DELETE_OUTBOX)) {
                    deleteStmt.bindString(0, eventId.toString()).executeUpdate();
                }
                connection.commit();
            } catch (RuntimeException dlqException) {
                if (connection.inTransaction()) {
                    connection.rollback();
                }
                throw dlqException;
            }
        }
    }

    private EventDescriptor toDescriptor(EventStore.OutboxEvent event) {
        UUID eventId = event.eventId();
        UUID streamId = streamUuid(event.aggregateId());
        int ordinal = resolveOrdinal(event.eventType());
        return EventDescriptor.of(
                eventId.getMostSignificantBits(),
                eventId.getLeastSignificantBits(),
                streamId.getMostSignificantBits(),
                streamId.getLeastSignificantBits(),
                ordinal,
                EventDescriptor.FLAG_PERSISTENT | EventDescriptor.FLAG_ASYNC,
                event.occurredAt()
        );
    }

    private int resolveOrdinal(String eventType) {
        int existing = eventRegistry.ordinalOf(eventType);
        if (existing >= 0) {
            return existing;
        }
        return dynamicOrdinals.computeIfAbsent(eventType, this::registerDynamicType);
    }

    private int registerDynamicType(String eventType) {
        while (true) {
            int ordinal = dynamicOrdinalSequence.getAndIncrement();
            try {
                eventRegistry.register(EventTypeSpec.ofPersistent(eventType, ordinal));
                return ordinal;
            } catch (RuntimeException _) {
                int alreadyRegistered = eventRegistry.ordinalOf(eventType);
                if (alreadyRegistered >= 0) {
                    return alreadyRegistered;
                }
            }
        }
    }

    private static UUID streamUuid(String aggregateId) {
        return UUID.nameUUIDFromBytes(aggregateId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
