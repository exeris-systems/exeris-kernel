/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.time.TimeSource;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportStream;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Package-private client connection pool managing persistent HTTP/1.1 transport connections.
 *
 * <p>Employs LIFO (Last-In-First-Out) connection reuse per authority to keep the warmest TCP
 * connections active, avoiding keep-alive expiration while pruning idle sockets. Uses a synchronized
 * LIFO queue (ArrayDeque) with atomic retirement protection, pre-sized to {@code maxIdlePerPeer}
 * to prevent array resizing, with zero carrier thread pinning under JDK 25. Single-allocation
 * {@link PooledConnection} wrapper is created on release. Time decisions go through {@link TimeSource}
 * per ADR-082.
 *
 * @since 0.12
 */
// LIFO pool state, eviction, and capacity management
@SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.TooManyMethods", "PMD.GodClass"})
final class CommunityHttpClientConnectionPool implements AutoCloseable {

    private static final int MAX_EVICTION_ATTEMPTS = 8;

    private static final String EVENT_ACQUIRE_MISS = "ACQUIRE_MISS";
    private static final String EVENT_ACQUIRE_HIT = "ACQUIRE_HIT";
    private static final String EVENT_RELEASE = "RELEASE";
    private static final String EVENT_EVICT_IDLE = "EVICT_IDLE";
    private static final String EVENT_EVICT_CAPACITY = "EVICT_CAPACITY";

    private final int maxTotalConnections;
    private final int maxIdlePerPeer;
    private final long idleTimeoutNanos;
    private final TimeSource timeSource;
    private final Function<String, DequeHolder> dequeHolderFactory;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger totalConnections = new AtomicInteger(0);
    private final ConcurrentMap<String, DequeHolder> pool = new ConcurrentHashMap<>();

    /* default */ CommunityHttpClientConnectionPool(HttpConfig config) {
        this(config, null);
    }

    /* default */ CommunityHttpClientConnectionPool(HttpConfig config, TimeSource timeSource) {
        Objects.requireNonNull(config, "config must not be null");
        this.timeSource = timeSource != null ? timeSource : KernelProviders.timeSource();
        this.maxTotalConnections = Math.max(config.maxConnections(), 1);
        this.maxIdlePerPeer = Math.clamp(config.maxConnections(), 1, 64);
        long timeoutMillis = config.idleTimeoutMillis();
        if (timeoutMillis == 0) {
            this.idleTimeoutNanos = Long.MAX_VALUE;
        } else if (timeoutMillis > 0) {
            long maxSafeMillis = Long.MAX_VALUE / 1_000_000L;
            this.idleTimeoutNanos = timeoutMillis >= maxSafeMillis
                    ? Long.MAX_VALUE
                    : timeoutMillis * 1_000_000L;
        } else {
            this.idleTimeoutNanos = 30_000_000_000L;
        }
        this.dequeHolderFactory = auth -> new DequeHolder(auth, this.maxIdlePerPeer);
    }

    private long currentNanoTime() {
        return timeSource.nanoTime();
    }

    /* default */ PooledConnection acquire(String authority) {
        if (closed.get()) {
            return null;
        }
        DequeHolder holder = pool.get(authority);
        if (holder == null) {
            CommunityHttpClientPoolEvent.emit(EVENT_ACQUIRE_MISS, authority, totalConnections.get());
            return null;
        }
        PooledConnection pooled = pollUsable(holder, authority);
        if (pooled == null && holder.tryRetireIfEmpty()) {
            pool.remove(authority, holder);
        }
        return pooled;
    }

    private PooledConnection pollUsable(DequeHolder holder, String authority) {
        long now = currentNanoTime();
        while (true) {
            PooledConnection pooled = holder.poll();
            if (pooled == null) {
                CommunityHttpClientPoolEvent.emit(EVENT_ACQUIRE_MISS, authority, totalConnections.get());
                return null;
            }
            decrementTotalConnections();
            if (pooled.isUsable(idleTimeoutNanos, now)) {
                CommunityHttpClientPoolEvent.emit(EVENT_ACQUIRE_HIT, authority, totalConnections.get());
                return pooled;
            }
            CommunityHttpClientPoolEvent.emit(EVENT_EVICT_IDLE, authority, totalConnections.get());
            pooled.close();
        }
    }

    /* default */ void release(String authority, TransportConnection connection, TransportStream stream) {
        if (shouldReject(connection, stream)) {
            closeQuietly(stream, connection);
            return;
        }

        DequeHolder holder = reserveHolder(authority);
        if (holder == null) {
            closeQuietly(stream, connection);
            return;
        }

        if (!tryAcquireCapacity(authority)) {
            holder.unreserveSlot();
            tryPruneRetired(authority, holder);
            closeQuietly(stream, connection);
            return;
        }

        if (closed.get()) {
            holder.unreserveSlot();
            decrementTotalConnections();
            closeQuietly(stream, connection);
            return;
        }

        PooledConnection pooled = new PooledConnection(connection, stream, currentNanoTime());
        if (!holder.offerReserved(pooled)) {
            decrementTotalConnections();
            closeQuietly(stream, connection);
            return;
        }
        CommunityHttpClientPoolEvent.emit(EVENT_RELEASE, authority, totalConnections.get());
        if (closed.get()) {
            drainAndClose();
        }
    }

    private DequeHolder reserveHolder(String authority) {
        while (!closed.get()) {
            DequeHolder candidate = pool.computeIfAbsent(authority, dequeHolderFactory);
            if (candidate.tryReserveSlot(maxIdlePerPeer)) {
                return candidate;
            }
            if (!candidate.isRetired()) {
                return null;
            }
            pool.remove(authority, candidate);
        }
        return null;
    }

    private boolean shouldReject(TransportConnection connection, TransportStream stream) {
        return closed.get() || connection == null || !connection.isOpen()
                || (stream != null && stream.hasPendingData());
    }

    private boolean tryAcquireCapacity(String authority) {
        while (true) {
            int current = totalConnections.get();
            if (current < maxTotalConnections) {
                if (totalConnections.compareAndSet(current, current + 1)) {
                    return true;
                }
            } else if (evictAndTransferCapacitySlot(authority)) {
                return true;
            } else if (totalConnections.get() >= maxTotalConnections) {
                return false;
            }
        }
    }

    private boolean evictAndTransferCapacitySlot(String preferredExclusion) {
        return evictExpiredConnection(true) || evictCrossAuthorityConnection(preferredExclusion, true);
    }

    private boolean evictExpiredConnection(boolean transferSlot) {
        long now = currentNanoTime();
        for (ConcurrentMap.Entry<String, DequeHolder> entry : pool.entrySet()) {
            DequeHolder holder = entry.getValue();
            PooledConnection pooled = holder.pollOldestIfExpired(idleTimeoutNanos, now);
            if (pooled != null) {
                retireAndClose(entry.getKey(), holder, pooled, EVENT_EVICT_IDLE, transferSlot);
                return true;
            }
            tryPruneRetired(entry.getKey(), holder);
        }
        return false;
    }

    private boolean evictCrossAuthorityConnection(String preferredExclusion, boolean transferSlot) {
        for (int attempt = 0; attempt < MAX_EVICTION_ATTEMPTS; attempt++) {
            DequeHolder oldestHolder = findOldestHolder(preferredExclusion);
            if (oldestHolder == null) {
                return false;
            }

            PooledConnection pooled = oldestHolder.pollOldest();
            if (pooled != null) {
                retireAndClose(oldestHolder.authority(), oldestHolder, pooled, EVENT_EVICT_CAPACITY, transferSlot);
                return true;
            }
            tryPruneRetired(oldestHolder.authority(), oldestHolder);
        }
        return false;
    }

    private DequeHolder findOldestHolder(String preferredExclusion) {
        DequeHolder oldestHolder = null;
        long maxAgeNanos = Long.MIN_VALUE;
        long now = currentNanoTime();

        for (ConcurrentMap.Entry<String, DequeHolder> entry : pool.entrySet()) {
            if (Objects.equals(entry.getKey(), preferredExclusion)) {
                continue;
            }
            DequeHolder holder = entry.getValue();
            PooledConnection candidate = holder.peekOldest();
            if (candidate != null) {
                long ageNanos = now - candidate.lastUsedNanos();
                if (oldestHolder == null || ageNanos > maxAgeNanos) {
                    maxAgeNanos = ageNanos;
                    oldestHolder = holder;
                }
            } else {
                tryPruneRetired(entry.getKey(), holder);
            }
        }
        return oldestHolder;
    }

    private void retireAndClose(String authority, DequeHolder holder, PooledConnection pooled,
                               String reason, boolean transferSlot) {
        if (!transferSlot) {
            decrementTotalConnections();
        }
        CommunityHttpClientPoolEvent.emit(reason, authority, totalConnections.get());
        pooled.close();
        tryPruneRetired(authority, holder);
    }

    private void tryPruneRetired(String authority, DequeHolder holder) {
        if (holder.tryRetireIfEmpty()) {
            pool.remove(authority, holder);
        }
    }

    private void decrementTotalConnections() {
        totalConnections.updateAndGet(count -> Math.max(0, count - 1));
    }

    /* default */ void pruneIfEmpty(String authority) {
        if (authority == null) {
            return;
        }
        DequeHolder holder = pool.get(authority);
        if (holder != null && holder.tryRetireIfEmpty()) {
            pool.remove(authority, holder);
        }
    }

    /* default */ int authorityCount() {
        return pool.size();
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
                PooledConnection pooled = holder.pollAndRetire();
                if (pooled == null) {
                    break;
                }
                decrementTotalConnections();
                pooled.close();
            }
        }
        pool.clear();
        totalConnections.set(0);
    }

    /* default */ int totalConnections() {
        return totalConnections.get();
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
        private final String authority;
        private final Deque<PooledConnection> connections;
        private boolean retired;
        private int reservedSlots;

        /* default */ DequeHolder(String authority, int initialCapacity) {
            this.authority = authority;
            this.connections = new ArrayDeque<>(initialCapacity);
        }

        /* default */ String authority() {
            return authority;
        }

        /* default */ synchronized int size() {
            return retired ? 0 : connections.size();
        }

        /* default */ synchronized boolean tryReserveSlot(int max) {
            if (retired || connections.size() + reservedSlots >= max) {
                return false;
            }
            reservedSlots++;
            return true;
        }

        /* default */ synchronized void unreserveSlot() {
            if (reservedSlots > 0) {
                reservedSlots--;
            }
        }

        /* default */ synchronized boolean offerReserved(PooledConnection pooled) {
            if (reservedSlots > 0) {
                reservedSlots--;
            }
            if (retired) {
                return false;
            }
            connections.offerFirst(pooled);
            return true;
        }

        /* default */ synchronized PooledConnection poll() {
            if (retired) {
                return null;
            }
            return connections.pollFirst();
        }

        /* default */ synchronized PooledConnection pollOldestIfExpired(long timeoutNanos, long nowNanos) {
            if (retired) {
                return null;
            }
            PooledConnection oldest = connections.peekLast();
            if (oldest != null && !oldest.isUsable(timeoutNanos, nowNanos)) {
                return connections.pollLast();
            }
            return null;
        }

        /* default */ synchronized PooledConnection peekOldest() {
            if (retired) {
                return null;
            }
            return connections.peekLast();
        }

        /* default */ synchronized PooledConnection pollOldest() {
            if (retired) {
                return null;
            }
            return connections.pollLast();
        }

        /* default */ synchronized boolean tryRetireIfEmpty() {
            if (retired) {
                return true;
            }
            if (connections.isEmpty() && reservedSlots == 0) {
                retired = true;
                return true;
            }
            return false;
        }

        /* default */ synchronized boolean isRetired() {
            return retired;
        }

        /* default */ synchronized PooledConnection pollAndRetire() {
            retired = true;
            reservedSlots = 0;
            return connections.pollFirst();
        }
    }

    /* default */ static final class PooledConnection {
        private final TransportConnection connection;
        private final TransportStream stream;
        private final long lastUsedNanos;

        /* default */ PooledConnection(TransportConnection connection, TransportStream stream, long lastUsedNanos) {
            this.connection = connection;
            this.stream = stream;
            this.lastUsedNanos = lastUsedNanos;
        }

        /* default */ TransportConnection connection() {
            return connection;
        }

        /* default */ TransportStream stream() {
            return stream;
        }

        /* default */ long lastUsedNanos() {
            return lastUsedNanos;
        }

        /* default */ boolean isUsable(long timeoutNanos, long nowNanos) {
            return connection.isOpen()
                    && (stream == null || !stream.hasPendingData())
                    && (nowNanos - lastUsedNanos) < timeoutNanos;
        }

        /* default */ void close() {
            closeQuietly(stream, connection);
        }
    }
}
