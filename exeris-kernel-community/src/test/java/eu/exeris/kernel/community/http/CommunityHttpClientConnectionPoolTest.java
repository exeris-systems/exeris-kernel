/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpMode;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;

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
    void idleTimeoutEviction() throws InterruptedException {
        // 1 ms idle timeout
        HttpConfig config = new HttpConfig(
                HttpMode.CLIENT, null, -1, 10, 1L, 100, 8192, 1024L * 1024L, false,
                HttpVersion.HTTP_2, null, 65536, 65536, 4096, 10L * 1024L * 1024L);
        try (CommunityHttpClientConnectionPool pool = new CommunityHttpClientConnectionPool(config)) {
            FakeConnection conn = new FakeConnection();
            FakeStream stream = new FakeStream(conn);
            pool.release("localhost:8080", conn, stream);

            Thread.sleep(15);

            assertThat(pool.acquire("localhost:8080")).isNull();
            assertThat(conn.closed.get()).isTrue();
            assertThat(stream.closed.get()).isTrue();
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
}
