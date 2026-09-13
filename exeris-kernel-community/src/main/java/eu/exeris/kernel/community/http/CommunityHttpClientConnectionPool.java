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
@SuppressWarnings("PMD.CyclomaticComplexity") // LIFO pool state, eviction, and capacity management
final class CommunityHttpClientConnectionPool implements AutoCloseable {

    private final int maxTotalConnections;
    private final int maxIdlePerPeer;
    private final long idleTimeoutNanos;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger totalConnections = new AtomicInteger(0);
    private final ConcurrentMap<String, DequeHolder> pool = new ConcurrentHashMap<>();

    /* default */ CommunityHttpClientConnectionPool(HttpConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        this.maxTotalConnections = Math.max(config.maxConnections(), 1);
        this.maxIdlePerPeer = Math.clamp(config.maxConnections(), 1, 64);
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
            CommunityHttpClientPoolEvent.emit("ACQUIRE_MISS", authority, totalConnections.get());
            return null;
        }
        PooledConnection pooled = pollUsable(holder, authority);
        if (pooled == null && holder.isEmpty()) {
            pool.remove(authority, holder);
        }
        return pooled;
    }

    private PooledConnection pollUsable(DequeHolder holder, String authority) {
        while (true) {
            PooledConnection pooled = holder.poll();
            if (pooled == null) {
                CommunityHttpClientPoolEvent.emit("ACQUIRE_MISS", authority, totalConnections.get());
                return null;
            }
            totalConnections.decrementAndGet();
            if (pooled.isUsable(idleTimeoutNanos)) {
                CommunityHttpClientPoolEvent.emit("ACQUIRE_HIT", authority, totalConnections.get());
                return pooled;
            }
            CommunityHttpClientPoolEvent.emit("EVICT_IDLE", authority, totalConnections.get());
            pooled.close();
        }
    }

    /* default */ void release(String authority, TransportConnection connection, TransportStream stream) {
        if (shouldReject(connection, stream)) {
            closeQuietly(stream, connection);
            return;
        }
        if (!tryAcquireCapacity()) {
            CommunityHttpClientPoolEvent.emit("EVICT_CAPACITY", authority, totalConnections.get());
            closeQuietly(stream, connection);
            return;
        }

        DequeHolder holder = pool.computeIfAbsent(authority, _ -> new DequeHolder());
        PooledConnection pooled = new PooledConnection(connection, stream);
        if (!holder.offer(pooled, maxIdlePerPeer)) {
            totalConnections.decrementAndGet();
            CommunityHttpClientPoolEvent.emit("EVICT_CAPACITY", authority, totalConnections.get());
            closeQuietly(stream, connection);
            if (holder.isEmpty()) {
                pool.remove(authority, holder);
            }
            return;
        }
        CommunityHttpClientPoolEvent.emit("RELEASE", authority, totalConnections.get());
        if (closed.get()) {
            drainAndClose();
        }
    }

    private boolean shouldReject(TransportConnection connection, TransportStream stream) {
        return closed.get() || connection == null || !connection.isOpen()
                || (stream != null && stream.hasPendingData());
    }

    private boolean tryAcquireCapacity() {
        if (totalConnections.incrementAndGet() > maxTotalConnections) {
            totalConnections.decrementAndGet();
            return false;
        }
        return true;
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
                totalConnections.decrementAndGet();
                pooled.close();
            }
        }
        pool.clear();
        totalConnections.set(0);
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    /* default */ static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception _) {
                // best-effort cleanup on close
            }
        }
    }

    /* default */ static void closeQuietly(AutoCloseable first, AutoCloseable second) {
        closeQuietly(first);
        closeQuietly(second);
    }

    private static final class DequeHolder {
        private final Deque<PooledConnection> connections = new ConcurrentLinkedDeque<>();
        private final AtomicInteger count = new AtomicInteger(0);

        /* default */ DequeHolder() {
            // default access
        }

        /* default */ PooledConnection poll() {
            while (true) {
                int current = count.get();
                if (current <= 0) {
                    return null;
                }
                if (count.compareAndSet(current, current - 1)) {
                    PooledConnection pooled = connections.pollFirst();
                    if (pooled != null) {
                        return pooled;
                    }
                    count.incrementAndGet();
                    return null;
                }
            }
        }

        /* default */ boolean offer(PooledConnection pooled, int max) {
            while (true) {
                int current = count.get();
                if (current >= max) {
                    return false;
                }
                if (count.compareAndSet(current, current + 1)) {
                    connections.offerFirst(pooled);
                    return true;
                }
            }
        }

        /* default */ boolean isEmpty() {
            return count.get() == 0 && connections.isEmpty();
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
            return connection.isOpen()
                    && (stream == null || !stream.hasPendingData())
                    && (System.nanoTime() - lastUsedNanos) < timeoutNanos;
        }

        /* default */ void close() {
            closeQuietly(stream, connection);
        }
    }
}
