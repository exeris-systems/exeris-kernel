/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.FaultOrigin;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The decoder stopped materialising a line, a name substring and a value substring per field in
 * v0.12, and stopped building a header list twice per response. These pin what must not have moved:
 * the characters, the whitespace handling, which lines are skipped, and what counts as a parseable
 * {@code Content-Length}.
 */
class CommunityHttpClientResponseDecoderTest {

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
    void namesAndValuesArriveWithTheCharactersTheWireCarried() {
        // Mixed known and unknown names, and known names in unknown casings, which must come through
        // as sent rather than borrowing the table's spelling.
        List<HttpHeader> headers = headersOf(
                "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json\r\n"
                + "content-length: 0\r\n"
                + "CONTENT-ENCODING: gzip\r\n"
                + "X-Wholly-Invented: yes\r\n"
                + "\r\n");

        assertThat(headers).extracting(HttpHeader::name).containsExactly(
                "Content-Type", "content-length", "CONTENT-ENCODING", "X-Wholly-Invented");
        assertThat(headers).extracting(HttpHeader::value).containsExactly(
                "application/json", "0", "gzip", "yes");
    }

    @Test
    void whitespaceIsTrimmedFromBothSidesOfBothHalves() {
        // The previous implementation materialised the line and called String.trim() on each half.
        // Trimming now happens on byte offsets, so this is the property that had to be reproduced --
        // including tabs, and including whitespace before the colon, which RFC 9112 forbids but the
        // decoder has always tolerated.
        List<HttpHeader> headers = headersOf(
                "HTTP/1.1 200 OK\r\n"
                + "Content-Length:0\r\n"
                + "X-Padded:    spaced   \r\n"
                + "X-Tabbed:\ttabbed\t\r\n"
                + "X-Pre-Colon : before\r\n"
                + "\r\n");

        assertThat(headers).extracting(HttpHeader::name)
                .containsExactly("Content-Length", "X-Padded", "X-Tabbed", "X-Pre-Colon");
        assertThat(headers).extracting(HttpHeader::value)
                .containsExactly("0", "spaced", "tabbed", "before");
    }

    @Test
    void anEmptyValueSurvivesAsEmpty() {
        List<HttpHeader> headers = headersOf(
                "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nX-Empty:\r\nX-Blank:   \r\n\r\n");

        assertThat(headers).extracting(HttpHeader::name).contains("X-Empty", "X-Blank");
        assertThat(headers).filteredOn(header -> header.name().startsWith("X-"))
                .extracting(HttpHeader::value).containsOnly("");
    }

    @Test
    void linesWithoutAUsableColonAreRejected() {
        // Both cases: no colon at all, and a colon at position zero (missing header name) per RFC 9112 §5.1
        String noColonWire = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nGarbageWithNoColon\r\n\r\n";
        assertThatThrownBy(() -> headersOf(noColonWire))
                .isInstanceOf(ExerisKernelException.class)
                .satisfies(ex -> {
                    ExerisKernelException ke = (ExerisKernelException) ex;
                    assertThat(ke.faultOrigin()).isEqualTo(FaultOrigin.SYSTEM);
                    assertThat(ke.errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004);
                });

        String leadingColonWire = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n: leading-colon\r\n\r\n";
        assertThatThrownBy(() -> headersOf(leadingColonWire))
                .isInstanceOf(ExerisKernelException.class)
                .satisfies(ex -> {
                    ExerisKernelException ke = (ExerisKernelException) ex;
                    assertThat(ke.faultOrigin()).isEqualTo(FaultOrigin.SYSTEM);
                    assertThat(ke.errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004);
                });
    }

    @Test
    void aHighBitByteStaysTheReplacementCharacterUsAsciiProduces() {
        // The subtle half of the rewrite. Decoding is US-ASCII, so a byte with the high bit set
        // becomes U+FFFD -- which is NOT whitespace and must not be trimmed away. Comparing bytes
        // SIGNED would make 0x80 read as -128, look like a control character, and eat it.
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        writeAscii(wire, "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nX-Odd: ");
        wire.write(0x80);
        writeAscii(wire, "ab\r\n\r\n");

        List<HttpHeader> headers = headersOfBytes(wire.toByteArray());

        assertThat(headers).filteredOn(header -> "X-Odd".equals(header.name()))
                .singleElement()
                .satisfies(header -> assertThat(header.value()).isEqualTo("�ab"));
    }

    @Test
    void theHeaderListHandedOnIsNotWritable() {
        List<HttpHeader> headers = headersOf("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n");

        assertThatThrownBy(() -> headers.add(new HttpHeader("X-Injected", "1")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void contentLengthDrivesTheExpectedTotalWhateverItsCasing() {
        // The read loop reads this field without building a header list now, so its own matching has
        // to stay case-insensitive the way a header list's would have been.
        String lower = "HTTP/1.1 200 OK\r\ncontent-length: 5\r\n\r\nhello";
        String upper = "HTTP/1.1 200 OK\r\nCONTENT-LENGTH: 5\r\n\r\nhello";
        assertThat(expectedTotalOf(lower)).isEqualTo(lower.length());
        assertThat(expectedTotalOf(upper)).isEqualTo(upper.length());
    }

    @Test
    void anAbsentContentLengthLeavesTheTotalUnresolved() {
        assertThat(expectedTotalOf("HTTP/1.1 200 OK\r\nServer: x\r\n\r\n")).isEqualTo(-1L);
    }

    @Test
    void anUnparseableOrNegativeContentLengthThrowsFailFast() {
        assertThatThrownBy(() -> expectedTotalOf("HTTP/1.1 200 OK\r\nContent-Length: nope\r\n\r\n"))
                .isInstanceOf(ExerisKernelException.class)
                .satisfies(ex -> assertThat(((ExerisKernelException) ex).errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004));
        assertThatThrownBy(() -> expectedTotalOf("HTTP/1.1 200 OK\r\nContent-Length: -3\r\n\r\n"))
                .isInstanceOf(ExerisKernelException.class)
                .satisfies(ex -> assertThat(((ExerisKernelException) ex).errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004));
    }

    @Test
    void conflictingMultipleContentLengthsAreRejectedFailClosed() {
        // RFC 9112 §6.3: Multiple conflicting Content-Length headers must be rejected fail-closed.
        String wire = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\nContent-Length: 99\r\n\r\nhello";
        assertThatThrownBy(() -> expectedTotalOf(wire))
                .isInstanceOf(ExerisKernelException.class)
                .satisfies(ex -> assertThat(((ExerisKernelException) ex).errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004));

        withWire(wire.getBytes(StandardCharsets.US_ASCII), (buffer, total) -> {
            assertThatThrownBy(() -> CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false))
                    .isInstanceOf(ExerisKernelException.class)
                    .satisfies(ex -> assertThat(((ExerisKernelException) ex).errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004));
            return null;
        });
    }

    @Test
    void duplicateIdenticalContentLengthsAreAccepted() {
        // RFC 9112 §6.3: Duplicate Content-Length headers with identical values are acceptable.
        String wire = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\nContent-Length: 5\r\n\r\nhello";
        assertThat(expectedTotalOf(wire)).isEqualTo(wire.length());

        withWire(wire.getBytes(StandardCharsets.US_ASCII), (buffer, total) -> {
            HttpResponse response = CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false);
            assertThat(response.status().code()).isEqualTo(200);
            assertThat(response.body()).isNotNull();
            assertThat(response.body().size()).isEqualTo(5L);
            response.body().close();
            return null;
        });
    }

    @Test
    void aResponseBodyIsDecodedToItsDeclaredLength() {
        String wire = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 5\r\n\r\nhello";
        withWire(wire.getBytes(StandardCharsets.ISO_8859_1), (buffer, total) -> {
            HttpResponse response = CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false);
            assertThat(response.status().code()).isEqualTo(200);
            assertThat(response.body()).isNotNull();
            assertThat(response.body().size()).isEqualTo(5L);
            response.body().close();
            return null;
        });
    }

    private static void writeAscii(ByteArrayOutputStream sink, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        sink.write(bytes, 0, bytes.length);
    }

    private List<HttpHeader> headersOf(String wire) {
        return headersOfBytes(wire.getBytes(StandardCharsets.ISO_8859_1));
    }

    private List<HttpHeader> headersOfBytes(byte[] wire) {
        return withWire(wire, (buffer, total) -> {
            HttpResponse response = CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, true);
            if (response.body() != null) {
                response.body().close();
            }
            return response.headers();
        });
    }

    private long expectedTotalOf(String wire) {
        byte[] bytes = wire.getBytes(StandardCharsets.ISO_8859_1);
        return withWire(bytes, (buffer, total) -> {
            long terminator = CommunityHttpClientResponseDecoder.resolveHeaderTerminator(
                    -1, buffer.segment(), total);
            return CommunityHttpClientResponseDecoder.resolveExpectedTotal(
                    -1, buffer.segment(), total, terminator, false);
        });
    }

    private <T> T withWire(byte[] wire, BiFunction<LoanedBuffer, Long, T> body) {
        try (LoanedBuffer buffer = allocator.allocateNetwork(Math.max(wire.length, 64))) {
            MemorySegment.copy(wire, 0, buffer.segment(), ValueLayout.JAVA_BYTE, 0, wire.length);
            buffer.setSize(wire.length);
            return body.apply(buffer, (long) wire.length);
        }
    }

    @Test
    void containsConnectionTokenMatchesTokensCaseInsensitivelyWithoutAllocations() {
        assertThat(CommunityHttpClientResponseDecoder.tokenMatches("close", "close")).isTrue();
        assertThat(CommunityHttpClientResponseDecoder.tokenMatches("CLOSE", "close")).isTrue();
        assertThat(CommunityHttpClientResponseDecoder.tokenMatches("  close  ", "close")).isTrue();
        assertThat(CommunityHttpClientResponseDecoder.tokenMatches("keep-alive, close", "close")).isTrue();
        assertThat(CommunityHttpClientResponseDecoder.tokenMatches("close, keep-alive", "close")).isTrue();
        assertThat(CommunityHttpClientResponseDecoder.tokenMatches("keep-alive, Upgrade", "upgrade")).isTrue();
        assertThat(CommunityHttpClientResponseDecoder.tokenMatches("closer", "close")).isFalse();
        assertThat(CommunityHttpClientResponseDecoder.tokenMatches("is-close", "close")).isFalse();
        assertThat(CommunityHttpClientResponseDecoder.tokenMatches("", "close")).isFalse();
        assertThat(CommunityHttpClientResponseDecoder.tokenMatches(",,,", "close")).isFalse();

        List<HttpHeader> headers = List.of(
                new HttpHeader("Content-Type", "text/plain"),
                new HttpHeader("Connection", "keep-alive, Upgrade")
        );
        assertThat(CommunityHttpClientResponseDecoder.containsConnectionToken(headers, "upgrade")).isTrue();
        assertThat(CommunityHttpClientResponseDecoder.containsConnectionToken(headers, "close")).isFalse();
    }

    @Test
    void isKeepAliveRejects1xxAndUpgradesAndTransferEncoding() {
        TransportConnection connection = new TransportConnection() {
            @Override
            public TransportStream openStream() {
                return null;
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
                return true;
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
            }
        };

        HttpRequest getRequest = HttpRequest.noBody(HttpMethod.GET, "/test", HttpVersion.HTTP_1_1, List.of());
        HttpRequest headRequest = HttpRequest.noBody(HttpMethod.HEAD, "/test", HttpVersion.HTTP_1_1, List.of());

        // 200 OK with Content-Length is keep-alive
        HttpResponse okResponse = new HttpResponse(
                HttpStatus.OK, HttpVersion.HTTP_1_1,
                List.of(new HttpHeader("Content-Length", "5")), null);
        assertThat(CommunityHttpClientResponseDecoder.isKeepAlive(getRequest, okResponse, connection)).isTrue();

        // 204 No Content and 304 Not Modified are bodyless keep-alive
        HttpResponse noContentResponse = HttpResponse.noBody(HttpStatus.NO_CONTENT, HttpVersion.HTTP_1_1);
        assertThat(CommunityHttpClientResponseDecoder.isKeepAlive(getRequest, noContentResponse, connection)).isTrue();
        HttpResponse notModifiedResponse = HttpResponse.noBody(HttpStatus.NOT_MODIFIED, HttpVersion.HTTP_1_1);
        assertThat(CommunityHttpClientResponseDecoder.isKeepAlive(getRequest, notModifiedResponse, connection)).isTrue();

        // HEAD request is bodyless keep-alive
        HttpResponse headResponse = HttpResponse.noBody(HttpStatus.OK, HttpVersion.HTTP_1_1);
        assertThat(CommunityHttpClientResponseDecoder.isKeepAlive(headRequest, headResponse, connection)).isTrue();

        // 101 Switching Protocols MUST NOT be keep-alive (RFC 9110 §15.2 / RFC 9112 §6.3)
        HttpResponse switchingProtocolsResponse = HttpResponse.noBody(HttpStatus.SWITCHING_PROTOCOLS, HttpVersion.HTTP_1_1);
        assertThat(CommunityHttpClientResponseDecoder.isKeepAlive(getRequest, switchingProtocolsResponse, connection)).isFalse();

        // 100 Continue and 103 Early Hints MUST NOT be keep-alive
        HttpResponse continueResponse = HttpResponse.noBody(HttpStatus.CONTINUE, HttpVersion.HTTP_1_1);
        assertThat(CommunityHttpClientResponseDecoder.isKeepAlive(getRequest, continueResponse, connection)).isFalse();
        HttpResponse hintsResponse = HttpResponse.noBody(HttpStatus.EARLY_HINTS, HttpVersion.HTTP_1_1);
        assertThat(CommunityHttpClientResponseDecoder.isKeepAlive(getRequest, hintsResponse, connection)).isFalse();

        // Connection: Upgrade in request or response MUST NOT be keep-alive
        HttpRequest upgradeRequest = HttpRequest.noBody(
                HttpMethod.GET, "/ws", HttpVersion.HTTP_1_1,
                List.of(new HttpHeader("Connection", "Upgrade"), new HttpHeader("Upgrade", "websocket")));
        assertThat(CommunityHttpClientResponseDecoder.isKeepAlive(upgradeRequest, okResponse, connection)).isFalse();

        HttpResponse upgradeResponse = new HttpResponse(
                HttpStatus.OK, HttpVersion.HTTP_1_1,
                List.of(new HttpHeader("Content-Length", "5"), new HttpHeader("Connection", "Upgrade")), null);
        assertThat(CommunityHttpClientResponseDecoder.isKeepAlive(getRequest, upgradeResponse, connection)).isFalse();

        // Transfer-Encoding MUST NOT be keep-alive (RFC 9112 §6.1)
        HttpResponse chunkedResponse = new HttpResponse(
                HttpStatus.OK, HttpVersion.HTTP_1_1,
                List.of(new HttpHeader("Transfer-Encoding", "chunked"), new HttpHeader("Content-Length", "5")), null);
        assertThat(CommunityHttpClientResponseDecoder.isKeepAlive(getRequest, chunkedResponse, connection)).isFalse();

        // Direct Upgrade header in request or response (without Connection: Upgrade) MUST NOT be keep-alive
        HttpRequest plainUpgradeRequest = HttpRequest.noBody(
                HttpMethod.GET, "/ws", HttpVersion.HTTP_1_1,
                List.of(new HttpHeader("Upgrade", "websocket")));
        assertThat(CommunityHttpClientResponseDecoder.isKeepAlive(plainUpgradeRequest, okResponse, connection)).isFalse();

        HttpResponse plainUpgradeResponse = new HttpResponse(
                HttpStatus.OK, HttpVersion.HTTP_1_1,
                List.of(new HttpHeader("Content-Length", "5"), new HttpHeader("Upgrade", "websocket")), null);
        assertThat(CommunityHttpClientResponseDecoder.isKeepAlive(getRequest, plainUpgradeResponse, connection)).isFalse();

        // Transfer-Encoding in request MUST NOT be keep-alive
        HttpRequest chunkedRequest = HttpRequest.noBody(
                HttpMethod.POST, "/data", HttpVersion.HTTP_1_1,
                List.of(new HttpHeader("Transfer-Encoding", "chunked")));
        assertThat(CommunityHttpClientResponseDecoder.isKeepAlive(chunkedRequest, okResponse, connection)).isFalse();
    }

    @Test
    void decodeResponseRejectsMalformedStatusCodes() {
        // 4 digits status code rejected
        byte[] fourDigits = "HTTP/1.1 2000 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(fourDigits, (buffer, total) -> {
            assertThatThrownBy(() -> CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false))
                    .isInstanceOf(ExerisKernelException.class)
                    .satisfies(ex -> assertThat(((ExerisKernelException) ex).errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004));
            return null;
        });

        // 2 digits status code rejected
        byte[] twoDigits = "HTTP/1.1 20 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(twoDigits, (buffer, total) -> {
            assertThatThrownBy(() -> CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false))
                    .isInstanceOf(ExerisKernelException.class)
                    .satisfies(ex -> assertThat(((ExerisKernelException) ex).errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004));
            return null;
        });

        // Valid status without reason phrase succeeds (no trailing space)
        byte[] noReason = "HTTP/1.1 204\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(noReason, (buffer, total) -> {
            HttpResponse response = CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false);
            assertThat(response.status().code()).isEqualTo(204);
            assertThat(response.status().reasonPhrase()).isEmpty();
            return null;
        });

        // Valid status without reason phrase succeeds (with trailing space)
        byte[] trailingSpaceNoReason = "HTTP/1.1 204 \r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(trailingSpaceNoReason, (buffer, total) -> {
            HttpResponse response = CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false);
            assertThat(response.status().code()).isEqualTo(204);
            assertThat(response.status().reasonPhrase()).isEmpty();
            return null;
        });

        // Reason phrase with multiple spaces is trimmed properly
        byte[] multiSpaceReason = "HTTP/1.1 200   Custom OK   \r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(multiSpaceReason, (buffer, total) -> {
            HttpResponse response = CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false);
            assertThat(response.status().code()).isEqualTo(200);
            assertThat(response.status().reasonPhrase()).isEqualTo("Custom OK");
            return null;
        });

        // RFC 9112 §3: HTAB (\t) is strictly forbidden as whitespace in start-line
        byte[] tabReason = "HTTP/1.1 200\tAccepted Tab\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(tabReason, (buffer, total) -> {
            assertThatThrownBy(() -> CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false))
                    .isInstanceOf(ExerisKernelException.class)
                    .satisfies(ex -> assertThat(((ExerisKernelException) ex).errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004));
            return null;
        });

        byte[] tabAfterVersion = "HTTP/1.1\t200 OK\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(tabAfterVersion, (buffer, total) -> {
            assertThatThrownBy(() -> CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false))
                    .isInstanceOf(ExerisKernelException.class)
                    .satisfies(ex -> assertThat(((ExerisKernelException) ex).errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004));
            return null;
        });
    }

    @Test
    void decodeResponseResolvesProtocolVersionsWithoutAllocation() {
        byte[] http10 = "HTTP/1.0 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(http10, (buffer, total) -> {
            HttpResponse response = CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false);
            assertThat(response.version()).isEqualTo(HttpVersion.HTTP_1_0);
            return null;
        });

        byte[] http11 = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(http11, (buffer, total) -> {
            HttpResponse response = CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false);
            assertThat(response.version()).isEqualTo(HttpVersion.HTTP_1_1);
            return null;
        });

        // HTTP/2 on HTTP/1.x wire must be rejected fail-fast per RFC 9113 & docs/subsystems/http.md
        byte[] http2 = "HTTP/2 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(http2, (buffer, total) -> {
            assertThatThrownBy(() -> CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false))
                    .isInstanceOf(ExerisKernelException.class)
                    .satisfies(ex -> assertThat(((ExerisKernelException) ex).errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004));
            return null;
        });

        byte[] http20 = "HTTP/2.0 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(http20, (buffer, total) -> {
            assertThatThrownBy(() -> CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false))
                    .isInstanceOf(ExerisKernelException.class)
                    .satisfies(ex -> assertThat(((ExerisKernelException) ex).errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004));
            return null;
        });

        // Unknown protocol versions must be rejected fail-fast per RFC 9112 §3.1.1
        byte[] unknownVersion = "UNKNOWN 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(unknownVersion, (buffer, total) -> {
            assertThatThrownBy(() -> CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false))
                    .isInstanceOf(ExerisKernelException.class)
                    .satisfies(ex -> assertThat(((ExerisKernelException) ex).errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004));
            return null;
        });

        byte[] invalidPrefix = "INVALID/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(invalidPrefix, (buffer, total) -> {
            assertThatThrownBy(() -> CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false))
                    .isInstanceOf(ExerisKernelException.class)
                    .satisfies(ex -> assertThat(((ExerisKernelException) ex).errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004));
            return null;
        });

        byte[] http3 = "HTTP/3.0 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(http3, (buffer, total) -> {
            assertThatThrownBy(() -> CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false))
                    .isInstanceOf(ExerisKernelException.class)
                    .satisfies(ex -> assertThat(((ExerisKernelException) ex).errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004));
            return null;
        });
    }

    @Test
    void decodeResponseReusesCanonicalHttpStatusInstances() {
        byte[] ok = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(ok, (buffer, total) -> {
            HttpResponse response = CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false);
            assertThat(response.status()).isSameAs(HttpStatus.OK);
            return null;
        });

        byte[] partialContent = "HTTP/1.1 206 Partial Content\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(partialContent, (buffer, total) -> {
            HttpResponse response = CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false);
            assertThat(response.status()).isSameAs(HttpStatus.PARTIAL_CONTENT);
            return null;
        });

        byte[] tempRedirect = "HTTP/1.1 307 Temporary Redirect\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(tempRedirect, (buffer, total) -> {
            HttpResponse response = CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false);
            assertThat(response.status()).isSameAs(HttpStatus.TEMPORARY_REDIRECT);
            return null;
        });

        byte[] permRedirect = "HTTP/1.1 308 Permanent Redirect\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(permRedirect, (buffer, total) -> {
            HttpResponse response = CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false);
            assertThat(response.status()).isSameAs(HttpStatus.PERMANENT_REDIRECT);
            return null;
        });

        byte[] methodNotAllowed = "HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(methodNotAllowed, (buffer, total) -> {
            HttpResponse response = CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false);
            assertThat(response.status()).isSameAs(HttpStatus.METHOD_NOT_ALLOWED);
            return null;
        });

        byte[] reqTimeout = "HTTP/1.1 408 Request Timeout\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(reqTimeout, (buffer, total) -> {
            HttpResponse response = CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false);
            assertThat(response.status()).isSameAs(HttpStatus.REQUEST_TIMEOUT);
            return null;
        });

        byte[] conflict = "HTTP/1.1 409 Conflict\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(conflict, (buffer, total) -> {
            HttpResponse response = CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false);
            assertThat(response.status()).isSameAs(HttpStatus.CONFLICT);
            return null;
        });

        byte[] gone = "HTTP/1.1 410 Gone\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(gone, (buffer, total) -> {
            HttpResponse response = CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false);
            assertThat(response.status()).isSameAs(HttpStatus.GONE);
            return null;
        });

        byte[] contentTooLarge = "HTTP/1.1 413 Content Too Large\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(contentTooLarge, (buffer, total) -> {
            HttpResponse response = CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false);
            assertThat(response.status()).isSameAs(HttpStatus.CONTENT_TOO_LARGE);
            return null;
        });

        byte[] uriTooLong = "HTTP/1.1 414 URI Too Long\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(uriTooLong, (buffer, total) -> {
            HttpResponse response = CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false);
            assertThat(response.status()).isSameAs(HttpStatus.URI_TOO_LONG);
            return null;
        });

        byte[] tooManyReqs = "HTTP/1.1 429 Too Many Requests\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(tooManyReqs, (buffer, total) -> {
            HttpResponse response = CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false);
            assertThat(response.status()).isSameAs(HttpStatus.TOO_MANY_REQUESTS);
            return null;
        });

        byte[] headersTooLarge = "HTTP/1.1 431 Request Header Fields Too Large\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(headersTooLarge, (buffer, total) -> {
            HttpResponse response = CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false);
            assertThat(response.status()).isSameAs(HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE);
            return null;
        });

        byte[] notImplemented = "HTTP/1.1 501 Not Implemented\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(notImplemented, (buffer, total) -> {
            HttpResponse response = CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false);
            assertThat(response.status()).isSameAs(HttpStatus.NOT_IMPLEMENTED);
            return null;
        });

        byte[] versionNotSupported = "HTTP/1.1 505 HTTP Version Not Supported\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(versionNotSupported, (buffer, total) -> {
            HttpResponse response = CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false);
            assertThat(response.status()).isSameAs(HttpStatus.HTTP_VERSION_NOT_SUPPORTED);
            return null;
        });

        byte[] noContent = "HTTP/1.1 204 No Content\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(noContent, (buffer, total) -> {
            HttpResponse response = CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false);
            assertThat(response.status()).isSameAs(HttpStatus.NO_CONTENT);
            return null;
        });

        byte[] notModified = "HTTP/1.1 304 Not Modified\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(notModified, (buffer, total) -> {
            HttpResponse response = CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false);
            assertThat(response.status()).isSameAs(HttpStatus.NOT_MODIFIED);
            return null;
        });

        // Non-standard reason phrase falls back to custom HttpStatus without throwing
        byte[] customOk = "HTTP/1.1 200 Custom Text\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(customOk, (buffer, total) -> {
            HttpResponse response = CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false);
            assertThat(response.status().code()).isEqualTo(200);
            assertThat(response.status().reasonPhrase()).isEqualTo("Custom Text");
            assertThat(response.status()).isNotSameAs(HttpStatus.OK);
            return null;
        });
    }

    @Test
    void decodeResponseRejectsBareCrAfterStatusCode() {
        byte[] bareCrWithReason = "HTTP/1.1 200\rOK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(bareCrWithReason, (buffer, total) -> {
            assertThatThrownBy(() -> CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false))
                    .isInstanceOf(ExerisKernelException.class)
                    .satisfies(ex -> assertThat(((ExerisKernelException) ex).errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004));
            return null;
        });

        byte[] bareCrNoReason = "HTTP/1.1 200\r\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(bareCrNoReason, (buffer, total) -> {
            assertThatThrownBy(() -> CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false))
                    .isInstanceOf(ExerisKernelException.class)
                    .satisfies(ex -> assertThat(((ExerisKernelException) ex).errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004));
            return null;
        });
    }

    @Test
    void decodeResponseRejectsConflictingTransferEncodingAndContentLength() {
        byte[] conflict = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Length: 5\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(conflict, (buffer, total) -> {
            assertThatThrownBy(() -> CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false))
                    .isInstanceOf(ExerisKernelException.class)
                    .satisfies(ex -> assertThat(((ExerisKernelException) ex).errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004));
            return null;
        });
    }

    @Test
    void isKeepAliveRejectsHttp10RequestWithoutKeepAliveToken() {
        TransportConnection connection = new TransportConnection() {
            @Override
            public TransportStream openStream() {
                return null;
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
                return true;
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
            }
        };

        HttpResponse ok11 = new HttpResponse(
                HttpStatus.OK, HttpVersion.HTTP_1_1,
                List.of(new HttpHeader("Content-Length", "0")), null);

        // HTTP/1.0 request without keep-alive must NOT be pooled even if response is HTTP/1.1
        HttpRequest plainHttp10Request = HttpRequest.noBody(
                HttpMethod.GET, "/test", HttpVersion.HTTP_1_0, List.of());
        assertThat(CommunityHttpClientResponseDecoder.isKeepAlive(plainHttp10Request, ok11, connection)).isFalse();

        // HTTP/1.0 request WITH keep-alive is eligible
        HttpRequest keepAliveHttp10Request = HttpRequest.noBody(
                HttpMethod.GET, "/test", HttpVersion.HTTP_1_0,
                List.of(new HttpHeader("Connection", "keep-alive")));
        assertThat(CommunityHttpClientResponseDecoder.isKeepAlive(keepAliveHttp10Request, ok11, connection)).isTrue();
    }

    @Test
    void decodeResponseHandlesEmptyHeadersWithValidSliceBounds() {
        byte[] emptyHeaders = "HTTP/1.1 204 No Content\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(emptyHeaders, (buffer, total) -> {
            HttpResponse response = CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false);
            assertThat(response.status().code()).isEqualTo(204);
            assertThat(response.status()).isSameAs(HttpStatus.NO_CONTENT);
            assertThat(response.headers()).isEmpty();
            assertThat(response.body()).isNull();
            return null;
        });
    }

    @Test
    void decodeResponseRejectsNegativeOrMalformedContentLength() {
        byte[] negative = "HTTP/1.1 200 OK\r\nContent-Length: -1\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(negative, (buffer, total) -> {
            assertThatThrownBy(() -> CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false))
                    .isInstanceOf(ExerisKernelException.class)
                    .satisfies(ex -> assertThat(((ExerisKernelException) ex).errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004));
            return null;
        });

        byte[] nonDigit = "HTTP/1.1 200 OK\r\nContent-Length: 5abc\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(nonDigit, (buffer, total) -> {
            assertThatThrownBy(() -> CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false))
                    .isInstanceOf(ExerisKernelException.class)
                    .satisfies(ex -> assertThat(((ExerisKernelException) ex).errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004));
            return null;
        });
    }

    @Test
    void headerOperationsSafeWithExactSlicedSegment() {
        byte[] raw = "Content-Length: 12\r\nTransfer-Encoding: chunked\r\nUpgrade: websocket\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        MemorySegment segment = MemorySegment.ofArray(raw);
        // Exact slice ending at the terminator without trailing bytes
        long endExclusive = raw.length - 2;
        MemorySegment exactSlice = segment.asSlice(0, endExclusive);

        CommunityHttpHeaderBlock.FramingInfo framing =
                CommunityHttpHeaderBlock.scanFraming(exactSlice, 0, endExclusive);
        assertThat(framing.contentLength()).isEqualTo(12L);
        assertThat(framing.hasTransferEncoding()).isTrue();

        List<HttpHeader> headers = CommunityHttpHeaderBlock.parse(exactSlice, 0, endExclusive);
        assertThat(headers).hasSize(3);
    }

    @Test
    void headerMatchingIsCaseInsensitiveAscii() {
        byte[] raw = "cOnTeNt-LeNgTh: 42\r\nTrAnSfEr-EnCoDiNg: chunked\r\nuPgRaDe: websocket\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        MemorySegment segment = MemorySegment.ofArray(raw);
        long endExclusive = raw.length - 2;

        CommunityHttpHeaderBlock.FramingInfo framing =
                CommunityHttpHeaderBlock.scanFraming(segment, 0, endExclusive);
        assertThat(framing.contentLength()).isEqualTo(42L);
        assertThat(framing.hasTransferEncoding()).isTrue();
    }

    @Test
    void resolveExpectedTotalWithoutHeadersDoesNotViolateRange() {
        byte[] raw = "HTTP/1.1 200 OK\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(raw, (buffer, total) -> {
            HttpResponse response = CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false);
            assertThat(response.status().code()).isEqualTo(200);
            assertThat(response.headers()).isEmpty();
            assertThat(response.body()).isNull();
            return null;
        });
    }

    @Test
    void rejectsControlCharactersInsideHeaderName() {
        byte[] raw = "HTTP/1.1 200 OK\r\nX-Bad Name: val\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(raw, (buffer, total) -> {
            assertThatThrownBy(() -> CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false))
                    .isInstanceOf(ExerisKernelException.class)
                    .satisfies(ex -> assertThat(((ExerisKernelException) ex).errorCode())
                            .isEqualTo(KernelErrorCodes.EX_HTTP_4004));
            return null;
        });
    }

    @Test
    void unconsumedTrailingBytesThrowsFailClosed() {
        // Body declares 5 bytes, but 10 bytes are present in buffer
        byte[] trailingContentLength = ("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhelloworld")
                .getBytes(StandardCharsets.US_ASCII);
        withWire(trailingContentLength, (buffer, total) -> {
            assertThatThrownBy(() -> CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false))
                    .isInstanceOf(ExerisKernelException.class)
                    .satisfies(ex -> assertThat(((ExerisKernelException) ex).errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004));
            return null;
        });

        // 204 No Content response with unexpected body bytes
        byte[] trailingNoContent = ("HTTP/1.1 204 No Content\r\n\r\nextra")
                .getBytes(StandardCharsets.US_ASCII);
        withWire(trailingNoContent, (buffer, total) -> {
            assertThatThrownBy(() -> CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false))
                    .isInstanceOf(ExerisKernelException.class)
                    .satisfies(ex -> assertThat(((ExerisKernelException) ex).errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004));
            return null;
        });
    }

    @Test
    void canonicalHttpStatusMatchesCaseInsensitive() {
        byte[] wire = "HTTP/1.1 200 ok\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(wire, (buffer, total) -> {
            HttpResponse response = CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false);
            assertThat(response.status()).isSameAs(HttpStatus.OK);
            return null;
        });

        byte[] wireNotFound = "HTTP/1.1 404 not found\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(wireNotFound, (buffer, total) -> {
            HttpResponse response = CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false);
            assertThat(response.status()).isSameAs(HttpStatus.NOT_FOUND);
            return null;
        });
    }

    @Test
    void parseContentLengthThrowsWithExactRawArgsOnInvalidChar() {
        byte[] wire = "HTTP/1.1 200 OK\r\nContent-Length: 12a4\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(wire, (buffer, total) -> {
            assertThatThrownBy(() -> CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false))
                    .isInstanceOf(ExerisKernelException.class)
                    .satisfies(ex -> {
                        ExerisKernelException ke = (ExerisKernelException) ex;
                        assertThat(ke.errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004);
                        assertThat(ke.faultOrigin()).isEqualTo(FaultOrigin.SYSTEM);
                        assertThat(ke.rawArgs()).containsExactly((long) 'a');
                    });
            return null;
        });
    }

    @Test
    void parseContentLengthThrowsWithExactRawArgsOnOverflow() {
        // 9223372036854775808 (Long.MAX_VALUE + 1)
        byte[] wire = "HTTP/1.1 200 OK\r\nContent-Length: 9223372036854775808\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(wire, (buffer, total) -> {
            assertThatThrownBy(() -> CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false))
                    .isInstanceOf(ExerisKernelException.class)
                    .satisfies(ex -> {
                        ExerisKernelException ke = (ExerisKernelException) ex;
                        assertThat(ke.errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004);
                        assertThat(ke.faultOrigin()).isEqualTo(FaultOrigin.SYSTEM);
                        assertThat(ke.rawArgs()).isNotEmpty();
                    });
            return null;
        });
    }

    @Test
    void clientResponseDecoderViolationsReportSystemFaultOrigin() {
        // ADR-083: client decoder receives responses from remote upstream dependencies.
        // Framing violations must be classified as FaultOrigin.SYSTEM, not CALLER.
        List<String> malformedResponses = List.of(
                "GARBAGE_NO_STATUS_LINE\r\n\r\n",
                "HTTP/1.1\r\n\r\n",
                "HTTP/1.1 999 InvalidStatus\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Length: -5\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Length: 5\r\nContent-Length: 10\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nshort",
                "HTTP/1.1 200 OK\r\nMalformedHeaderWithoutColon\r\n\r\n",
                "HTTP/1.1 200 OK\r\n: missingHeaderName\r\n\r\n",
                "HTTP/1.1 200 OK\r\n   : whitespaceOnlyBeforeColon\r\n\r\n"
        );

        for (String malformed : malformedResponses) {
            byte[] wire = malformed.getBytes(StandardCharsets.US_ASCII);
            withWire(wire, (buffer, total) -> {
                assertThatThrownBy(() -> CommunityHttpClientResponseDecoder.decodeResponse(
                        allocator, buffer, total, false))
                        .isInstanceOf(ExerisKernelException.class)
                        .satisfies(ex -> {
                            ExerisKernelException ke = (ExerisKernelException) ex;
                            assertThat(ke.errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004);
                            assertThat(ke.faultOrigin())
                                    .as("Response parse violation for %s must be SYSTEM fault", malformed)
                                    .isEqualTo(FaultOrigin.SYSTEM);
                        });
                return null;
            });
        }
    }

    @Test
    void malformedHeaderLineFailsFastWithRawArgs() {
        String wireNoColon = "HTTP/1.1 200 OK\r\nInvalidHeaderLine\r\n\r\n";
        byte[] bytesNoColon = wireNoColon.getBytes(StandardCharsets.US_ASCII);
        withWire(bytesNoColon, (buffer, total) -> {
            assertThatThrownBy(() -> CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false))
                    .isInstanceOf(ExerisKernelException.class)
                    .satisfies(ex -> {
                        ExerisKernelException ke = (ExerisKernelException) ex;
                        assertThat(ke.errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004);
                        assertThat(ke.faultOrigin()).isEqualTo(FaultOrigin.SYSTEM);
                        assertThat(ke.rawArgs()).containsExactly((long) "InvalidHeaderLine".length());
                    });
            return null;
        });

        withWire(bytesNoColon, (buffer, total) -> {
            long headerStart = CommunityHttpBufferOps.findCrLf(buffer.segment(), 0, total) + 2;
            long headerEnd = CommunityHttpBufferOps.findHeaderTerminator(buffer.segment(), headerStart - 2, total);
            assertThatThrownBy(() -> CommunityHttpHeaderBlock.scanFraming(
                    buffer.segment(), headerStart, headerEnd))
                    .isInstanceOf(ExerisKernelException.class)
                    .satisfies(ex -> {
                        ExerisKernelException ke = (ExerisKernelException) ex;
                        assertThat(ke.errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004);
                        assertThat(ke.faultOrigin()).isEqualTo(FaultOrigin.SYSTEM);
                        assertThat(ke.rawArgs()).containsExactly((long) "InvalidHeaderLine".length());
                    });
            return null;
        });
    }

    @Test
    void transferEncodingWithoutContentLengthFailsFastInExpectedTotal() {
        String wire = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n";
        assertThatThrownBy(() -> expectedTotalOf(wire))
                .isInstanceOf(ExerisKernelException.class)
                .satisfies(ex -> {
                    ExerisKernelException ke = (ExerisKernelException) ex;
                    assertThat(ke.errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004);
                    assertThat(ke.faultOrigin()).isEqualTo(FaultOrigin.SYSTEM);
                    assertThat(ke.getMessage()).contains("Transfer-Encoding is not supported");
                });
    }

    @Test
    void transferEncodingWithoutContentLengthFailsFastInDecodeResponse() {
        byte[] wire = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        withWire(wire, (buffer, total) -> {
            assertThatThrownBy(() -> CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, false))
                    .isInstanceOf(ExerisKernelException.class)
                    .satisfies(ex -> {
                        ExerisKernelException ke = (ExerisKernelException) ex;
                        assertThat(ke.errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004);
                        assertThat(ke.faultOrigin()).isEqualTo(FaultOrigin.SYSTEM);
                        assertThat(ke.getMessage()).contains("Transfer-Encoding is not supported");
                    });
            return null;
        });
    }

    @Test
    void singlePassParsedHeadersMatchesDirectBlockOperations() {
        String headerContent = "Host: example.com\r\nContent-Length: 42\r\nTransfer-Encoding: gzip\r\nX-Custom: value\r\n\r\n";
        byte[] wire = ("HTTP/1.1 200 OK\r\n" + headerContent).getBytes(StandardCharsets.US_ASCII);
        withWire(wire, (buffer, total) -> {
            long headerStart = CommunityHttpBufferOps.findCrLf(buffer.segment(), 0, total) + 2;
            long headerEnd = CommunityHttpBufferOps.findHeaderTerminator(buffer.segment(), headerStart - 2, total);
            CommunityHttpHeaderBlock.ParsedHeaders parsed = CommunityHttpHeaderBlock.parseHeaders(
                    buffer.segment(), headerStart, headerEnd);

            assertThat(parsed.headers()).hasSize(4);
            assertThat(parsed.contentLength()).isEqualTo(42L);
            assertThat(parsed.hasTransferEncoding()).isTrue();

            List<HttpHeader> legacyParsed = CommunityHttpHeaderBlock.parse(buffer.segment(), headerStart, headerEnd);
            assertThat(parsed.headers()).isEqualTo(legacyParsed);
            return null;
        });
    }

    @Test
    void scanFramingSinglePassMatchesIndependentScans() {
        String wireWithBoth = "HTTP/1.1 200 OK\r\nHost: example.com\r\nContent-Length: 100\r\nTransfer-Encoding: chunked\r\n\r\n";
        byte[] bytesWithBoth = wireWithBoth.getBytes(StandardCharsets.US_ASCII);
        withWire(bytesWithBoth, (buffer, total) -> {
            long headerStart = CommunityHttpBufferOps.findCrLf(buffer.segment(), 0, total) + 2;
            long headerEnd = CommunityHttpBufferOps.findHeaderTerminator(buffer.segment(), headerStart - 2, total);
            CommunityHttpHeaderBlock.FramingInfo framing = CommunityHttpHeaderBlock.scanFraming(
                    buffer.segment(), headerStart, headerEnd);

            assertThat(framing.contentLength()).isEqualTo(100L);
            assertThat(framing.hasTransferEncoding()).isTrue();
            return null;
        });

        String wireClOnly = "HTTP/1.1 200 OK\r\nContent-Length: 250\r\nX-Foo: bar\r\n\r\n";
        byte[] bytesClOnly = wireClOnly.getBytes(StandardCharsets.US_ASCII);
        withWire(bytesClOnly, (buffer, total) -> {
            long headerStart = CommunityHttpBufferOps.findCrLf(buffer.segment(), 0, total) + 2;
            long headerEnd = CommunityHttpBufferOps.findHeaderTerminator(buffer.segment(), headerStart - 2, total);
            CommunityHttpHeaderBlock.FramingInfo framing = CommunityHttpHeaderBlock.scanFraming(
                    buffer.segment(), headerStart, headerEnd);

            assertThat(framing.contentLength()).isEqualTo(250L);
            assertThat(framing.hasTransferEncoding()).isFalse();
            return null;
        });

        String wireNeither = "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n";
        byte[] bytesNeither = wireNeither.getBytes(StandardCharsets.US_ASCII);
        withWire(bytesNeither, (buffer, total) -> {
            long headerStart = CommunityHttpBufferOps.findCrLf(buffer.segment(), 0, total) + 2;
            long headerEnd = CommunityHttpBufferOps.findHeaderTerminator(buffer.segment(), headerStart - 2, total);
            CommunityHttpHeaderBlock.FramingInfo framing = CommunityHttpHeaderBlock.scanFraming(
                    buffer.segment(), headerStart, headerEnd);

            assertThat(framing.contentLength()).isEqualTo(-1L);
            assertThat(framing.hasTransferEncoding()).isFalse();
            return null;
        });
    }
}
