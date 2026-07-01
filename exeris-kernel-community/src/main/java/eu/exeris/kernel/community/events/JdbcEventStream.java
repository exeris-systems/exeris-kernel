/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.events;

import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventStream;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceStatement;
import eu.exeris.kernel.spi.persistence.QueryResult;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * JDBC-cursor-backed {@link EventStream} (ADR-049) over {@code exeris_event_log} replay queries.
 *
 * <p>Owns the {@link PersistenceConnection} / {@link PersistenceStatement} / {@link QueryResult}
 * triple opened by {@link JdbcEventStreamReader} and releases them on {@link #close()} (idempotent,
 * result → statement → connection order). Iteration is lazy with single-row lookahead: each
 * {@code next()} copies exactly one row's payload into a fresh {@link CommunityHeapEventPayload}
 * (refCount 1), so per-event allocation is bounded and the consumer owns each payload's close.
 */
final class JdbcEventStream implements EventStream {

    private static final int PAYLOAD_COLUMN = 0;

    private final PersistenceConnection connection;
    private final PersistenceStatement statement;
    private final QueryResult result;
    private boolean closed;

    /* default */ JdbcEventStream(PersistenceConnection connection, PersistenceStatement statement,
                                  QueryResult result) {
        this.connection = connection;
        this.statement = statement;
        this.result = result;
    }

    @Override
    public Iterator<EventPayload> iterator() {
        return new PayloadIterator();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // Unnamed resources (JEP 456): reverse declaration order releases result → statement →
        // connection. The close is the whole point — there is no body.
        try (PersistenceConnection _ = connection;
             PersistenceStatement _ = statement;
             QueryResult _ = result) {
            // close-only
        }
    }

    private final class PayloadIterator implements Iterator<EventPayload> {

        private boolean fetched;
        private boolean hasRow;

        @Override
        public boolean hasNext() {
            if (!fetched) {
                hasRow = result.next();
                fetched = true;
            }
            return hasRow;
        }

        @Override
        public EventPayload next() {
            if (!hasNext()) {
                throw new NoSuchElementException("event stream exhausted");
            }
            byte[] bytes = result.row().getBytes(PAYLOAD_COLUMN);
            fetched = false;
            return CommunityHeapEventPayload.wrap(bytes == null ? new byte[0] : bytes);
        }
    }
}
