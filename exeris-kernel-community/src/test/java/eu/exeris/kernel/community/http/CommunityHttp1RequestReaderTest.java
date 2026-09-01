/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.core.http.http1.Http1Codec;
import eu.exeris.kernel.core.http.http1.Http1RequestParser;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The reader derives its header list from the codec's single pass (v0.12). These pin the observable
 * results of that: what the list contains, that it is not writable, and that the connection state
 * the same pass produces still agrees with it.
 */
class CommunityHttp1RequestReaderTest {

    private MemoryAllocator allocator;

    @BeforeEach
    void setUp() {
        allocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    }

    @AfterEach
    void tearDown() {
        allocator.close();
    }

    @Test
    void headersAndConnectionStateComeOffTheSamePassAndAgree() {
        String request = "POST /orders HTTP/1.1\r\n"
                + "Host: service.internal\r\n"
                + "Content-Length: 5\r\n"
                + "Connection: close\r\n"
                + "\r\n"
                + "hello";

        withRequest(request, (codec, buffer, total) -> {
            ReadResult result = CommunityHttp1RequestReader.tryParseRequest(codec, buffer, total);

            assertThat(result).isNotNull();
            assertThat(result.method()).isEqualTo(HttpMethod.POST);
            assertThat(result.path()).isEqualTo("/orders");
            assertThat(result.version()).isEqualTo(HttpVersion.HTTP_1_1);
            assertThat(result.headers())
                    .as("every field once, in wire order -- a second pass would double these")
                    .extracting(HttpHeader::name)
                    .containsExactly("Host", "Content-Length", "Connection");
            assertThat(result.headers())
                    .extracting(HttpHeader::value)
                    .containsExactly("service.internal", "5", "close");
            assertThat(result.bodyLength())
                    .as("the body length the same pass derived")
                    .isEqualTo(5);
            assertThat(result.keepAlive())
                    .as("the keep-alive the same pass derived")
                    .isFalse();
        });
    }

    @Test
    void theHeaderListHandedOnIsNotWritable() {
        // HttpRequest documents its header list as immutable and this is the list that becomes one.
        // The reader stopped copying it in v0.12; what makes that safe is that nothing else holds
        // the backing list, so the view is the only reference -- and it has to refuse writes.
        String request = "GET / HTTP/1.1\r\nHost: service.internal\r\n\r\n";

        withRequest(request, (codec, buffer, total) -> {
            ReadResult result = CommunityHttp1RequestReader.tryParseRequest(codec, buffer, total);

            assertThat(result).isNotNull();
            List<HttpHeader> headers = result.headers();
            assertThatThrownBy(() -> headers.add(new HttpHeader("X-Injected", "1")))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(headers::clear)
                    .isInstanceOf(UnsupportedOperationException.class);
        });
    }

    @Test
    void aRequestWhoseBodyHasNotArrivedYieldsNothing() {
        // Content-Length declares more than the buffer holds. The single pass has already visited
        // the fields by this point, so the check that matters is that the reader still reports
        // "not yet" rather than handing on a request with a short body.
        String request = "POST /orders HTTP/1.1\r\nContent-Length: 32\r\n\r\nshort";

        withRequest(request, (codec, buffer, total) ->
                assertThat(CommunityHttp1RequestReader.tryParseRequest(codec, buffer, total))
                        .as("incomplete body must not produce a request")
                        .isNull());
    }

    @Test
    void theConfiguredHeaderBoundStillGovernsTheSinglePass() {
        // ADR-071 held because both passes were handed the same bound. There is one pass now, but
        // the bound it enforces must still be the operator's: three headers against a bound of two,
        // which the parser default of 100 would accept.
        String request = "GET / HTTP/1.1\r\nA: 1\r\nB: 2\r\nC: 3\r\n\r\n";
        byte[] bytes = request.getBytes(StandardCharsets.ISO_8859_1);

        try (LoanedBuffer buffer = allocator.allocateNetwork(bytes.length)) {
            MemorySegment.copy(bytes, 0, buffer.segment(), ValueLayout.JAVA_BYTE, 0, bytes.length);
            buffer.setSize(bytes.length);

            assertThatThrownBy(() -> CommunityHttp1RequestReader.tryParseRequest(
                    new Http1Codec(2, 8_192), buffer, bytes.length))
                    .isInstanceOf(Http1RequestParser.Http1ParseException.class);
        }
    }

    @FunctionalInterface
    private interface RequestCase {
        void run(Http1Codec codec, LoanedBuffer buffer, long total);
    }

    private void withRequest(String request, RequestCase body) {
        byte[] bytes = request.getBytes(StandardCharsets.ISO_8859_1);
        try (LoanedBuffer buffer = allocator.allocateNetwork(bytes.length)) {
            MemorySegment.copy(bytes, 0, buffer.segment(), ValueLayout.JAVA_BYTE, 0, bytes.length);
            buffer.setSize(bytes.length);
            body.run(new Http1Codec(), buffer, bytes.length);
        }
    }
}
