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
import eu.exeris.kernel.spi.transport.TransportEngineCapabilities;
import eu.exeris.kernel.spi.transport.TransportMode;
import eu.exeris.kernel.spi.transport.TransportStats;
import eu.exeris.kernel.spi.transport.TransportStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
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

    @AfterAll
    @SuppressWarnings("unused")
    static void closeAllocator() {
        ALLOCATOR.close();
    }

    /**
     * Integration test: Verify the gating mechanism is in place and functional.
     *
     * <p>This test creates a persistence engine, saturates the pool to 90%+,
     * and verifies that canServiceRequest() returns false,
     * which triggers HTTP 503 responses in the dispatcher.
     */
    @Test
    @DisplayName("Persistence pool saturation triggers admission gate closure")
    void testBackpressureGate_RejectsWhenPoolSaturated() {
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

            // Action: Saturate the pool to 90%
            int maxPoolSize = initialStats.maxConnections();
            int threshold90 = (int) Math.ceil(maxPoolSize * 0.9);

            var connections = new java.util.ArrayList<PersistenceConnection>();
            for (int i = 0; i < threshold90; i++) {
                connections.add(engine.openConnection());
            }

            try {
                // Assertion 1: Pool is now at/near 90% saturation
                EngineStats saturatedStats = engine.stats();
                assertThat(saturatedStats.activeConnections())
                        .as("Active connections should be at threshold level")
                        .isGreaterThanOrEqualTo(threshold90);

                // Assertion 2: Admission gate is now closed (returns false)
                boolean canService = engine.canServiceRequest();
                assertThat(canService)
                        .as("Admission gate should be closed at 90%+ saturation")
                        .isFalse();

                // Assertion 3: This gate closure would trigger 503 in HTTP dispatcher
                // (The actual HTTP 503 routing is verified in e2e/integration tests)
                // This test just confirms the gate works and is accessible

            } finally {
                // Cleanup
                connections.forEach(c -> {
                    try {
                        c.close();
                    } catch (Exception _) {
                        // Best-effort close in test cleanup.
                    }
                });
            }
        }
    }

    @Test
    @DisplayName("CommunityHttpRequestProcessor returns 503 with Retry-After when admission gate closed")
    void testProcessorReturns503WithRetryAfterWhenAdmissionGateClosed() {
        PersistenceConfig config = PersistenceConfig.production(
                "jdbc:h2:mem:exeris_processor_backpressure_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
                "sa",
                "",
                4,
                1,
                1
        );

        try (PersistenceEngine engine = new CommunityPersistenceProvider().createEngine(config)) {
            int threshold90 = (int) Math.ceil(engine.stats().maxConnections() * 0.9);
            var heldConnections = new java.util.ArrayList<PersistenceConnection>();
            for (int i = 0; i < threshold90; i++) {
                heldConnections.add(engine.openConnection());
            }

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

            heldConnections.forEach(c -> {
                try {
                    c.close();
                } catch (Exception _) {
                    // Best-effort close in test cleanup.
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
        public TransportEngineCapabilities capabilities() {
            return TransportEngineCapabilities.STANDARD.withProvider("test");
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
