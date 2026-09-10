/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpMode;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.ConnectionHandler;
import eu.exeris.kernel.spi.transport.StreamHandler;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportMode;
import eu.exeris.kernel.spi.transport.TransportStats;
import eu.exeris.kernel.spi.transport.TransportStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a response costs is what the response is, not what the ceiling allows.
 *
 * <p>Until 0.12 the client allocated {@code resolveAggregateCapacity()} up front for every response,
 * so a {@code HEAD} against a 10 MiB ceiling allocated 10 MiB — configuration sized the allocation,
 * on a path that runs once per request.
 *
 * <p>Asserted on {@code MemoryStats.peakAllocatedBytes()} rather than on timing, because the claim
 * is about bytes and timing would measure the machine. The allocator is fresh per exchange, so the
 * peak is that exchange's high-water mark and nothing else. The peer is canned: a real socket would
 * add its own buffers to the number under test and prove less, not more.
 */
@DisplayName("Community HTTP client: a response is sized by the response")
class CommunityHttpClientResponseSizingTest {

    private static final long CEILING_10_MIB = 10L * 1024 * 1024;
    private static final int KIB = 1024;

    @Nested
    @DisplayName("A small response does not pay for a large ceiling")
    class SmallResponses {

        @Test
        @DisplayName("a HEAD allocates a fraction of the ceiling, even when it declares a huge body")
        void headDoesNotPayForTheCeiling() {
            // RFC 9110 §6.4.1: the Content-Length on a HEAD response is the size the body WOULD
            // have. Nothing follows the headers, so it must not size an allocation either.
            String canned = "HTTP/1.1 200 OK\r\nContent-Length: " + CEILING_10_MIB + "\r\n\r\n";

            Exchange exchange = exchange(CEILING_10_MIB, canned.getBytes(StandardCharsets.US_ASCII),
                    HttpRequest.noBody(HttpMethod.HEAD, "/object", HttpVersion.HTTP_1_1, List.of()));

            assertThat(exchange.response().body()).as("a HEAD carries no body").isNull();
            assertThat(exchange.peakBytes())
                    .as("the declared size of an absent body must not reach the allocator")
                    .isLessThan(64 * KIB);
        }

        @Test
        @DisplayName("a 200-byte GET allocates a fraction of the ceiling")
        void smallGetDoesNotPayForTheCeiling() {
            byte[] canned = response(200);

            Exchange exchange = exchange(CEILING_10_MIB, canned,
                    HttpRequest.noBody(HttpMethod.GET, "/small", HttpVersion.HTTP_1_1, List.of()));

            assertThat(bodyLength(exchange.response())).isEqualTo(200);
            assertThat(exchange.peakBytes())
                    .as("a small response against a 10 MiB ceiling")
                    .isLessThan(64 * KIB);
        }
    }

    @Nested
    @DisplayName("Growing to the response is not the same as truncating to the buffer")
    class LargerResponses {

        @Test
        @DisplayName("a body past the initial buffer arrives whole, and still well under the ceiling")
        void contentLengthSizesTheGrowth() {
            int bodyBytes = 100 * KIB;

            Exchange exchange = exchange(CEILING_10_MIB, response(bodyBytes),
                    HttpRequest.noBody(HttpMethod.GET, "/blob", HttpVersion.HTTP_1_1, List.of()));

            assertThat(bodyLength(exchange.response()))
                    .as("the grow-and-copy must not lose or duplicate a byte")
                    .isEqualTo(bodyBytes);
            assertThat(exchange.bodyBytes())
                    .as("and must move the bytes it kept — a length that survives a copy that did "
                            + "not happen is not evidence the copy happened")
                    .isEqualTo(filler(bodyBytes));
            assertThat(exchange.peakBytes())
                    .as("Content-Length is known before the growth, so one more allocation ends it")
                    .isLessThan(512 * KIB);
        }

        @Test
        @DisplayName("a response with no Content-Length still completes, by doubling until the peer stops")
        void framelessResponsesDoubleInstead() {
            int bodyBytes = 30 * KIB;
            // Carries a header on purpose. A response with NO header at all is legal per RFC 9112
            // §2.1 and the decoder rejects it: the read loop scans for CRLFCRLF from offset 0 and
            // finds the status line's own terminator, while decodeResponse scans from after the
            // status line and finds nothing. Separate from what this test measures, and a peer that
            // sends no headers at all is not the shape being exercised here.
            byte[] canned = concat(
                    "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII),
                    filler(bodyBytes));

            Exchange exchange = exchange(CEILING_10_MIB, canned,
                    HttpRequest.noBody(HttpMethod.GET, "/stream", HttpVersion.HTTP_1_1, List.of()));

            assertThat(bodyLength(exchange.response()))
                    .as("connection-framed: everything after the headers is the body")
                    .isEqualTo(bodyBytes);
            assertThat(exchange.peakBytes())
                    .as("doubling is bounded by what arrived, not by the ceiling")
                    .isLessThan(512 * KIB);
        }
    }

    @Nested
    @DisplayName("The ceiling still ends the read")
    class Overrun {

        @Test
        @DisplayName("a response past the configured ceiling is refused, not silently truncated")
        void overrunStillRefuses() {
            // 1 KiB ceiling: the reader may reach 1 KiB + the 8 KiB header allowance and no further.
            long ceiling = KIB;
            byte[] canned = response(20 * KIB);

            assertThatThrownBy(() -> exchange(ceiling, canned,
                    HttpRequest.noBody(HttpMethod.GET, "/toobig", HttpVersion.HTTP_1_1, List.of())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Truncated HTTP response body");
        }
    }

    // ---------------------------------------------------------------- fixtures

    private record Exchange(HttpResponse response, long peakBytes, byte[] bodyBytes) {
    }

    /**
     * Runs one request against a canned peer on a private allocator and reports the peak.
     *
     * <p>The response is decoded before the allocator is read, and the body buffer is released
     * afterwards, so the peak includes the body copy the decoder makes — which is part of what a
     * response costs and belongs in the number.
     */
    private static Exchange exchange(long responseCeiling, byte[] cannedResponse, HttpRequest request) {
        try (MemoryAllocator allocator =
                     new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults())) {
            HttpResponse response;
            try (CommunityHttpClientEngine engine = new CommunityHttpClientEngine(
                    clientConfig(responseCeiling), allocator, new CannedTransport(cannedResponse),
                    false, "127.0.0.1:9")) {
                engine.start();
                response = engine.send(request);
            }
            long peak = allocator.stats().peakAllocatedBytes();
            byte[] body = new byte[0];
            if (response.body() != null) {
                body = response.body().segment().asSlice(0, response.body().size())
                        .toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
                response.body().close();
            }
            return new Exchange(response, peak, body);
        }
    }

    private static long bodyLength(HttpResponse response) {
        return response.body() == null ? 0L : response.body().size();
    }

    private static byte[] response(int bodyBytes) {
        byte[] head = ("HTTP/1.1 200 OK\r\nContent-Length: " + bodyBytes + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        return concat(head, filler(bodyBytes));
    }

    private static byte[] filler(int length) {
        byte[] body = new byte[length];
        for (int i = 0; i < length; i++) {
            body[i] = (byte) ('a' + (i % 26));
        }
        return body;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = new byte[first.length + second.length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    private static HttpConfig clientConfig(long responseCeiling) {
        return new HttpConfig(
                HttpMode.CLIENT, "127.0.0.1", -1, 8, 30_000L, 100, 8_192,
                CEILING_10_MIB, false, HttpVersion.HTTP_1_1, "127.0.0.1:9",
                65_536, 65_536, 65_536, responseCeiling);
    }

    /**
     * A peer that has already decided what it will say. Reads hand back the canned bytes a chunk at
     * a time and then report end-of-stream; writes are accepted and dropped.
     */
    private static final class CannedTransport implements TransportEngine {

        private final byte[] canned;

        private CannedTransport(byte[] canned) {
            this.canned = canned.clone();
        }

        @Override
        public TransportConnection connect(String host, int port) {
            return new CannedConnection(canned);
        }

        @Override
        public void setStreamHandler(StreamHandler handler) {
            // client-side: nothing accepts here
        }

        @Override
        public void setConnectionHandler(ConnectionHandler handler) {
            // client-side: nothing accepts here
        }

        @Override
        public void start() {
            // no listener to bind
        }

        @Override
        public void stop() {
            // no listener to unbind
        }

        @Override
        public TransportMode mode() {
            return TransportMode.CLIENT;
        }

        @Override
        public TransportStats stats() {
            return TransportStats.EMPTY;
        }

        @Override
        public String engineName() {
            return "canned-peer";
        }

        @Override
        public void close() {
            // nothing owned
        }
    }

    private static final class CannedConnection implements TransportConnection {

        private final byte[] canned;
        private boolean open = true;
        private Object attachment;

        private CannedConnection(byte[] canned) {
            this.canned = canned;
        }

        @Override
        public TransportStream openStream() {
            return new CannedStream(this, canned);
        }

        @Override
        public TransportStream openUnidirectionalStream() {
            return openStream();
        }

        @Override
        public String remoteAddress() {
            return "127.0.0.1";
        }

        @Override
        public int remotePort() {
            return 9;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public Object attachment() {
            return attachment;
        }

        @Override
        public void setAttachment(Object value) {
            this.attachment = value;
        }

        @Override
        public boolean tick() {
            return false;
        }

        @Override
        public void close() {
            open = false;
        }
    }

    private static final class CannedStream implements TransportStream {

        private final CannedConnection connection;
        private final byte[] canned;
        private int offset;

        private CannedStream(CannedConnection connection, byte[] canned) {
            this.connection = connection;
            this.canned = canned;
        }

        @Override
        public int read(MemorySegment target, int maxBytes) {
            if (offset >= canned.length) {
                return -1;
            }
            int count = Math.min(maxBytes, canned.length - offset);
            MemorySegment.copy(canned, offset, target, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, count);
            offset += count;
            return count;
        }

        @Override
        public void write(MemorySegment source, int length) {
            // the request is not what this test measures
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
            return offset < canned.length;
        }

        @Override
        public void close() {
            offset = canned.length;
        }
    }
}
