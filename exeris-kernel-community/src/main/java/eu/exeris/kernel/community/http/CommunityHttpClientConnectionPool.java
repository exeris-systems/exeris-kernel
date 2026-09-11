/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportStream;

import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Package-private client connection pool managing persistent HTTP/1.1 transport connections.
 *
 * <p>Employs LIFO (Last-In-First-Out) connection reuse per authority to keep the warmest TCP
 * connections active, avoiding keep-alive expiration while pruning idle sockets. Operations
 * are non-blocking and lock-free.
 *
 * @since 0.12
 */
@SuppressWarnings("PMD.CloseResource")
final class CommunityHttpClientConnectionPool implements AutoCloseable {

    private final int maxIdlePerPeer;
    private final long idleTimeoutNanos;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ConcurrentMap<String, DequeHolder> pool = new ConcurrentHashMap<>();

    /* default */ CommunityHttpClientConnectionPool(HttpConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        this.maxIdlePerPeer = Math.clamp(config.maxConnections(), 16, 256);
        this.idleTimeoutNanos = config.idleTimeoutMillis() > 0
                ? config.idleTimeoutMillis() * 1_000_000L
                : 30_000_000_000L;
    }

    /* default */ PooledConnection acquire(String authority) {
        if (closed.get()) {
            return null;
        }
        DequeHolder holder = pool.get(authority);
        if (holder == null) {
            return null;
        }
        while (true) {
            PooledConnection pooled = holder.poll();
            if (pooled == null) {
                break;
            }
            if (pooled.isUsable(idleTimeoutNanos)) {
                return pooled;
            }
            pooled.close();
        }
        return null;
    }

    /* default */ void release(String authority, TransportConnection connection, TransportStream stream) {
        if (closed.get() || connection == null || !connection.isOpen()) {
            closeQuietly(stream, connection);
            return;
        }
        DequeHolder holder = pool.computeIfAbsent(authority, _ -> new DequeHolder());
        PooledConnection pooled = new PooledConnection(connection, stream);
        if (!holder.offer(pooled, maxIdlePerPeer)) {
            closeQuietly(stream, connection);
            return;
        }
        if (closed.get()) {
            drainAndClose();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        drainAndClose();
    }

    private void drainAndClose() {
        for (DequeHolder holder : pool.values()) {
            while (true) {
                PooledConnection pooled = holder.poll();
                if (pooled == null) {
                    break;
                }
                pooled.close();
            }
        }
        pool.clear();
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    /* default */ static void closeQuietly(AutoCloseable... closeables) {
        if (closeables == null) {
            return;
        }
        for (AutoCloseable c : closeables) {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception _) {
                    // best-effort cleanup on close
                }
            }
        }
    }

    private static final class DequeHolder {
        private final Deque<PooledConnection> connections = new ConcurrentLinkedDeque<>();
        private final AtomicInteger count = new AtomicInteger(0);

        /* default */ DequeHolder() {
            // default access
        }

        /* default */ PooledConnection poll() {
            PooledConnection pooled = connections.pollFirst();
            if (pooled != null) {
                count.decrementAndGet();
            }
            return pooled;
        }

        /* default */ boolean offer(PooledConnection pooled, int max) {
            if (count.get() >= max) {
                return false;
            }
            connections.offerFirst(pooled);
            count.incrementAndGet();
            return true;
        }
    }

    /* default */ static final class PooledConnection {
        private final TransportConnection connection;
        private final TransportStream stream;
        private final long lastUsedNanos;

        /* default */ PooledConnection(TransportConnection connection, TransportStream stream) {
            this.connection = connection;
            this.stream = stream;
            this.lastUsedNanos = System.nanoTime();
        }

        /* default */ TransportConnection connection() {
            return connection;
        }

        /* default */ TransportStream stream() {
            return stream;
        }

        /* default */ boolean isUsable(long timeoutNanos) {
            return connection.isOpen() && (System.nanoTime() - lastUsedNanos) < timeoutNanos;
        }

        /* default */ void close() {
            closeQuietly(stream, connection);
        }
    }
}
