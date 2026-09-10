/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.http.HttpEncodedBody;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpResponseBodyEncoder;
import eu.exeris.kernel.spi.http.HttpResponseBodyEncoderRegistry;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpTypedResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportStream;
import eu.exeris.kernel.tck.contract.http.AbstractHttpExchangeTck;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Community: HttpExchange TCK")
class CommunityHttpExchangeTckTest extends AbstractHttpExchangeTck {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    @AfterAll
    @SuppressWarnings("unused")
    static void closeAllocator() {
        ALLOCATOR.close();
    }

    @Override
    protected HttpExchange createExchange(HttpRequest request) {
        return new CommunityHttpExchange(request, new RecordingStream(), ALLOCATOR, true, HttpResponseBodyEncoderRegistry.empty());
    }

    @Test
    void respondEmitsSingleEncodedHttpResponse() {
        HttpRequest request = HttpRequest.noBody(HttpMethod.GET, "/health", HttpVersion.HTTP_1_1, List.of());
        CapturingStream stream = new CapturingStream();
        CommunityHttpExchange exchange = new CommunityHttpExchange(
                request,
                stream,
                ALLOCATOR,
                true,
                HttpResponseBodyEncoderRegistry.empty());

        LoanedBuffer body = ALLOCATOR.allocateNetwork(5);
        MemorySegment.copy(MemorySegment.ofArray("hello".getBytes(StandardCharsets.UTF_8)), 0, body.segment(), 0, 5);
        body.setSize(5);

        exchange.respond(new HttpResponse(HttpStatus.OK, HttpVersion.HTTP_1_1, List.of(), body));

        assertEquals(1, stream.emissionCalls, "Exchange should emit exactly one outbound response");
        assertEquals("HTTP/1.1 200 OK", stream.statusLine);
        assertEquals("hello", stream.bodyText);
    }

    @Test
    void typedResponseEncodingClosesBodyWhenExchangeAlreadyResponded() {
        HttpRequest request = HttpRequest.noBody(HttpMethod.GET, "/health", HttpVersion.HTTP_1_1, List.of());
        LoanedBuffer[] encodedBodyRef = new LoanedBuffer[1];
        HttpResponseBodyEncoder encoder = new HttpResponseBodyEncoder() {
            @Override
            public boolean supports(Class<?> payloadType) {
                return String.class.equals(payloadType);
            }

            @Override
            public HttpEncodedBody encode(Object payload, eu.exeris.kernel.spi.http.HttpResponseEncodingContext context) {
                byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
                LoanedBuffer encodedBody = context.allocator().allocateNetwork(bytes.length);
                MemorySegment.copy(MemorySegment.ofArray(bytes), 0, encodedBody.segment(), 0, bytes.length);
                encodedBody.setSize(bytes.length);
                encodedBodyRef[0] = encodedBody;
                return new HttpEncodedBody(List.of(), encodedBody);
            }
        };
        CommunityHttpExchange exchange = new CommunityHttpExchange(
                request,
                new RecordingStream(),
                ALLOCATOR,
                true,
                payloadType -> encoder.supports(payloadType) ? encoder : null);

        exchange.respond(HttpResponse.noBody(HttpStatus.OK, HttpVersion.HTTP_1_1));

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> exchange.respond(HttpTypedResponse.of(HttpStatus.OK, "typed-body")));

        assertEquals("respond() already called for this exchange", ex.getMessage());
        if (encodedBodyRef[0] != null) {
            assertFalse(encodedBodyRef[0].isAlive(), "Encoded typed body should be released on failed respond");
            assertEquals(0, encodedBodyRef[0].refCount(), "Encoded typed body should not leak ownership");
        }
    }

    private static final class RecordingStream implements TransportStream {

        @Override
        public int read(MemorySegment target, int maxBytes) {
            return -1;
        }

        @Override
        public void write(MemorySegment source, int length) {
            // no-op
        }

        @Override
        public void queueWrite(LoanedBuffer buffer, int length) {
            buffer.close();
        }

        @Override
        public long streamId() {
            return 0;
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
            throw new UnsupportedOperationException("Not required by exchange TCK");
        }

        @Override
        public boolean hasPendingData() {
            return false;
        }

        @Override
        public void close() {
            // no-op
        }
    }

    private static final class CapturingStream implements TransportStream {

        private int emissionCalls;
        private String statusLine = "";
        private String bodyText = "";

        @Override
        public int read(MemorySegment target, int maxBytes) {
            return -1;
        }

        @Override
        public void write(MemorySegment source, int length) {
            capturePayload(source, length);
        }

        @Override
        public void queueWrite(LoanedBuffer buffer, int length) {
            try {
                capturePayload(buffer.segment(), length);
            } finally {
                buffer.close();
            }
        }

        private void capturePayload(MemorySegment source, int length) {
            emissionCalls++;
            byte[] bytes = source.asSlice(0, length).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
            String payload = new String(bytes, StandardCharsets.US_ASCII);
            int lineEnd = payload.indexOf("\r\n");
            statusLine = lineEnd >= 0 ? payload.substring(0, lineEnd) : payload;
            int bodyStart = payload.indexOf("\r\n\r\n");
            bodyText = bodyStart >= 0 ? payload.substring(bodyStart + 4) : payload;
        }

        @Override
        public long streamId() {
            return 0;
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
            throw new UnsupportedOperationException("Not required by exchange test");
        }

        @Override
        public boolean hasPendingData() {
            return false;
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
