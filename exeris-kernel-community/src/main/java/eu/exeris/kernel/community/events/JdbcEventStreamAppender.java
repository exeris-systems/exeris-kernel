/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.events;

import eu.exeris.kernel.community.events.jfr.EventLogAppendConflictEvent;
import eu.exeris.kernel.community.events.jfr.EventLogAppendFailedEvent;
import eu.exeris.kernel.spi.events.AppendResult;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventStreamAppender;
import eu.exeris.kernel.spi.events.StreamId;
import eu.exeris.kernel.spi.exceptions.events.EventEngineException;
import eu.exeris.kernel.spi.exceptions.events.EventStreamAppendConflictException;
import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.PersistenceStatement;
import eu.exeris.kernel.spi.persistence.QueryResult;

import java.sql.SQLException;
import java.util.Objects;

/**
 * Community JDBC binding of {@link EventStreamAppender} (ADR-049) backed by the
 * {@code exeris_event_log} table (V0.10.0 migration).
 *
 * <h2>Per-Stream Ordering + Optimistic Concurrency (ADR-049)</h2>
 * <p>The durable log owns the ordering / optimistic-concurrency boundary. Each stream has a 1-based
 * monotonic {@code committed_sequence} (its head); the composite primary key
 * {@code (stream_id_high, stream_id_low, committed_sequence)} is both the replay order and the
 * uniqueness constraint the OCC append relies on. An append reads the current head
 * ({@code SELECT COALESCE(MAX(committed_sequence), 0)}) and inserts at {@code head + 1}:
 * <ul>
 *   <li>{@link EventStreamAppender#ANY_VERSION} — append unconditionally; on a concurrent race
 *       (SQLSTATE class 23 on the PK) retry at the new head, bounded by
 *       {@value #MAX_APPEND_ATTEMPTS} attempts.</li>
 *   <li>{@code expectedVersion >= 0} — require the head to equal {@code expectedVersion}; on
 *       mismatch (or a lost INSERT race) fail closed with
 *       {@link EventStreamAppendConflictException} ({@code EX-EVENT-6008}), leaving the head
 *       unchanged.</li>
 * </ul>
 *
 * <h2>SPI-Routed Connection Acquisition (ADR-022)</h2>
 * <p>Connections are acquired through the {@link PersistenceEngine} SPI, mirroring
 * {@code JdbcFlowSnapshotStore}. The read-head + INSERT run in one {@code READ_COMMITTED}
 * transaction; a fresh transaction is used per {@code ANY_VERSION} retry because a constraint
 * violation aborts the current transaction on PostgreSQL.
 *
 * <h2>The Wall</h2>
 * <p>Depends only on the SPI persistence contracts and the Community heap payload — no JDBC type
 * escapes into SPI/Core. Portable across Postgres and H2 (no {@code RETURNING} / {@code ON CONFLICT}).
 *
 * <h2>RAII Ownership</h2>
 * <p>The appender takes ownership of the supplied {@link EventPayload}: it copies the bytes and
 * closes the payload exactly once (success or failure).
 *
 * @since 0.10
 */
public final class JdbcEventStreamAppender implements EventStreamAppender {

    private static final String SQL_HEAD =
            "SELECT COALESCE(MAX(committed_sequence), 0) FROM exeris_event_log "
                    + "WHERE stream_id_high = ? AND stream_id_low = ?";

    private static final String SQL_INSERT =
            "INSERT INTO exeris_event_log ("
                    + "stream_id_high, stream_id_low, committed_sequence, "
                    + "event_id_high, event_id_low, event_type_ordinal, event_flags, occurred_at, payload) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    // SQLSTATE class 23 = ANSI integrity-constraint violation (Postgres 23505, H2 23505). Used to
    // detect a concurrent-INSERT race on the composite PK under READ_COMMITTED (TOCTOU vs SELECT MAX).
    private static final String SQLSTATE_CLASS_INTEGRITY = "23";
    private static final String SQLSTATE_UNKNOWN = "UNKNOWN";
    private static final long EMPTY_HEAD = 0L;
    private static final int MAX_APPEND_ATTEMPTS = 8;

    private final PersistenceEngine engine;
    private final String engineName;

    public JdbcEventStreamAppender(PersistenceEngine engine, String engineName) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.engineName = Objects.requireNonNull(engineName, "engineName");
    }

    @Override
    public AppendResult append(StreamId streamId, long expectedVersion,
                               EventDescriptor descriptor, EventPayload payload) {
        Objects.requireNonNull(streamId, "streamId");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(payload, "payload");
        final byte[] bytes;
        try (payload) { // RAII: the appender owns the payload and releases it exactly once
            bytes = CommunityHeapEventPayload.copyBytes(payload);
        }
        return appendCopied(streamId, expectedVersion, descriptor, bytes);
    }

    // The read-head + INSERT + SQLSTATE-23 race resolution is one transactional unit; the
    // conflict throw and the ANY_VERSION retry are transactional control flow, not error misuse.
    @SuppressWarnings("PMD.CyclomaticComplexity")
    private AppendResult appendCopied(StreamId streamId, long expectedVersion,
                                      EventDescriptor descriptor, byte[] bytes) {
        PersistenceProviderException lastRace = null;
        for (int attempt = 1; attempt <= MAX_APPEND_ATTEMPTS; attempt++) {
            try (PersistenceConnection conn = engine.openConnection()) {
                conn.beginTransaction();
                long head = queryHead(conn, streamId);
                if (expectedVersion != ANY_VERSION && expectedVersion != head) {
                    conn.rollback();
                    EventLogAppendConflictEvent.emit(engineName, EventLogAppendConflictEvent.PHASE_HEAD_MISMATCH,
                            streamId.streamType(), expectedVersion, head);
                    throw EventStreamAppendConflictException.versionConflict(
                            streamId.streamType(), expectedVersion, head);
                }
                long next = head + 1L;
                try {
                    insert(conn, streamId, next, descriptor, bytes);
                    conn.commit();
                    return new AppendResult(next);
                } catch (PersistenceProviderException ppe) {
                    conn.rollback();
                    if (!isIntegrityConstraintViolation(ppe)) {
                        EventLogAppendFailedEvent.emit(
                                engineName, streamId.streamType(), extractSqlState(ppe), ppe);
                        throw new EventEngineException("Failed to append event to stream log", ppe);
                    }
                    if (expectedVersion != ANY_VERSION) {
                        // A concurrent writer took the head the OCC caller expected — fail closed,
                        // preserving the SQLSTATE-23 cause that revealed the lost race.
                        EventLogAppendConflictEvent.emit(engineName, EventLogAppendConflictEvent.PHASE_INSERT_TOCTOU,
                                streamId.streamType(), expectedVersion, next);
                        throw EventStreamAppendConflictException.versionConflict(
                                streamId.streamType(), expectedVersion, next, ppe);
                    }
                    lastRace = ppe; // ANY_VERSION: retry in a fresh transaction at the new head
                }
            }
        }
        EventLogAppendFailedEvent.emit(engineName, streamId.streamType(), extractSqlState(lastRace), lastRace);
        throw new EventEngineException("Append retry budget exhausted under contention", lastRace);
    }

    private long queryHead(PersistenceConnection conn, StreamId streamId) {
        try (PersistenceStatement stmt = conn.prepare(SQL_HEAD)) {
            stmt.bindLong(0, streamId.streamIdHigh());
            stmt.bindLong(1, streamId.streamIdLow());
            try (QueryResult result = stmt.executeQuery()) {
                return result.next() ? result.row().getLong(0) : EMPTY_HEAD;
            }
        }
    }

    private void insert(PersistenceConnection conn, StreamId streamId, long sequence,
                        EventDescriptor descriptor, byte[] bytes) {
        try (PersistenceStatement stmt = conn.prepare(SQL_INSERT)) {
            stmt.bindLong(0, streamId.streamIdHigh());
            stmt.bindLong(1, streamId.streamIdLow());
            stmt.bindLong(2, sequence);
            stmt.bindLong(3, descriptor.eventIdHigh());
            stmt.bindLong(4, descriptor.eventIdLow());
            stmt.bindInt(5, descriptor.eventTypeOrdinal());
            stmt.bindInt(6, descriptor.flags());
            stmt.bindLong(7, descriptor.occurredAtEpochMs());
            stmt.bindBytes(8, bytes);
            stmt.executeUpdate();
        }
    }

    private static boolean isIntegrityConstraintViolation(Throwable thrown) {
        for (Throwable current = thrown; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && state.startsWith(SQLSTATE_CLASS_INTEGRITY)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String extractSqlState(Throwable thrown) {
        for (Throwable current = thrown; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && !state.isBlank()) {
                    return state;
                }
            }
        }
        return SQLSTATE_UNKNOWN;
    }
}
