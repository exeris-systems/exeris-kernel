/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.events;

import eu.exeris.kernel.spi.events.EventStream;
import eu.exeris.kernel.spi.events.EventStreamReader;
import eu.exeris.kernel.spi.events.StreamId;
import eu.exeris.kernel.spi.exceptions.events.EventEngineException;
import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.PersistenceStatement;
import eu.exeris.kernel.spi.persistence.QueryResult;

import java.time.Instant;
import java.util.Objects;
import java.util.function.ToIntFunction;

/**
 * Community JDBC binding of {@link EventStreamReader} (ADR-049) over {@code exeris_event_log}.
 *
 * <h2>Ordering (ADR-049)</h2>
 * <p>{@link #replayFromVersion(StreamId, long)} yields a stream's events in strictly ascending
 * {@code committed_sequence} order — the read side of the durable-log ordering boundary the
 * {@link eu.exeris.kernel.community.events.JdbcEventStreamAppender} establishes. {@link #replayFrom}
 * filters by {@code occurred_at} but still orders by sequence; {@link #replayByType} scans across
 * streams by ordinal + time.
 *
 * <h2>Cursor Lifecycle</h2>
 * <p>Each replay opens a fresh {@link PersistenceConnection} and hands the connection / statement /
 * cursor to a {@link JdbcEventStream}, which the caller MUST close. The reader itself holds no
 * shared resource (the {@link PersistenceEngine} is owned by the persistence subsystem), so
 * {@link #close()} is a no-op and does not invalidate already-returned streams.
 *
 * <h2>The Wall</h2>
 * <p>Depends only on SPI persistence contracts + the Community payload. The {@code replayByType}
 * name→ordinal resolution is injected as a {@link ToIntFunction} (wired to the engine's
 * {@code EventRegistry::ordinalOf}) so the reader stays decoupled from the concrete registry.
 *
 * @since 0.10.0
 */
public final class JdbcEventStreamReader implements EventStreamReader {

    private static final String SQL_REPLAY_FROM_VERSION =
            "SELECT payload FROM exeris_event_log "
                    + "WHERE stream_id_high = ? AND stream_id_low = ? AND committed_sequence >= ? "
                    + "ORDER BY committed_sequence";

    private static final String SQL_REPLAY_FROM_TIME =
            "SELECT payload FROM exeris_event_log "
                    + "WHERE stream_id_high = ? AND stream_id_low = ? AND occurred_at >= ? "
                    + "ORDER BY committed_sequence";

    private static final String SQL_REPLAY_BY_TYPE =
            "SELECT payload FROM exeris_event_log "
                    + "WHERE event_type_ordinal = ? AND occurred_at >= ? "
                    + "ORDER BY occurred_at, stream_id_high, stream_id_low, committed_sequence";

    private final PersistenceEngine engine;
    private final String engineName;
    private final ToIntFunction<String> typeOrdinalResolver;

    public JdbcEventStreamReader(PersistenceEngine engine, String engineName,
                                 ToIntFunction<String> typeOrdinalResolver) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.engineName = Objects.requireNonNull(engineName, "engineName");
        this.typeOrdinalResolver = Objects.requireNonNull(typeOrdinalResolver, "typeOrdinalResolver");
    }

    @Override
    public EventStream replayFrom(StreamId streamId, Instant fromTimestamp) {
        Objects.requireNonNull(streamId, "streamId");
        Objects.requireNonNull(fromTimestamp, "fromTimestamp");
        long epochMilli = fromTimestamp.toEpochMilli();
        return openStream(SQL_REPLAY_FROM_TIME, stmt -> stmt
                .bindLong(0, streamId.streamIdHigh())
                .bindLong(1, streamId.streamIdLow())
                .bindLong(2, epochMilli));
    }

    @Override
    public EventStream replayFromVersion(StreamId streamId, long fromVersion) {
        Objects.requireNonNull(streamId, "streamId");
        return openStream(SQL_REPLAY_FROM_VERSION, stmt -> stmt
                .bindLong(0, streamId.streamIdHigh())
                .bindLong(1, streamId.streamIdLow())
                .bindLong(2, fromVersion));
    }

    @Override
    public EventStream replayByType(String eventType, Instant fromTimestamp) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(fromTimestamp, "fromTimestamp");
        int ordinal = typeOrdinalResolver.applyAsInt(eventType);
        long epochMilli = fromTimestamp.toEpochMilli();
        return openStream(SQL_REPLAY_BY_TYPE, stmt -> stmt
                .bindInt(0, ordinal)
                .bindLong(1, epochMilli));
    }

    @Override
    public void close() {
        // No shared resource to release: each replay opens (and its EventStream owns) a fresh
        // connection / statement / cursor. Idempotent by construction; does not invalidate any
        // stream returned earlier.
    }

    // openStream transfers ownership of the connection / statement / cursor to the returned
    // EventStream (released on EventStream.close()). On the failure path the connection is closed,
    // which releases any prepared statement transitively.
    private EventStream openStream(String sql, Binder binder) {
        PersistenceConnection connection = engine.openConnection();
        try {
            PersistenceStatement statement = connection.prepare(sql);
            binder.bind(statement);
            QueryResult result = statement.executeQuery();
            return new JdbcEventStream(connection, statement, result);
        } catch (PersistenceProviderException ppe) {
            connection.close();
            throw new EventEngineException(
                    "Failed to open event replay stream [engine=" + engineName + ']', ppe);
        }
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PersistenceStatement statement);
    }
}
