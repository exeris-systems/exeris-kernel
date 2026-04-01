/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.persistence.jdbc;

import eu.exeris.kernel.spi.persistence.EventStore;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceStatement;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.kernel.spi.persistence.RowCursor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Community JDBC implementation of {@link EventStore}.
 *
 * <p>This implementation keeps the SPI contract implementation-blind and maps
 * outbox operations to SQL statements executed on the provided
 * {@link PersistenceConnection}. Connection ownership and pooling are managed by
 * the caller (typically {@code CommunityPersistenceEngine} / request scope).
 *
 * @since 0.5.0
 */
public final class CommunityJdbcEventStore implements EventStore {

    private static final String SQL_APPEND =
            "INSERT INTO exeris_outbox " +
                    "(id, aggregate_id, aggregate_type, event_type, payload, occurred_at) " +
                    "VALUES ($1, $2, $3, $4, $5, $6)";

    private static final String SQL_POLL_PENDING =
            "SELECT id, aggregate_id, aggregate_type, event_type, payload, occurred_at " +
                    "FROM exeris_outbox " +
                    "WHERE published_at IS NULL " +
                "ORDER BY outbox_seq ASC " +
                    "LIMIT $1 " +
                    "FOR UPDATE SKIP LOCKED";

    private static final String SQL_MARK_PUBLISHED =
            "UPDATE exeris_outbox " +
                    "SET published_at = $1 " +
                    "WHERE id = $2";

    private final PersistenceConnection connection;

    public CommunityJdbcEventStore(PersistenceConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
    }

    @Override
    public void append(OutboxEvent event) {
        ensureTransactionActive();
        Objects.requireNonNull(event, "event must not be null");

        try (PersistenceStatement statement = connection.prepare(SQL_APPEND)) {
            statement.bindString(0, event.eventId().toString())
                    .bindString(1, event.aggregateId())
                    .bindString(2, event.aggregateType())
                    .bindString(3, event.eventType())
                    .bindBytes(4, event.payload())
                    .bindLong(5, event.occurredAt())
                    .executeUpdate();
        }
    }

    @Override
    public List<OutboxEvent> pollPending(int maxBatchSize) {
        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException("maxBatchSize must be > 0, got: " + maxBatchSize);
        }

        List<OutboxEvent> events = new ArrayList<>(maxBatchSize);
        try (PersistenceStatement statement = connection.prepare(SQL_POLL_PENDING)) {
            statement.bindInt(0, maxBatchSize);
            try (QueryResult result = statement.executeQuery()) {
                while (result.next()) {
                    RowCursor row = result.row();
                    events.add(new OutboxEvent(
                            UUID.fromString(row.getString(0)),
                            row.getString(1),
                            row.getString(2),
                            row.getString(3),
                            row.getBytes(4),
                            row.getLong(5)
                    ));
                }
            }
        }
        return events;
    }

    @Override
    public void markPublished(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId must not be null");

        try (PersistenceStatement statement = connection.prepare(SQL_MARK_PUBLISHED)) {
            statement.bindLong(0, System.currentTimeMillis())
                    .bindString(1, eventId.toString())
                    .executeUpdate();
        }
    }

    private void ensureTransactionActive() {
        if (!connection.inTransaction()) {
            throw new IllegalStateException("EventStore.append() requires an active transaction");
        }
    }
}
