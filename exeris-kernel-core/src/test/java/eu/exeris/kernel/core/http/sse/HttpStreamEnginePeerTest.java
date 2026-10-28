/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.sse;

import eu.exeris.kernel.core.http.hpack.TestAllocator;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stream engine reports the peer of the connection its stream was opened on, read from that
 * connection on each call. The stream here is a fake that accepts the SSE head and discards it, so
 * the only thing under test is which connection the engine asks.
 */
@DisplayName("HttpStreamEngine reports the peer of its stream's connection")
class HttpStreamEnginePeerTest {

    private static final Arena ARENA = Arena.ofShared();
    private static final MemoryAllocator ALLOCATOR = new TestAllocator(ARENA);

    @AfterAll
    static void closeArena() {
        ARENA.close();
    }

    @Test
    @DisplayName("remoteAddress() and remotePort() are the connection's, not the request's or a constant")
    void engineReportsTheConnectionOfItsStream() {
        HttpRequest request = HttpRequest.noBody(HttpMethod.GET, "/events", HttpVersion.HTTP_1_1, List.of());
        HttpStreamEngine engine = HttpStreamEngine.open(
                request, streamOn(new PeerConnection("10.9.8.7", 40_001)), ALLOCATOR);
        try {
            assertThat(engine.remoteAddress()).isEqualTo("10.9.8.7");
            assertThat(engine.remotePort()).isEqualTo(40_001);
        } finally {
            engine.close();
        }
    }

    private static TransportStream streamOn(TransportConnection connection) {
        return new TransportStream() {
            @Override
            public int read(MemorySegment target, int maxBytes) {
                return -1;
            }

            @Override
            public void write(MemorySegment source, int length) {
                // Discarded: the wire is not what is under test.
            }

            @Override
            public void queueWrite(LoanedBuffer buffer, int length) {
                buffer.close();
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
                return connection;
            }

            @Override
            public boolean hasPendingData() {
                return false;
            }

            @Override
            public void close() {
                // no-op
            }
        };
    }

    /** A connection whose only answer is its peer; nothing else on it is reached by this test. */
    private record PeerConnection(String remoteAddress, int remotePort) implements TransportConnection {

        @Override
        public TransportStream openStream() {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public TransportStream openUnidirectionalStream() {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public Object attachment() {
            return null;
        }

        @Override
        public void setAttachment(Object attachment) {
            // not used by this test
        }

        @Override
        public boolean tick() {
            return false;
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
