/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpMode;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.time.TimeSource;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Community: HttpClient Connection Pool")
class CommunityHttpClientConnectionPoolTest {

    @Test
    @DisplayName("Acquire on empty pool returns null")
    void acquireEmptyReturnsNull() {
        try (CommunityHttpClientConnectionPool pool = new CommunityHttpClientConnectionPool(HttpConfig.defaultClient())) {
            assertThat(pool.acquire("localhost:8080")).isNull();
        }
    }

    @Test
    @DisplayName("Released connection is reused on subsequent acquire")
    void releaseAndAcquireRoundTrip() {
        try (CommunityHttpClientConnectionPool pool = new CommunityHttpClientConnectionPool(HttpConfig.defaultClient())) {
            FakeConnection conn = new FakeConnection();
            FakeStream stream = new FakeStream(conn);

            pool.release("localhost:8080", conn, stream);

            CommunityHttpClientConnectionPool.PooledConnection pooled = pool.acquire("localhost:8080");
            assertThat(pooled).isNotNull();
            assertThat(pooled.connection()).isSameAs(conn);
            assertThat(pooled.stream()).isSameAs(stream);

            // Pool is now empty again
            assertThat(pool.acquire("localhost:8080")).isNull();
        }
    }

    @Test
    @DisplayName("LIFO order returns most recently released connection first")
    void lifoOrder() {
        try (CommunityHttpClientConnectionPool pool = new CommunityHttpClientConnectionPool(HttpConfig.defaultClient())) {
            FakeConnection conn1 = new FakeConnection();
            FakeConnection conn2 = new FakeConnection();

            pool.release("localhost:8080", conn1, new FakeStream(conn1));
            pool.release("localhost:8080", conn2, new FakeStream(conn2));

            CommunityHttpClientConnectionPool.PooledConnection first = pool.acquire("localhost:8080");
            assertThat(first).isNotNull();
            assertThat(first.connection()).isSameAs(conn2);

            CommunityHttpClientConnectionPool.PooledConnection second = pool.acquire("localhost:8080");
            assertThat(second).isNotNull();
            assertThat(second.connection()).isSameAs(conn1);
        }
    }

    @Test
    @DisplayName("Closed connection is evicted and closed on acquire")
    void closedConnectionEvicted() {
        try (CommunityHttpClientConnectionPool pool = new CommunityHttpClientConnectionPool(HttpConfig.defaultClient())) {
            FakeConnection conn = new FakeConnection();
            FakeStream stream = new FakeStream(conn);

            pool.release("localhost:8080", conn, stream);

            // Simulate connection closed by remote peer
            conn.close();

            CommunityHttpClientConnectionPool.PooledConnection pooled = pool.acquire("localhost:8080");
            assertThat(pooled).isNull();
            assertThat(stream.closed.get()).isTrue();
        }
    }

    @Test
    @DisplayName("Pool close closes all pooled connections and streams")
    void poolCloseClosesAll() {
        FakeConnection conn = new FakeConnection();
        FakeStream stream = new FakeStream(conn);

        CommunityHttpClientConnectionPool pool = new CommunityHttpClientConnectionPool(HttpConfig.defaultClient());
        pool.release("localhost:8080", conn, stream);

        pool.close();

        assertThat(conn.closed.get()).isTrue();
        assertThat(stream.closed.get()).isTrue();
        assertThat(pool.acquire("localhost:8080")).isNull();
    }

    @Test
    @DisplayName("Concurrent acquire and release across virtual threads does not leak or orphan sockets")
    void concurrentAcquireReleaseUnderHeavyContentionDoesNotLeakOrOrphan() throws Exception {
        int threads = 30;
        int iterations = 100;
        java.util.concurrent.ConcurrentLinkedQueue<FakeConnection> createdConnections =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        try (CommunityHttpClientConnectionPool pool = new CommunityHttpClientConnectionPool(HttpConfig.defaultClient())) {
            Thread[] vts = new Thread[threads];
            for (int i = 0; i < threads; i++) {
                vts[i] = Thread.ofVirtual().start(() -> {
                    for (int j = 0; j < iterations; j++) {
                        CommunityHttpClientConnectionPool.PooledConnection acquired = pool.acquire("localhost:8080");
                        if (acquired != null) {
                            pool.release("localhost:8080", acquired.connection(), acquired.stream());
                        } else {
                            FakeConnection conn = new FakeConnection();
                            createdConnections.add(conn);
                            FakeStream stream = new FakeStream(conn);
                            pool.release("localhost:8080", conn, stream);
                        }
                    }
                });
            }
            for (Thread vt : vts) {
                vt.join();
            }

            pool.close();
            assertThat(pool.acquire("localhost:8080")).isNull();

            // Verify zero leaks: every single connection created under contention must be closed
            assertThat(createdConnections).isNotEmpty();
            for (FakeConnection conn : createdConnections) {
                assertThat(conn.closed.get())
                        .as("Every connection created must be closed after pool shutdown or capacity eviction")
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("Capacity eviction closes excess connections when pool is full")
    void capacityEvictionWhenPoolFull() {
        HttpConfig config = new HttpConfig(
                HttpMode.CLIENT, null, -1, 2, 30_000L, 100, 8192, 1024L * 1024L, false,
                HttpVersion.HTTP_2, null, 65536, 65536, 4096, 10L * 1024L * 1024L);
        try (CommunityHttpClientConnectionPool pool = new CommunityHttpClientConnectionPool(config)) {
            FakeConnection conn1 = new FakeConnection();
            FakeConnection conn2 = new FakeConnection();
            FakeConnection conn3 = new FakeConnection();

            pool.release("localhost:8080", conn1, new FakeStream(conn1));
            pool.release("localhost:8080", conn2, new FakeStream(conn2));
            pool.release("localhost:8080", conn3, new FakeStream(conn3));

            assertThat(conn3.closed.get()).isTrue();
            assertThat(pool.acquire("localhost:8080")).isNotNull();
            assertThat(pool.acquire("localhost:8080")).isNotNull();
            assertThat(pool.acquire("localhost:8080")).isNull();
        }
    }

    @Test
    @DisplayName("Idle timeout evicts and closes expired connection on acquire")
    void idleTimeoutEviction() {
        ManualTimeSource clock = new ManualTimeSource();
        // 100 ms idle timeout
        HttpConfig config = new HttpConfig(
                HttpMode.CLIENT, null, -1, 10, 100L, 100, 8192, 1024L * 1024L, false,
                HttpVersion.HTTP_2, null, 65536, 65536, 4096, 10L * 1024L * 1024L);
        try (CommunityHttpClientConnectionPool pool = new CommunityHttpClientConnectionPool(config, clock)) {
            FakeConnection conn = new FakeConnection();
            FakeStream stream = new FakeStream(conn);
            pool.release("localhost:8080", conn, stream);

            // Advance clock past idle timeout (150 ms > 100 ms) with zero sleeping
            clock.advanceMillis(150);

            assertThat(pool.acquire("localhost:8080")).isNull();
            assertThat(conn.closed.get()).isTrue();
            assertThat(stream.closed.get()).isTrue();
        }
    }

    @Test
    @DisplayName("Idle timeout is disabled when configured to zero")
    void idleTimeoutDisabledWhenConfiguredZero() {
        ManualTimeSource clock = new ManualTimeSource();
        // 0 ms idle timeout -> disabled (Long.MAX_VALUE)
        HttpConfig config = new HttpConfig(
                HttpMode.CLIENT, null, -1, 10, 0L, 100, 8192, 1024L * 1024L, false,
                HttpVersion.HTTP_2, null, 65536, 65536, 4096, 10L * 1024L * 1024L);
        try (CommunityHttpClientConnectionPool pool = new CommunityHttpClientConnectionPool(config, clock)) {
            FakeConnection conn = new FakeConnection();
            FakeStream stream = new FakeStream(conn);
            pool.release("localhost:8080", conn, stream);

            // Advance clock by arbitrary large amount (e.g. 1 day)
            clock.advanceMillis(86_400_000L);

            CommunityHttpClientConnectionPool.PooledConnection acquired = pool.acquire("localhost:8080");
            assertThat(acquired).isNotNull();
            assertThat(acquired.connection()).isSameAs(conn);
            assertThat(conn.closed.get()).isFalse();
        }
    }

    @Test
    @DisplayName("Idle timeout with Long.MAX_VALUE does not overflow to negative nanoseconds")
    void idleTimeoutWithMaxLongDoesNotOverflow() {
        ManualTimeSource clock = new ManualTimeSource();
        HttpConfig config = new HttpConfig(
                HttpMode.CLIENT, null, -1, 10, Long.MAX_VALUE, 100, 8192, 1024L * 1024L, false,
                HttpVersion.HTTP_2, null, 65536, 65536, 4096, 10L * 1024L * 1024L);
        try (CommunityHttpClientConnectionPool pool = new CommunityHttpClientConnectionPool(config, clock)) {
            FakeConnection conn = new FakeConnection();
            FakeStream stream = new FakeStream(conn);
            pool.release("localhost:8080", conn, stream);

            // Advance clock by 1 hour (would evict if timeout overflowed to negative nanoseconds)
            clock.advanceMillis(3_600_000L);

            CommunityHttpClientConnectionPool.PooledConnection acquired = pool.acquire("localhost:8080");
            assertThat(acquired).isNotNull();
            assertThat(acquired.connection()).isSameAs(conn);
            assertThat(conn.closed.get()).isFalse();
        }
    }

    @Test
    @DisplayName("Cross-authority capacity eviction evicts oldest idle connection from another peer")
    void crossAuthorityCapacityEvictionEvictsOldestIdleConnection() {
        HttpConfig config = new HttpConfig(
                HttpMode.CLIENT, null, -1, 2, 30_000L, 100, 8192, 1024L * 1024L, false,
                HttpVersion.HTTP_2, null, 65536, 65536, 4096, 10L * 1024L * 1024L);
        try (CommunityHttpClientConnectionPool pool = new CommunityHttpClientConnectionPool(config)) {
            FakeConnection connA1 = new FakeConnection();
            FakeConnection connB1 = new FakeConnection();
            FakeConnection connA2 = new FakeConnection();

            pool.release("peerA:8080", connA1, new FakeStream(connA1));
            pool.release("peerB:8080", connB1, new FakeStream(connB1));

            // Pool is full: totalConnections == 2 == maxTotalConnections
            pool.release("peerA:8080", connA2, new FakeStream(connA2));

            // connB1 should have been evicted to make room for connA2
            assertThat(connB1.closed.get()).isTrue();
            assertThat(connA2.closed.get()).isFalse();

            // peerA has connA2 (LIFO first) and connA1
            CommunityHttpClientConnectionPool.PooledConnection acquiredA1 = pool.acquire("peerA:8080");
            assertThat(acquiredA1).isNotNull();
            assertThat(acquiredA1.connection()).isSameAs(connA2);

            CommunityHttpClientConnectionPool.PooledConnection acquiredA2 = pool.acquire("peerA:8080");
            assertThat(acquiredA2).isNotNull();
            assertThat(acquiredA2.connection()).isSameAs(connA1);

            // peerB is empty because connB1 was evicted
            assertThat(pool.acquire("peerB:8080")).isNull();
        }
    }

    @Test
    @DisplayName("Concurrent capacity eviction under cross-authority contention preserves acquired slots")
    void concurrentCapacityEvictionUnderContentionPreservesSlots() throws Exception {
        int maxTotal = 5;
        HttpConfig config = new HttpConfig(
                HttpMode.CLIENT, null, -1, maxTotal, 30_000L, 100, 8192, 1024L * 1024L, false,
                HttpVersion.HTTP_2, null, 65536, 65536, 4096, 10L * 1024L * 1024L);
        try (CommunityHttpClientConnectionPool pool = new CommunityHttpClientConnectionPool(config)) {
            int threads = 20;
            int iterations = 50;
            java.util.concurrent.ConcurrentLinkedQueue<FakeConnection> allCreated =
                    new java.util.concurrent.ConcurrentLinkedQueue<>();
            Thread[] vts = new Thread[threads];
            for (int i = 0; i < threads; i++) {
                final String peer = "peer" + (i % 4) + ":8080";
                vts[i] = Thread.ofVirtual().start(() -> {
                    for (int j = 0; j < iterations; j++) {
                        FakeConnection conn = new FakeConnection();
                        allCreated.add(conn);
                        pool.release(peer, conn, new FakeStream(conn));
                        CommunityHttpClientConnectionPool.PooledConnection acq = pool.acquire(peer);
                        if (acq != null) {
                            pool.release(peer, acq.connection(), acq.stream());
                        }
                    }
                });
            }
            for (Thread vt : vts) {
                vt.join();
            }

            pool.close();
            for (FakeConnection c : allCreated) {
                assertThat(c.closed.get())
                        .as("Every created connection must be closed either via eviction or pool shutdown")
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("Stream with pending unread data is rejected and closed on release")
    void dirtyStreamWithPendingDataIsRejectedOnRelease() {
        try (CommunityHttpClientConnectionPool pool = new CommunityHttpClientConnectionPool(HttpConfig.defaultClient())) {
            FakeConnection conn = new FakeConnection();
            FakeStream stream = new FakeStream(conn);
            stream.setPendingData(true);

            pool.release("localhost:8080", conn, stream);

            assertThat(conn.closed.get()).isTrue();
            assertThat(stream.closed.get()).isTrue();
            assertThat(pool.acquire("localhost:8080")).isNull();
        }
    }

    @Test
    @DisplayName("Pool handles connection count exceeding default 16 without regression")
    void poolCapacityExceedingDefaultInitialCapacityOperatesWithoutRegression() {
        HttpConfig config = HttpConfig.defaultClient();
        try (CommunityHttpClientConnectionPool pool = new CommunityHttpClientConnectionPool(config)) {
            int count = 30;
            FakeConnection[] conns = new FakeConnection[count];
            for (int i = 0; i < count; i++) {
                conns[i] = new FakeConnection();
                pool.release("localhost:8080", conns[i], new FakeStream(conns[i]));
            }
            // Acquire in LIFO order
            for (int i = count - 1; i >= 0; i--) {
                CommunityHttpClientConnectionPool.PooledConnection pooled = pool.acquire("localhost:8080");
                assertThat(pooled).isNotNull();
                assertThat(pooled.connection()).isSameAs(conns[i]);
            }
            assertThat(pool.acquire("localhost:8080")).isNull();
        }
    }

    @Test
    @DisplayName("Sequential acquire-release cycles on warm authority do not prune holder, avoiding reallocation")
    void sequentialAcquireReleaseRetainsHolderWithoutReallocation() {
        HttpConfig config = HttpConfig.defaultClient();
        try (CommunityHttpClientConnectionPool pool = new CommunityHttpClientConnectionPool(config)) {
            FakeConnection conn = new FakeConnection();
            FakeStream stream = new FakeStream(conn);

            // Initial release: authority holder is created
            pool.release("localhost:8080", conn, stream);
            assertThat(pool.authorityCount()).isEqualTo(1);

            // Leased for in-flight request: queue becomes empty, BUT holder must NOT be retired
            CommunityHttpClientConnectionPool.PooledConnection acquired = pool.acquire("localhost:8080");
            assertThat(acquired).isNotNull();
            assertThat(acquired.connection()).isSameAs(conn);
            assertThat(pool.authorityCount())
                    .as("Authority holder must be retained while connection is actively leased to prevent reallocation")
                    .isEqualTo(1);

            // Return to pool
            pool.release("localhost:8080", acquired.connection(), acquired.stream());
            assertThat(pool.authorityCount()).isEqualTo(1);

            // Next acquire retrieves it again from the retained holder
            CommunityHttpClientConnectionPool.PooledConnection second = pool.acquire("localhost:8080");
            assertThat(second).isNotNull();
            assertThat(second.connection()).isSameAs(conn);
            assertThat(pool.authorityCount()).isEqualTo(1);

            // Second acquire on empty pool misses AND prunes empty holder
            assertThat(pool.acquire("localhost:8080")).isNull();
            assertThat(pool.authorityCount())
                    .as("Authority holder must be pruned when acquire encounters an empty holder")
                    .isEqualTo(0);
        }
    }

    @Test
    @DisplayName("Pool prunes holder when connection is closed or not released without requiring another acquire miss")
    void poolPrunesHolderWhenLeasedConnectionClosedWithoutRelease() {
        HttpConfig config = HttpConfig.defaultClient();
        try (CommunityHttpClientConnectionPool pool = new CommunityHttpClientConnectionPool(config)) {
            FakeConnection conn = new FakeConnection();
            FakeStream stream = new FakeStream(conn);

            // Connection is in pool
            pool.release("localhost:8080", conn, stream);
            assertThat(pool.authorityCount()).isEqualTo(1);

            // Leased for in-flight request
            CommunityHttpClientConnectionPool.PooledConnection acquired = pool.acquire("localhost:8080");
            assertThat(acquired).isNotNull();
            assertThat(pool.authorityCount()).isEqualTo(1);

            // Connection closed/broken — pruneIfEmpty invoked explicitly (e.g. by engine)
            pool.pruneIfEmpty("localhost:8080");
            assertThat(pool.authorityCount())
                    .as("Empty holder must be pruned when connection is closed/not returned without release")
                    .isEqualTo(0);
        }
    }

    @Test
    @DisplayName("Fair cross-authority eviction evicts truly oldest idle connection regardless of map iteration order")
    void fairCrossAuthorityEvictionEvictsTrulyOldestIdleConnection() {
        ManualTimeSource clock = new ManualTimeSource();
        HttpConfig config = new HttpConfig(
                HttpMode.CLIENT, null, -1, 2, 30_000L, 100, 8192, 1024L * 1024L, false,
                HttpVersion.HTTP_2, null, 65536, 65536, 4096, 10L * 1024L * 1024L);
        try (CommunityHttpClientConnectionPool pool = new CommunityHttpClientConnectionPool(config, clock)) {
            FakeConnection connOld = new FakeConnection();
            FakeConnection connNew = new FakeConnection();
            FakeConnection connTrigger = new FakeConnection();

            pool.release("peerOld:8080", connOld, new FakeStream(connOld));
            clock.advanceMillis(10);
            pool.release("peerNew:8080", connNew, new FakeStream(connNew));

            // Pool is full (2/2). Releasing for peerTrigger must evict the globally oldest idle peer (peerOld)
            pool.release("peerTrigger:8080", connTrigger, new FakeStream(connTrigger));

            assertThat(connOld.closed.get())
                    .as("Older connection on peerOld must be evicted first")
                    .isTrue();
            assertThat(connNew.closed.get())
                    .as("Newer connection on peerNew must be preserved")
                    .isFalse();

            CommunityHttpClientConnectionPool.PooledConnection acqNew = pool.acquire("peerNew:8080");
            assertThat(acqNew).isNotNull();
            assertThat(acqNew.connection()).isSameAs(connNew);
        }
    }

    @Test
    @DisplayName("Simulated TimeSource drives deterministic idle eviction: valid before deadline, evicted after")
    void poolEvictsIdleWithSimulatedTimeSource() {
        ManualTimeSource clock = new ManualTimeSource();
        // 100 ms idle timeout
        HttpConfig config = new HttpConfig(
                HttpMode.CLIENT, null, -1, 5, 100L, 100, 8192, 1024L * 1024L, false,
                HttpVersion.HTTP_2, null, 65536, 65536, 4096, 10L * 1024L * 1024L);
        try (CommunityHttpClientConnectionPool pool = new CommunityHttpClientConnectionPool(config, clock)) {
            FakeConnection conn = new FakeConnection();
            FakeStream stream = new FakeStream(conn);
            pool.release("localhost:8080", conn, stream);

            // Advance clock by 50 ms (deadline is 100 ms) -> connection must still be usable
            clock.advanceMillis(50);
            CommunityHttpClientConnectionPool.PooledConnection acquired = pool.acquire("localhost:8080");
            assertThat(acquired).isNotNull();
            assertThat(acquired.connection()).isSameAs(conn);
            assertThat(conn.closed.get()).isFalse();

            // Release back with new timestamp
            pool.release("localhost:8080", acquired.connection(), acquired.stream());

            // Advance clock past idle timeout (101 ms) -> must be evicted deterministically
            clock.advanceMillis(101);
            assertThat(pool.acquire("localhost:8080")).isNull();
            assertThat(conn.closed.get()).isTrue();
            assertThat(stream.closed.get()).isTrue();
        }
    }

    @Test
    @DisplayName("Cross-authority eviction scan does not prematurely prune in-flight empty holders")
    void crossAuthorityEvictionDoesNotPruneInFlightHolders() {
        HttpConfig config = new HttpConfig(
                HttpMode.CLIENT, null, -1, 2, 30_000L, 100, 8192, 1024L * 1024L, false,
                HttpVersion.HTTP_2, null, 65536, 65536, 4096, 10L * 1024L * 1024L);
        try (CommunityHttpClientConnectionPool pool = new CommunityHttpClientConnectionPool(config)) {
            FakeConnection activeConn = new FakeConnection();
            FakeStream activeStream = new FakeStream(activeConn);

            // Establish activePeer holder and lease connection (simulating in-flight request)
            pool.release("activePeer:8080", activeConn, activeStream);
            CommunityHttpClientConnectionPool.PooledConnection leased = pool.acquire("activePeer:8080");
            assertThat(leased).isNotNull();
            assertThat(pool.authorityCount()).isEqualTo(1);

            // Fill pool to capacity with other peers
            FakeConnection conn1 = new FakeConnection();
            FakeConnection conn2 = new FakeConnection();
            FakeConnection conn3 = new FakeConnection();

            pool.release("peer1:8080", conn1, new FakeStream(conn1));
            pool.release("peer2:8080", conn2, new FakeStream(conn2));

            // Trigger cross-authority capacity eviction across the pool
            pool.release("peer3:8080", conn3, new FakeStream(conn3));

            // In-flight activePeer holder must NOT have been pruned by the eviction scan!
            assertThat(pool.authorityCount())
                    .as("In-flight authority holder must remain registered despite being empty during eviction scan")
                    .isGreaterThanOrEqualTo(1);

            // Return the leased connection to activePeer
            pool.release("activePeer:8080", leased.connection(), leased.stream());

            CommunityHttpClientConnectionPool.PooledConnection reacquired = pool.acquire("activePeer:8080");
            assertThat(reacquired).isNotNull();
            assertThat(reacquired.connection()).isSameAs(activeConn);
        }
    }

    @Test
    @DisplayName("Cross-authority eviction scan terminates cleanly without hanging or infinite loop")
    void crossAuthorityEvictionScanTerminatesCleanly() {
        HttpConfig config = new HttpConfig(
                HttpMode.CLIENT, null, -1, 1, 30_000L, 100, 8192, 1024L * 1024L, false,
                HttpVersion.HTTP_2, null, 65536, 65536, 4096, 10L * 1024L * 1024L);
        try (CommunityHttpClientConnectionPool pool = new CommunityHttpClientConnectionPool(config)) {
            FakeConnection conn1 = new FakeConnection();
            FakeConnection conn2 = new FakeConnection();

            pool.release("peerA:8080", conn1, new FakeStream(conn1));
            // Capacity is 1, releasing peerB triggers eviction of peerA
            pool.release("peerB:8080", conn2, new FakeStream(conn2));

            assertThat(conn1.closed.get()).isTrue();
            assertThat(conn2.closed.get()).isFalse();

            CommunityHttpClientConnectionPool.PooledConnection acqB = pool.acquire("peerB:8080");
            assertThat(acqB).isNotNull();
            assertThat(acqB.connection()).isSameAs(conn2);
        }
    }

    @Test
    @DisplayName("totalConnections count strictly tracks release, eviction, acquire and close without drift")
    void totalConnectionsTrackingDuringEvictionAndAcquire() {
        HttpConfig config = new HttpConfig(
                HttpMode.CLIENT, null, -1, 2, 30_000L, 100, 8192, 1024L * 1024L, false,
                HttpVersion.HTTP_2, null, 65536, 65536, 4096, 10L * 1024L * 1024L);
        try (CommunityHttpClientConnectionPool pool = new CommunityHttpClientConnectionPool(config)) {
            assertThat(pool.totalConnections()).isEqualTo(0);

            FakeConnection conn1 = new FakeConnection();
            FakeConnection conn2 = new FakeConnection();
            FakeConnection conn3 = new FakeConnection();

            pool.release("peerA:8080", conn1, new FakeStream(conn1));
            assertThat(pool.totalConnections()).isEqualTo(1);

            pool.release("peerB:8080", conn2, new FakeStream(conn2));
            assertThat(pool.totalConnections()).isEqualTo(2);

            // Releasing a 3rd connection when maxTotalConnections=2 evicts the oldest (peerA)
            pool.release("peerC:8080", conn3, new FakeStream(conn3));
            assertThat(conn1.closed.get()).isTrue();
            assertThat(pool.totalConnections()).isEqualTo(2);

            // Acquire peerC decrements totalConnections
            CommunityHttpClientConnectionPool.PooledConnection acqC = pool.acquire("peerC:8080");
            assertThat(acqC).isNotNull();
            assertThat(pool.totalConnections()).isEqualTo(1);

            // Acquire peerB decrements totalConnections
            CommunityHttpClientConnectionPool.PooledConnection acqB = pool.acquire("peerB:8080");
            assertThat(acqB).isNotNull();
            assertThat(pool.totalConnections()).isEqualTo(0);
        }
    }

    @Test
    @DisplayName("Eviction prunes empty authority holders preventing map memory leakage")
    void evictionPrunesEmptyAuthorityHoldersPreventingMapLeak() {
        HttpConfig config = new HttpConfig(
                HttpMode.CLIENT, null, -1, 2, 30_000L, 100, 8192, 1024L * 1024L, false,
                HttpVersion.HTTP_2, null, 65536, 65536, 4096, 10L * 1024L * 1024L);
        try (CommunityHttpClientConnectionPool pool = new CommunityHttpClientConnectionPool(config)) {
            FakeConnection connA = new FakeConnection();
            FakeConnection connB = new FakeConnection();
            FakeConnection connC = new FakeConnection();

            pool.release("peerA:8080", connA, new FakeStream(connA));
            pool.release("peerB:8080", connB, new FakeStream(connB));
            assertThat(pool.authorityCount()).isEqualTo(2);

            // Releasing peerC evicts peerA's connection; peerA's holder must be pruned immediately
            pool.release("peerC:8080", connC, new FakeStream(connC));
            assertThat(connA.closed.get()).isTrue();
            assertThat(pool.authorityCount())
                    .as("Evicted authority holder must be pruned from the pool map immediately")
                    .isEqualTo(2);

            // Verify active authorities are peerB and peerC
            assertThat(pool.acquire("peerA:8080")).isNull();
            assertThat(pool.acquire("peerB:8080")).isNotNull();
            assertThat(pool.acquire("peerC:8080")).isNotNull();
        }
    }

    @Test
    @DisplayName("Cross-authority eviction handles arbitrary and negative nanoTime correctly")
    void crossAuthorityEvictionHandlesArbitraryAndNegativeNanoTimeCorrectly() {
        // Test 1: Negative nanoTime values (clock starting before epoch or negative origin)
        ManualTimeSource negativeClock = new ManualTimeSource(-10_000_000_000L);
        HttpConfig config = new HttpConfig(
                HttpMode.CLIENT, null, -1, 2, 30_000L, 100, 8192, 1024L * 1024L, false,
                HttpVersion.HTTP_2, null, 65536, 65536, 4096, 10L * 1024L * 1024L);
        try (CommunityHttpClientConnectionPool pool = new CommunityHttpClientConnectionPool(config, negativeClock)) {
            FakeConnection connA = new FakeConnection();
            FakeConnection connB = new FakeConnection();
            FakeConnection connC = new FakeConnection();

            // connA released at -10s
            pool.release("peerA:8080", connA, new FakeStream(connA));

            // Advance clock by 1s: clock is now -9s
            negativeClock.advanceNanos(1_000_000_000L);

            // connB released at -9s
            pool.release("peerB:8080", connB, new FakeStream(connB));

            // Advance clock by 1s: clock is now -8s
            negativeClock.advanceNanos(1_000_000_000L);

            // connA age = -8s - (-10s) = 2s
            // connB age = -8s - (-9s) = 1s
            // Pool is full (maxConnections = 2). Releasing peerC must evict the older connA (2s > 1s)
            pool.release("peerC:8080", connC, new FakeStream(connC));

            assertThat(connA.closed.get())
                    .as("connA should be evicted because it has the largest relative age")
                    .isTrue();
            assertThat(connB.closed.get())
                    .as("connB is younger than connA and must not be evicted")
                    .isFalse();
            assertThat(connC.closed.get()).isFalse();

            // Verify active connections in pool
            assertThat(pool.acquire("peerA:8080")).isNull();
            assertThat(pool.acquire("peerB:8080")).isNotNull();
            assertThat(pool.acquire("peerC:8080")).isNotNull();
        }

        // Test 2: Wrapping across Long.MAX_VALUE into negative territory
        ManualTimeSource wrapClock = new ManualTimeSource(Long.MAX_VALUE - 1000L);
        try (CommunityHttpClientConnectionPool pool = new CommunityHttpClientConnectionPool(config, wrapClock)) {
            FakeConnection connA = new FakeConnection();
            FakeConnection connB = new FakeConnection();
            FakeConnection connC = new FakeConnection();

            // connA released at Long.MAX_VALUE - 1000L
            pool.release("peerA:8080", connA, new FakeStream(connA));

            // Advance clock by 500ns: clock is Long.MAX_VALUE - 500L
            wrapClock.advanceNanos(500L);

            // connB released at Long.MAX_VALUE - 500L
            pool.release("peerB:8080", connB, new FakeStream(connB));

            // Advance clock by 1000ns: clock wraps to Long.MIN_VALUE + 499L
            wrapClock.advanceNanos(1000L);

            // At wrap:
            // connA elapsed = (Long.MIN_VALUE + 499L) - (Long.MAX_VALUE - 1000L) = 1500L
            // connB elapsed = (Long.MIN_VALUE + 499L) - (Long.MAX_VALUE - 500L) = 1000L
            // Releasing peerC must evict connA (1500ns > 1000ns) despite numerical overflow
            pool.release("peerC:8080", connC, new FakeStream(connC));

            assertThat(connA.closed.get())
                    .as("connA must be evicted across clock wrap boundary")
                    .isTrue();
            assertThat(connB.closed.get())
                    .as("connB must remain pooled across clock wrap boundary")
                    .isFalse();
            assertThat(connC.closed.get()).isFalse();
        }
    }

    @Test
    @DisplayName("Concurrent eviction under contention never exceeds pool capacity or drops healthy connections")
    void concurrentEvictionUnderContentionNeverDropsHealthyConnectionPrematurely() throws Exception {
        int maxTotal = 16;
        HttpConfig config = new HttpConfig(
                HttpMode.CLIENT, null, -1, maxTotal, 60_000L, 100, 8192, 1024L * 1024L, false,
                HttpVersion.HTTP_2, null, 65536, 65536, 4096, 10L * 1024L * 1024L);
        try (CommunityHttpClientConnectionPool pool = new CommunityHttpClientConnectionPool(config)) {
            int threads = 32;
            int iterations = 100;
            java.util.concurrent.ConcurrentLinkedQueue<FakeConnection> created =
                    new java.util.concurrent.ConcurrentLinkedQueue<>();
            java.util.concurrent.atomic.AtomicInteger maxObservedConnections =
                    new java.util.concurrent.atomic.AtomicInteger(0);
            Thread[] vts = new Thread[threads];

            for (int i = 0; i < threads; i++) {
                final int threadId = i;
                vts[i] = Thread.ofVirtual().start(() -> {
                    for (int j = 0; j < iterations; j++) {
                        String peer = "host-" + ((threadId + j) % 8) + ":8080";
                        CommunityHttpClientConnectionPool.PooledConnection pooled = pool.acquire(peer);
                        FakeConnection conn;
                        FakeStream stream;
                        if (pooled == null) {
                            conn = new FakeConnection();
                            stream = new FakeStream(conn);
                            created.add(conn);
                        } else {
                            conn = (FakeConnection) pooled.connection();
                            stream = (FakeStream) pooled.stream();
                        }

                        int current = pool.totalConnections();
                        maxObservedConnections.accumulateAndGet(current, Math::max);
                        assertThat(current).isLessThanOrEqualTo(maxTotal);

                        Thread.onSpinWait();
                        pool.release(peer, conn, stream);

                        current = pool.totalConnections();
                        maxObservedConnections.accumulateAndGet(current, Math::max);
                        assertThat(current).isLessThanOrEqualTo(maxTotal);
                    }
                });
            }

            for (Thread vt : vts) {
                vt.join();
            }

            assertThat(maxObservedConnections.get())
                    .as("Total connections must never exceed configured maximum")
                    .isLessThanOrEqualTo(maxTotal);
            assertThat(pool.totalConnections())
                    .as("Pool totalConnections must be within valid bounds")
                    .isBetween(0, maxTotal);

            // Clean pool shutdown
            pool.close();
            assertThat(pool.totalConnections()).isEqualTo(0);
            for (FakeConnection c : created) {
                assertThat(c.closed.get())
                        .as("All created connections must be cleanly closed after pool close")
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("Release to saturated authority rejects connection without evicting from other authorities")
    void releaseToSaturatedAuthorityDoesNotEvictConnectionsFromOtherAuthorities() {
        int maxTotal = 70;
        HttpConfig config = new HttpConfig(
                HttpMode.CLIENT, null, -1, maxTotal, 60_000L, 100, 8192, 1024L * 1024L, false,
                HttpVersion.HTTP_2, null, 65536, 65536, 4096, 10L * 1024L * 1024L);
        try (CommunityHttpClientConnectionPool pool = new CommunityHttpClientConnectionPool(config)) {
            String authA = "host-a:8080";
            String authB = "host-b:8080";

            FakeConnection[] connsA = new FakeConnection[64];
            for (int i = 0; i < 64; i++) {
                connsA[i] = new FakeConnection();
                pool.release(authA, connsA[i], new FakeStream(connsA[i]));
            }

            FakeConnection[] connsB = new FakeConnection[6];
            for (int i = 0; i < 6; i++) {
                connsB[i] = new FakeConnection();
                pool.release(authB, connsB[i], new FakeStream(connsB[i]));
            }

            assertThat(pool.totalConnections()).isEqualTo(70);

            FakeConnection excessConn = new FakeConnection();
            pool.release(authA, excessConn, new FakeStream(excessConn));

            assertThat(excessConn.closed.get()).isTrue();
            assertThat(pool.totalConnections()).isEqualTo(70);

            for (FakeConnection cb : connsB) {
                assertThat(cb.closed.get())
                        .as("Connections in peer authority must not be evicted when target authority is saturated")
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("Concurrent release and close never results in negative totalConnections")
    void concurrentReleaseAndCloseNeverResultsInNegativeTotalConnections() throws Exception {
        HttpConfig config = new HttpConfig(
                HttpMode.CLIENT, null, -1, 16, 60_000L, 100, 8192, 1024L * 1024L, false,
                HttpVersion.HTTP_2, null, 65536, 65536, 4096, 10L * 1024L * 1024L);
        for (int cycle = 0; cycle < 50; cycle++) {
            CommunityHttpClientConnectionPool pool = new CommunityHttpClientConnectionPool(config);
            int threads = 16;
            Thread[] vts = new Thread[threads];
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                vts[i] = Thread.ofVirtual().start(() -> {
                    String peer = "host-" + (idx % 4) + ":8080";
                    FakeConnection conn = new FakeConnection();
                    pool.release(peer, conn, new FakeStream(conn));
                });
            }
            pool.close();
            for (Thread vt : vts) {
                vt.join();
            }
            assertThat(pool.totalConnections())
                    .as("totalConnections must never be negative after concurrent close and release")
                    .isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    @DisplayName("Concurrent release to saturated peer does not evict connections from other peers")
    void concurrentReleaseToSaturatedPeerDoesNotEvictFromOtherPeers() throws Exception {
        int maxTotal = 70;
        HttpConfig config = new HttpConfig(
                HttpMode.CLIENT, null, -1, maxTotal, 60_000L, 100, 8192, 1024L * 1024L, false,
                HttpVersion.HTTP_2, null, 65536, 65536, 4096, 10L * 1024L * 1024L);
        try (CommunityHttpClientConnectionPool pool = new CommunityHttpClientConnectionPool(config)) {
            String peerA = "host-a:8080";
            String peerB = "host-b:8080";

            int peerAConns = 64;
            FakeConnection[] connsA = new FakeConnection[peerAConns];
            for (int i = 0; i < peerAConns; i++) {
                connsA[i] = new FakeConnection();
                pool.release(peerA, connsA[i], new FakeStream(connsA[i]));
            }

            int peerBConns = 6;
            FakeConnection[] connsB = new FakeConnection[peerBConns];
            for (int i = 0; i < peerBConns; i++) {
                connsB[i] = new FakeConnection();
                pool.release(peerB, connsB[i], new FakeStream(connsB[i]));
            }
            assertThat(pool.totalConnections()).isEqualTo(70);

            int concurrency = 100;
            Thread[] vts = new Thread[concurrency];
            for (int i = 0; i < concurrency; i++) {
                vts[i] = Thread.ofVirtual().start(() -> {
                    FakeConnection conn = new FakeConnection();
                    pool.release(peerA, conn, new FakeStream(conn));
                });
            }
            for (Thread vt : vts) {
                vt.join();
            }

            assertThat(pool.totalConnections()).isEqualTo(70);

            for (FakeConnection cb : connsB) {
                assertThat(cb.closed.get())
                        .as("PeerB connections must not be evicted under concurrent peerA release contention")
                        .isFalse();
            }

            for (int i = 0; i < peerBConns; i++) {
                CommunityHttpClientConnectionPool.PooledConnection pooled = pool.acquire(peerB);
                assertThat(pooled).isNotNull();
            }
        }
    }

    @Test
    @DisplayName("Pool captures ScopedValue TimeSource at construction time per ADR-082 capture rule")
    void poolHonorsScopedValueTimeSourceAtRuntime() {
        HttpConfig config = new HttpConfig(
                HttpMode.CLIENT, null, -1, 10, 1000L, 100, 8192, 1024L * 1024L, false,
                HttpVersion.HTTP_2, null, 65536, 65536, 4096, 10L * 1024L * 1024L);
        ManualTimeSource virtualTime = new ManualTimeSource(1_000_000_000L);

        // Pool constructed inside carrier scope captures bound TimeSource per ADR-082 Amendment A1
        ScopedValue.where(KernelProviders.TIME_SOURCE, virtualTime).run(() -> {
            try (CommunityHttpClientConnectionPool pool = new CommunityHttpClientConnectionPool(config)) {
                FakeConnection conn = new FakeConnection();
                FakeStream stream = new FakeStream(conn);

                // Operations on other threads or unbound contexts still use captured TimeSource
                pool.release("host-a:8080", conn, stream);

                // Before idle timeout: acquire succeeds
                virtualTime.advanceMillis(500);
                CommunityHttpClientConnectionPool.PooledConnection pooled = pool.acquire("host-a:8080");
                assertThat(pooled).isNotNull();
                assertThat(pooled.connection()).isSameAs(conn);

                // Return it to pool
                pool.release("host-a:8080", conn, stream);

                // After idle timeout: acquire evicts expired connection
                virtualTime.advanceMillis(1500);
                CommunityHttpClientConnectionPool.PooledConnection expired = pool.acquire("host-a:8080");
                assertThat(expired).isNull();
                assertThat(conn.closed.get()).isTrue();
            }
        });
    }

    @Test
    @DisplayName("Cross-authority eviction scans beyond 32 authorities without hash-bucket starvation")
    void crossAuthorityEvictionScansBeyond32AuthoritiesWithoutHashBucketStarvation() {
        ManualTimeSource manualTime = new ManualTimeSource(1_000_000_000L);
        // Pool capacity = 40 connections
        HttpConfig config = new HttpConfig(
                HttpMode.CLIENT, null, -1, 40, 0L, 100, 8192, 1024L * 1024L, false,
                HttpVersion.HTTP_2, null, 65536, 65536, 4096, 10L * 1024L * 1024L);

        try (CommunityHttpClientConnectionPool pool = new CommunityHttpClientConnectionPool(config, manualTime)) {
            FakeConnection[] connections = new FakeConnection[40];
            // Release 40 connections across 40 distinct authorities
            for (int i = 0; i < 39; i++) {
                manualTime.advanceMillis(100);
                connections[i] = new FakeConnection();
                pool.release("host-authority-" + i + ":8080", connections[i], new FakeStream(connections[i]));
            }

            // Set time back to initial origin for the 40th authority (index 39) so it is the absolute oldest
            manualTime.advanceMillis(-50_000);
            connections[39] = new FakeConnection();
            pool.release("host-authority-39:8080", connections[39], new FakeStream(connections[39]));

            // Advance time forward
            manualTime.advanceMillis(100_000);

            assertThat(pool.totalConnections()).isEqualTo(40);

            // Releasing 41st authority when pool is full
            FakeConnection incomingConn = new FakeConnection();
            pool.release("incoming-authority:8080", incomingConn, new FakeStream(incomingConn));

            assertThat(pool.totalConnections()).isEqualTo(40);
            assertThat(incomingConn.closed.get()).isFalse();

            // host-authority-39 must be evicted because it is the oldest across all 40 authorities
            assertThat(connections[39].closed.get())
                    .as("host-authority-39 has the oldest connection and must be evicted without bucket starvation")
                    .isTrue();

            // Other authorities must remain open
            for (int i = 0; i < 39; i++) {
                assertThat(connections[i].closed.get())
                        .as("Connection %d is younger and must remain open", i)
                        .isFalse();
            }
        }
    }

    private static final class FakeConnection implements TransportConnection {
        private final AtomicBoolean closed = new AtomicBoolean(false);

        @Override
        public TransportStream openStream() {
            return new FakeStream(this);
        }

        @Override
        public TransportStream openUnidirectionalStream() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String remoteAddress() {
            return "127.0.0.1";
        }

        @Override
        public int remotePort() {
            return 8080;
        }

        @Override
        public boolean isOpen() {
            return !closed.get();
        }

        @Override
        public Object attachment() {
            return null;
        }

        @Override
        public void setAttachment(Object attachment) {
        }

        @Override
        public boolean tick() {
            return false;
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private static final class FakeStream implements TransportStream {
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final TransportConnection parentConnection;

        private final AtomicBoolean pendingData = new AtomicBoolean(false);

        FakeStream(TransportConnection parentConnection) {
            this.parentConnection = parentConnection;
        }

        void setPendingData(boolean pending) {
            this.pendingData.set(pending);
        }

        @Override
        public int read(MemorySegment target, int maxBytes) {
            return -1;
        }

        @Override
        public void write(MemorySegment source, int length) {
        }

        @Override
        public void queueWrite(LoanedBuffer buffer, int length) {
        }

        @Override
        public long streamId() {
            return 1L;
        }

        @Override
        public boolean isBidirectional() {
            return true;
        }

        @Override
        public boolean isClientInitiated() {
            return true;
        }

        @Override
        public TransportConnection connection() {
            return parentConnection;
        }

        @Override
        public boolean hasPendingData() {
            return pendingData.get();
        }

        @Override
        public void reset(long errorCode) {
            close();
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private static final class ManualTimeSource implements TimeSource {
        private final AtomicLong nanos;
        private final Instant origin = Instant.parse("2026-09-01T00:00:00Z");

        ManualTimeSource() {
            this(1_000_000_000L);
        }

        ManualTimeSource(long initialNanos) {
            this.nanos = new AtomicLong(initialNanos);
        }

        @Override
        public long nanoTime() {
            return nanos.get();
        }

        @Override
        public Instant wallTime() {
            return origin.plusNanos(nanos.get());
        }

        void advanceMillis(long millis) {
            nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
        }

        void advanceNanos(long byNanos) {
            nanos.addAndGet(byNanos);
        }
    }
}

