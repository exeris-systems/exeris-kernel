/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.community.persistence.CommunityAdmissionConfig;
import eu.exeris.kernel.community.persistence.CommunityPersistenceProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpMode;
import eu.exeris.kernel.spi.http.HttpResponseBodyEncoderRegistry;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.persistence.EngineStats;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.transport.ConnectionHandler;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportMode;
import eu.exeris.kernel.spi.transport.TransportStats;
import eu.exeris.kernel.spi.transport.TransportStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L2 Integration: HTTP dispatcher backpressure when persistence pool saturated.
 *
 * <h2>Contract</h2>
 * Verifies that CommunityHttpRequestProcessor correctly checks
 * {@link PersistenceEngine#canServiceRequest()} before creating a session box and
 * responds with 503 when the gate is closed (pool saturated).
 *
 * <p>This test validates the gateway behavior in isolation, ensuring that:
 * <ul>
 *   <li>HTTP processor calls canServiceRequest() on the engine</li>
 *   <li>Gate returns false when pool is near saturation</li>
 *   <li>Integration point is correctly wired</li>
 * </ul>
 *
 */
@DisplayName("L2 Integration: HTTP backpressure admission control")
class HttpDispatcherBackpressureTckTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    // ADR-035: strict allowance (ratio 0) restores the pre-035 "shed on first queued acquire"
    // contract, so a saturated pool with a single forming waiter deterministically closes the gate.
    private static final CommunityAdmissionConfig STRICT_ADMISSION =
            new CommunityAdmissionConfig(0.90d, 0.85d, 0.90d, 1L, 0.15d, 3, 0.0d);
    private static final Duration QUEUE_WAIT_TIMEOUT = Duration.ofSeconds(2);

    @AfterAll
    @SuppressWarnings("unused")
    static void closeAllocator() {
        ALLOCATOR.close();
    }

    /**
     * Drives {@code engine} into a closed-gate state under {@link #STRICT_ADMISSION}: fully drains
     * the pool and spawns a virtual thread that blocks on acquire so that {@code pendingAcquires > 0}.
     * Runs {@code whileClosed} with the gate closed, then releases everything and restores config.
     */
    private static void withClosedAdmissionGate(PersistenceEngine engine, Runnable whileClosed)
            throws InterruptedException {
        CommunityAdmissionConfig previous = CommunityAdmissionConfig.CURRENT;
        CommunityAdmissionConfig.CURRENT = STRICT_ADMISSION;
        int maxPoolSize = engine.stats().maxConnections();
        List<PersistenceConnection> held = new ArrayList<>();
        CountDownLatch waiterStarted = new CountDownLatch(1);
        Thread waiter = Thread.ofVirtual().unstarted(() -> {
            waiterStarted.countDown();
            try {
                engine.openConnection().close(); // blocks: pool is fully drained
            } catch (RuntimeException _) {
                // acquire timeout on cleanup is fine
            }
        });
        try {
            for (int i = 0; i < maxPoolSize; i++) {
                held.add(engine.openConnection());
            }
            waiter.start();
            assertThat(waiterStarted.await(1, TimeUnit.SECONDS)).isTrue();
            awaitPendingAcquires(engine);
            whileClosed.run();
        } finally {
            held.forEach(c -> {
                try {
                    c.close();
                } catch (Exception _) {
                    // best-effort
                }
            });
            waiter.join(2000);
            CommunityAdmissionConfig.CURRENT = previous;
        }
    }

    private static void awaitPendingAcquires(PersistenceEngine engine) {
        long deadline = System.nanoTime() + QUEUE_WAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (engine.stats().pendingAcquires() > 0) {
                return;
            }
            try {
                Thread.sleep(5L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted awaiting pending acquires", ex);
            }
        }
        throw new AssertionError("Timed out waiting for pending acquires > 0");
    }

    /**
     * Integration test: Verify the gating mechanism is in place and functional.
     *
     * <p>This test creates a persistence engine, saturates the pool to 90%+,
     * and verifies that canServiceRequest() returns false,
     * which triggers HTTP 503 responses in the dispatcher.
     */
    @Test
    @DisplayName("Persistence pool saturation + forming queue triggers admission gate closure (strict)")
    void testBackpressureGate_RejectsWhenPoolSaturated() throws InterruptedException {
        // Setup: Create a small pool for easy saturation
        PersistenceConfig config = PersistenceConfig.production(
                "jdbc:h2:mem:exeris_integration_backpressure_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
                "sa",
                "",
                4,    // maxPoolSize = 4
                1,    // minIdleConnections
                1     // maxTenantPools
        );

        try (PersistenceEngine engine = new CommunityPersistenceProvider().createEngine(config)) {
            // Precondition: Pool should have capacity initially
            EngineStats initialStats = engine.stats();
            assertThat(initialStats.activeConnections()).isZero();
            assertThat(engine.canServiceRequest()).isTrue();

            // ADR-035: full pool + a forming queue under strict allowance closes the gate.
            withClosedAdmissionGate(engine, () ->
                    assertThat(engine.canServiceRequest())
                            .as("Admission gate should be closed with full pool and a forming queue")
                            .isFalse());
        }
    }

    @Test
    @DisplayName("CommunityHttpRequestProcessor returns 503 with Retry-After when admission gate closed")
    void testProcessorReturns503WithRetryAfterWhenAdmissionGateClosed() throws InterruptedException {
        PersistenceConfig config = PersistenceConfig.production(
                "jdbc:h2:mem:exeris_processor_backpressure_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
                "sa",
                "",
                4,
                1,
                1
        );

        try (PersistenceEngine engine = new CommunityPersistenceProvider().createEngine(config)) {
            withClosedAdmissionGate(engine, () -> {
                assertThat(engine.canServiceRequest()).isFalse();

                HttpConfig httpConfig = new HttpConfig(
                    HttpMode.SERVER,
                    "127.0.0.1",
                    18080,
                    HttpConfig.DEFAULT_MAX_CONNECTIONS,
                    HttpConfig.DEFAULT_IDLE_TIMEOUT_MS,
                    HttpConfig.DEFAULT_MAX_HEADER_COUNT,
                    HttpConfig.DEFAULT_MAX_HEADER_SIZE,
                    HttpConfig.DEFAULT_MAX_REQUEST_BODY_BYTES,
                    true,
                    HttpVersion.HTTP_2
            );

                FakeTransportEngine transport = new FakeTransportEngine(
                    "GET /health HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Connection: close\r\n"
                        + "\r\n"
                );
                CommunityHttpServerEngine server =
                    new CommunityHttpServerEngine(httpConfig, ALLOCATOR, transport, 18080, HttpResponseBodyEncoderRegistry.empty());
            AtomicBoolean handlerInvoked = new AtomicBoolean(false);
                server.setHandler(exchange -> handlerInvoked.set(true));

                try {
                ScopedValue.where(KernelProviders.PERSISTENCE_ENGINE, engine).run(server::start);

                String response = transport.capturedResponse();
                assertThat(handlerInvoked).isFalse();
                assertThat(response).contains("HTTP/1.1 503 Service Unavailable");
                assertThat(response).contains("Retry-After: 1");
                } finally {
                server.close();
                }
            });
        }
    }

    /**
     * Test: With capacity, gate remains open and requests proceed.
     *
     * <p>Verifies downstream behavior: when pool has capacity,
     * canServiceRequest() returns true and HTTP dispatcher proceeds normally.
     */
    @Test
    @DisplayName("Admission gate remains open when pool has capacity")
    void testBackpressureGate_RemainsOpen_WithCapacity() {
        PersistenceConfig config = PersistenceConfig.production(
                "jdbc:h2:mem:exeris_integration_capacity_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
                "sa",
                "",
                4,
                1,
                1
        );

        try (PersistenceEngine engine = new CommunityPersistenceProvider().createEngine(config)) {
            // Precondition: Pool has capacity
            EngineStats stats = engine.stats();
            assertThat(stats.activeConnections()).isLessThan(stats.maxConnections() / 2);

            // Action & Assertion: Gate should be open
            assertThat(engine.canServiceRequest())
                    .as("Admission gate should be open when pool has capacity")
                    .isTrue();
        }
    }

    private static final class FakeTransportEngine implements TransportEngine {

        private final FakeTransportStream stream;
        private volatile eu.exeris.kernel.spi.transport.StreamHandler streamHandler;

        private FakeTransportEngine(String requestPayload) {
            this.stream = new FakeTransportStream(requestPayload);
        }

        @Override
        public void setStreamHandler(eu.exeris.kernel.spi.transport.StreamHandler handler) {
            this.streamHandler = handler;
        }

        @Override
        public void setConnectionHandler(ConnectionHandler handler) {
            // Not required by this focused integration test.
        }

        @Override
        public void start() {
            streamHandler.handle(stream);
        }

        @Override
        public void stop() {
            // No-op for synthetic transport.
        }

        @Override
        public TransportConnection connect(String host, int port) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TransportMode mode() {
            return TransportMode.SERVER;
        }

        @Override
        public TransportStats stats() {
            return TransportStats.EMPTY;
        }

        @Override
        public String engineName() {
            return "fake-test-transport";
        }

        @Override
        public void close() {
            // No-op for synthetic transport.
        }

        private String capturedResponse() {
            return stream.writtenPayload();
        }
    }

    private static final class FakeTransportStream implements TransportStream {

        private final MemorySegment requestBytes;
        private final byte[] written = new byte[8 * 1024];
        private int readOffset;
        private int writtenSize;
        private boolean closed;

        private FakeTransportStream(String requestPayload) {
            this.requestBytes = MemorySegment.ofArray(requestPayload.getBytes(StandardCharsets.US_ASCII));
        }

        @Override
        public int read(MemorySegment target, int maxBytes) {
            if (closed) {
                return -1;
            }
            int remaining = (int) requestBytes.byteSize() - readOffset;
            if (remaining <= 0) {
                return -1;
            }
            int bytesToRead = Math.min(maxBytes, remaining);
            MemorySegment.copy(requestBytes, readOffset, target, 0, bytesToRead);
            readOffset += bytesToRead;
            return bytesToRead;
        }

        @Override
        public void write(MemorySegment source, int length) {
            byte[] chunk = source.asSlice(0, length).toArray(ValueLayout.JAVA_BYTE);
            System.arraycopy(chunk, 0, written, writtenSize, length);
            writtenSize += length;
        }

        @Override
        public void queueWrite(LoanedBuffer buffer, int length) {
            try {
                write(buffer.segment(), length);
            } finally {
                buffer.close();
            }
        }

        @Override
        public long streamId() {
            return 1;
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
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hasPendingData() {
            return false;
        }

        @Override
        public void close() {
            closed = true;
        }

        private String writtenPayload() {
            return new String(written, 0, writtenSize, StandardCharsets.US_ASCII);
        }
    }
}
