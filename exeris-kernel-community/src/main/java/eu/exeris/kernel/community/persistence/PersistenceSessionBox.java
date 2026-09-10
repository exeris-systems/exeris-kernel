/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.core.persistence.RequestSessionLifecycleEvent;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.PersistenceStatement;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.kernel.spi.persistence.TransactionIsolation;

import java.util.Objects;
import java.util.Optional;

/**
 * Lazy per-request persistence session holder for {@code ScopedValue} binding.
 *
 * <p>Bound once at HTTP dispatch boundary. Acquires a connection on first
 * {@link #getOrAcquire()} call and reuses it for the entire request lifetime.
 * Released via {@link #release()} which returns the connection to pool.
 *
 * <p>NOT thread-safe — designed for single-VT-per-request access within a ScopedValue scope.
 *
 * <p><b>Allocation:</b> allocates one wrapper object per {@link #requestScopedConnection()} call;
 * the backing {@link PersistenceConnection} itself is acquired at most once per box instance, on
 * the first {@link #getOrAcquire()}.
 * <p><b>Thread confinement:</b> owner thread — a box is created and used by the single virtual
 * thread executing the request inside whose {@code ScopedValue} scope it is bound; it holds no
 * internal synchronization, so concurrent use from more than one thread is undefined.
 * <p><b>Ownership:</b> the box owns the acquired connection from {@link #getOrAcquire()} until
 * {@link #release()}; every {@link PersistenceConnection} handed out through
 * {@link #requestScopedConnection()} is a non-owning view whose {@code close()} is a no-op, so
 * only {@link #release()} returns the physical connection to the pool.
 *
 * @since 0.5
 */
public final class PersistenceSessionBox {

    /**
     * Binds the session box active for the current request, for the lifetime of one HTTP
     * dispatch.
     *
     * @see #currentOrNull()
     */
    public static final ScopedValue<PersistenceSessionBox> REQUEST_SESSION =
            ScopedValue.newInstance();

    private static final String SHARED_SCOPE_KEY = "shared";

    private final PersistenceEngine engine;
    private final TransactionIsolation isolation;
    private final boolean readOnly;
    private RequestPersistenceSession session;
    private String sessionScopeKey;
    private boolean released;

    /**
     * Creates an unacquired session box for one request; no connection is opened until the
     * first {@link #getOrAcquire()}.
     *
     * @param engine    the engine to acquire the backing connection from; a {@code null} engine
     *                  makes every acquire attempt a no-op that returns {@code null}
     * @param isolation the transaction isolation requested when the connection is acquired
     * @param readOnly  the read-only hint requested when the connection is acquired
     */
    public PersistenceSessionBox(PersistenceEngine engine,
                                        TransactionIsolation isolation,
                                        boolean readOnly) {
        this.engine = engine;
        this.isolation = isolation;
        this.readOnly = readOnly;
    }

    /**
     * Returns the session box bound to the current request scope.
     *
     * @return the bound box, or {@code null} when {@link #REQUEST_SESSION} is not bound
     */
    public static PersistenceSessionBox currentOrNull() {
        return REQUEST_SESSION.isBound() ? REQUEST_SESSION.get() : null;
    }

    /**
     * Whether this box was created for {@code candidate} — the check a caller makes before
     * reusing a request-scoped session instead of acquiring its own connection.
     *
     * @param candidate the engine to compare against this box's engine
     * @return {@code true} when this box has a non-{@code null} engine equal to {@code candidate}
     */
    public boolean belongsTo(PersistenceEngine candidate) {
        return engine != null && Objects.equals(engine, candidate);
    }

    /**
     * Returns the shared-scope session for this request, acquiring a connection on first call.
     *
     * @return the active session; {@code null} if no {@code PersistenceEngine} is available, or
     *         if an existing request session was already acquired under a different scope key
     */
    public RequestPersistenceSession getOrAcquire() {
        return getOrAcquire(this::openBackingConnection);
    }

    /**
     * Returns the shared-scope session for this request, opening the backing connection through
     * {@code opener} on first call instead of the engine's default.
     *
     * @param opener supplies the backing connection when none is acquired yet; not invoked
     *               again once a session exists
     * @return the active session; {@code null} if an existing request session was already
     *         acquired under a different scope key
     */
    public RequestPersistenceSession getOrAcquire(ConnectionOpener opener) {
        return getOrAcquireInternal(SHARED_SCOPE_KEY, opener, true);
    }

    /**
     * Returns the request session, acquiring one under {@code scopeKey} on first call.
     *
     * @param scopeKey the isolation/tenant scope key the caller is addressing
     * @param opener   supplies the backing connection when none is acquired yet; not invoked
     *                 again once a session exists
     * @return the active session; {@code null} if an existing request session was already
     *         acquired under a different scope key
     * @throws NullPointerException if {@code scopeKey} is {@code null}
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

    /**
     * Acquires (or reuses) the shared-scope session and returns a non-owning view over its
     * connection.
     *
     * @return a connection whose {@code close()} is a no-op, or {@code null} under the same
     *         conditions as {@link #getOrAcquire()}
     */
    public PersistenceConnection requestScopedConnection() {
        RequestPersistenceSession activeSession = getOrAcquire();
        if (activeSession == null) {
            return null;
        }
        return requestScopedConnection(activeSession);
    }

    /**
     * Wraps {@code requestSession}'s connection in a non-owning view whose {@code close()} is a
     * no-op — this box, not the caller, remains responsible for returning the connection to the
     * pool through {@link #release()}.
     *
     * @param requestSession the session to wrap; may be {@code null}
     * @return the wrapped connection, or {@code null} when {@code requestSession} is {@code null}
     */
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

    /**
     * Supplies the backing connection a session box acquires on first use.
     */
    @FunctionalInterface
    public interface ConnectionOpener {

        /**
         * Opens a new backing connection.
         *
         * @return a freshly opened connection
         */
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
        public <T> Optional<T> unwrap(Class<T> type) {
            // Forward the unwrap seam to the backing connection so integration
            // bridges (e.g. the JDBC compat bridge, ADR-017) survive request-scope
            // wrapping. The wrapper itself takes precedence when assignable.
            if (type.isInstance(this)) {
                return Optional.of(type.cast(this));
            }
            return delegate.unwrap(type);
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
