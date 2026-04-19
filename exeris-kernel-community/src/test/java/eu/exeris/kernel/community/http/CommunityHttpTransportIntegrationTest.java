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
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMode;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpResponseBodyEncoderRegistry;
import eu.exeris.kernel.spi.http.HttpStatus;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Community: HTTP over transport integration")
class CommunityHttpTransportIntegrationTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    @AfterAll
    @SuppressWarnings("unused")
    static void closeAllocator() {
        ALLOCATOR.close();
    }

    @Test
    @DisplayName("Server parses HTTP/1.1 request and returns status line over injected transport")
    void servesHttpResponseOverTransport() {
        HttpConfig config = new HttpConfig(
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
                        + "Host: 127.0.0.1\r\n"
                        + "Connection: close\r\n"
                        + "\r\n"
        );

        CommunityHttpServerEngine server = new CommunityHttpServerEngine(
                config,
                ALLOCATOR,
                transport,
                18080,
                HttpResponseBodyEncoderRegistry.empty()
        );

        server.setHandler(exchange -> exchange.respond(new HttpResponse(
                HttpStatus.OK,
                exchange.request().version(),
                List.of(new HttpHeader("Content-Type", "text/plain")),
                null
        )));

        server.start();
        try {
            String response = transport.capturedResponse();
            String[] lines = response.split("\\r\\n");

            assertThat(lines[0]).isEqualTo("HTTP/1.1 200 OK");
            assertThat(response).contains("Connection: close");
        } finally {
            server.close();
        }
    }

        @Test
        @DisplayName("Server handles two HTTP/1.1 keep-alive requests on one stream")
        void servesTwoKeepAliveRequestsOverSingleStream() {
        HttpConfig config = new HttpConfig(
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
                + "Host: 127.0.0.1\r\n"
                + "Connection: keep-alive\r\n"
                + "\r\n"
                + "GET /health HTTP/1.1\r\n"
                + "Host: 127.0.0.1\r\n"
                + "Connection: keep-alive\r\n"
                + "\r\n"
        );

        CommunityHttpServerEngine server = new CommunityHttpServerEngine(
            config,
            ALLOCATOR,
            transport,
            18080,
            HttpResponseBodyEncoderRegistry.empty()
        );

        server.setHandler(exchange -> exchange.respond(new HttpResponse(
            HttpStatus.OK,
            exchange.request().version(),
            List.of(new HttpHeader("Content-Type", "text/plain")),
            null
        )));

        server.start();
        try {
            String response = transport.capturedResponse();
            long statusLineCount = Pattern.compile("HTTP/1\\.1 200 OK")
                .matcher(response)
                .results()
                .count();

            assertThat(statusLineCount).isEqualTo(2);
        } finally {
            server.close();
        }
        }

    private static final class FakeTransportEngine implements TransportEngine {

        private final FakeTransportStream stream;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private StreamHandler streamHandler;

        FakeTransportEngine(String requestPayload) {
            this.stream = new FakeTransportStream(requestPayload);
        }

        @Override
        public void setStreamHandler(StreamHandler handler) {
            this.streamHandler = handler;
        }

        @Override
        public void setConnectionHandler(ConnectionHandler handler) {
        }

        @Override
        public void start() {
            running.set(true);
            streamHandler.handle(stream);
        }

        @Override
        public void stop() {
            running.set(false);
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
            running.set(false);
        }

        String capturedResponse() {
            return stream.writtenPayload();
        }
    }

    private static final class FakeTransportStream implements TransportStream {

        private final MemorySegment requestBytes;
        private final byte[] written = new byte[8 * 1024];
        private int readOffset;
        private int writtenSize;
        private boolean closed;

        FakeTransportStream(String requestPayload) {
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

        String writtenPayload() {
            return new String(written, 0, writtenSize, StandardCharsets.US_ASCII);
        }
    }
}
