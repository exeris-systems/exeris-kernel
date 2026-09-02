/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
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
    void linesWithoutAUsableColonAreSkipped() {
        // Both cases the old index check covered: no colon at all, and a colon at position zero.
        List<HttpHeader> headers = headersOf(
                "HTTP/1.1 200 OK\r\n"
                + "Content-Length: 0\r\n"
                + "GarbageWithNoColon\r\n"
                + ": leading-colon\r\n"
                + "X-Kept: yes\r\n"
                + "\r\n");

        assertThat(headers).extracting(HttpHeader::name).containsExactly("Content-Length", "X-Kept");
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
    void anAbsentOrUnparseableContentLengthLeavesTheTotalUnresolved() {
        assertThat(expectedTotalOf("HTTP/1.1 200 OK\r\nServer: x\r\n\r\n")).isEqualTo(-1L);
        assertThat(expectedTotalOf("HTTP/1.1 200 OK\r\nContent-Length: nope\r\n\r\n")).isEqualTo(-1L);
        assertThat(expectedTotalOf("HTTP/1.1 200 OK\r\nContent-Length: -3\r\n\r\n")).isEqualTo(-1L);
    }

    @Test
    void theFirstContentLengthWins() {
        // A header list would have taken the first match; scanning must not take the last.
        String wire = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\nContent-Length: 99\r\n\r\nhello";
        assertThat(expectedTotalOf(wire)).isEqualTo(wire.length());
    }

    @Test
    void aResponseBodyIsDecodedToItsDeclaredLength() {
        String wire = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 5\r\n\r\nhello";
        withWire(wire.getBytes(StandardCharsets.ISO_8859_1), (buffer, total) -> {
            HttpResponse response = CommunityHttpClientResponseDecoder.decodeResponse(
                    allocator, buffer, total, HttpVersion.HTTP_1_1, false);
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
                    allocator, buffer, total, HttpVersion.HTTP_1_1, true);
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
}
