/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.core.persistence.RequestSessionLifecycleEvent;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.PersistenceStatement;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.kernel.spi.persistence.TransactionIsolation;

import java.util.Objects;

/**
 * Lazy per-request persistence session holder for {@code ScopedValue} binding.
 *
 * <p>Bound once at HTTP dispatch boundary. Acquires a connection on first
 * {@link #getOrAcquire()} call and reuses it for the entire request lifetime.
 * Released via {@link #release()} which returns the connection to pool.
 *
 * <p>NOT thread-safe — designed for single-VT-per-request access within a ScopedValue scope.
 *
 * @since 0.5.0
 */
public final class PersistenceSessionBox {
    public static final ScopedValue<PersistenceSessionBox> REQUEST_SESSION =
            ScopedValue.newInstance();

    private static final String SHARED_SCOPE_KEY = "shared";

    private final PersistenceEngine engine;
    private final TransactionIsolation isolation;
    private final boolean readOnly;
    private RequestPersistenceSession session;
    private String sessionScopeKey;
    private boolean released;

    public PersistenceSessionBox(PersistenceEngine engine,
                                        TransactionIsolation isolation,
                                        boolean readOnly) {
        this.engine = engine;
        this.isolation = isolation;
        this.readOnly = readOnly;
    }

    public static PersistenceSessionBox currentOrNull() {
        return REQUEST_SESSION.isBound() ? REQUEST_SESSION.get() : null;
    }

    public boolean belongsTo(PersistenceEngine candidate) {
        return engine != null && Objects.equals(engine, candidate);
    }

    /**
     * Returns the shared-scope session for this request, acquiring a connection on first call.
     * Returns {@code null} if no {@code PersistenceEngine} is available or if an existing
     * request session was already acquired under a different scope key.
     */
    public RequestPersistenceSession getOrAcquire() {
        return getOrAcquire(this::openBackingConnection);
    }

    public RequestPersistenceSession getOrAcquire(ConnectionOpener opener) {
        return getOrAcquireInternal(SHARED_SCOPE_KEY, opener, true);
    }

    /**
     * Returns request session if absent-or-matching for a given scope key.
     * If session already exists with a different scope key, returns {@code null}.
     */
    public RequestPersistenceSession getOrAcquireIfScopeMatches(String scopeKey, ConnectionOpener opener) {
        Objects.requireNonNull(scopeKey, "scopeKey must not be null");
        return getOrAcquireInternal(scopeKey, opener, true);
    }

    private RequestPersistenceSession getOrAcquireInternal(String scopeKey,
                                                          ConnectionOpener opener,
                                                          boolean requireScopeMatch) {
        Objects.requireNonNull(opener, "opener must not be null");
        if (released) {
            RequestSessionLifecycleEvent.emit(
                    "REJECTED_RELEASED",
                    isolation,
                    readOnly,
                    session != null);
            throw new IllegalStateException("Request persistence session already released");
        }
        if (engine == null) {
            RequestSessionLifecycleEvent.emit(
                    "BYPASS_NO_ENGINE",
                    isolation,
                    readOnly,
                    false);
            return null;
        }
        if (session == null) {
            session = openRequestSession(opener);
            sessionScopeKey = scopeKey;
            RequestSessionLifecycleEvent.emit(
                    "ACQUIRE",
                    isolation,
                    readOnly,
                    true);
        } else if (requireScopeMatch && !Objects.equals(sessionScopeKey, scopeKey)) {
            RequestSessionLifecycleEvent.emit(
                    "BYPASS_SCOPE_MISMATCH",
                    isolation,
                    readOnly,
                    true);
            return null;
        } else {
            RequestSessionLifecycleEvent.emit(
                    "REUSE",
                    isolation,
                    readOnly,
                    true);
        }
        return session;
    }

    public PersistenceConnection requestScopedConnection() {
        RequestPersistenceSession activeSession = getOrAcquire();
        if (activeSession == null) {
            return null;
        }
        return requestScopedConnection(activeSession);
    }

    public PersistenceConnection requestScopedConnection(RequestPersistenceSession requestSession) {
        if (requestSession == null) {
            return null;
        }
        return new NonOwningPersistenceConnection(requestSession.connection());
    }

    private RequestPersistenceSession openRequestSession(ConnectionOpener opener) {
        PersistenceConnection conn = opener.open();
        return RequestPersistenceSession.active(conn, isolation, readOnly);
    }

    private PersistenceConnection openBackingConnection() {
        if (engine instanceof PhysicalConnectionSource source) {
            return source.openPhysical();
        }
        return engine.openConnection();
    }

    /**
     * Releases the connection back to the pool. No-op if never acquired or already released.
     */
    public void release() {
        if (released) {
            RequestSessionLifecycleEvent.emit(
                    "RELEASE_IDEMPOTENT",
                    isolation,
                    readOnly,
                    session != null);
            released = true;
            return;
        }
        if (session == null) {
            RequestSessionLifecycleEvent.emit(
                    "RELEASE_NO_SESSION",
                    isolation,
                    readOnly,
                    false);
            released = true;
            return;
        }
        RequestSessionLifecycleEvent.emit(
                "RELEASE",
                isolation,
                readOnly,
                true);
        released = true;
        session.connection().close();
    }

    @FunctionalInterface
    public interface ConnectionOpener {
        PersistenceConnection open();
    }

    private abstract static class ForwardingPersistenceConnection implements PersistenceConnection {
        private final PersistenceConnection delegate;

        private ForwardingPersistenceConnection(PersistenceConnection delegate) {
            this.delegate = delegate;
        }

        @Override
        public PersistenceStatement prepare(String sql) {
            return delegate.prepare(sql);
        }

        @Override
        public QueryResult executeQuery(String sql) {
            return delegate.executeQuery(sql);
        }

        @Override
        public long executeUpdate(String sql) {
            return delegate.executeUpdate(sql);
        }

        @Override
        public void beginTransaction() {
            delegate.beginTransaction();
        }

        @Override
        public void beginTransaction(TransactionIsolation transactionIsolation, boolean readOnlyConnection) {
            delegate.beginTransaction(transactionIsolation, readOnlyConnection);
        }

        @Override
        public void commit() {
            delegate.commit();
        }

        @Override
        public void rollback() {
            delegate.rollback();
        }

        @Override
        public boolean inTransaction() {
            return delegate.inTransaction();
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }
    }

    private static final class NonOwningPersistenceConnection extends ForwardingPersistenceConnection {

        private NonOwningPersistenceConnection(PersistenceConnection delegate) {
            super(delegate);
        }

        @Override
        public void close() {
            // Request-scoped lifecycle is managed by PersistenceSessionBox.release().
        }
    }
}
