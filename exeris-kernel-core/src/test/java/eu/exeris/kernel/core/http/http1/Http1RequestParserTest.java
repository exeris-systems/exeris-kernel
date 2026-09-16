/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.http1;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Http1RequestParserTest {

    @Test
    void parsesRequestLine() {
        try (Arena arena = Arena.ofConfined()) {
            String requestLine = "GET /health HTTP/1.1\r\n";
            MemorySegment segment = arena.allocateFrom(requestLine, StandardCharsets.US_ASCII);

            Http1RequestParser.RequestLine parsed = Http1RequestParser.parseRequestLine(
                    segment, 0, requestLine.length());

            assertThat(parsed).isNotNull();
            assertThat(parsed.method()).isEqualTo("GET");
            assertThat(parsed.target()).isEqualTo("/health");
            assertThat(parsed.version()).isEqualTo("HTTP/1.1");
        }
    }

    @Test
    void parsesHeadersAndReturnsTerminalOffset() {
        try (Arena arena = Arena.ofConfined()) {
            String headers = "Host: example.com\r\nX-Trace: abc\r\n\r\n";
            MemorySegment segment = arena.allocateFrom(headers, StandardCharsets.US_ASCII);
            List<String> parsed = new ArrayList<>();

            long consumed = Http1RequestParser.parseHeaders(segment, 0, headers.length(),
                    (name, value) -> parsed.add(name + "=" + value));

            assertThat(consumed).isEqualTo(headers.length());
            assertThat(parsed).containsExactly("Host=example.com", "X-Trace=abc");
        }
    }

    @Test
    void throwsWhenHeaderCountLimitExceeded() {
        try (Arena arena = Arena.ofConfined()) {
            String headers = "A: 1\r\nB: 2\r\n\r\n";
            MemorySegment segment = arena.allocateFrom(headers, StandardCharsets.US_ASCII);

            assertThatThrownBy(() -> Http1RequestParser.parseHeaders(
                    segment,
                    0,
                    headers.length(),
                    1,
                    Http1RequestParser.DEFAULT_MAX_HEADER_SIZE,
                    (name, value) -> {
                    }
            )).isInstanceOf(Http1RequestParser.Http1ParseException.class)
                    .hasMessageContaining("too many header fields");
        }
    }

    @Test
    void trimsOnlyHttpOwsInHeaderValue() {
        try (Arena arena = Arena.ofConfined()) {
            String headers = "X-Test:\t value with spaces \t\r\n\r\n";
            MemorySegment segment = arena.allocateFrom(headers, StandardCharsets.US_ASCII);
            List<String> parsed = new ArrayList<>();

            long consumed = Http1RequestParser.parseHeaders(segment, 0, headers.length(),
                    (name, value) -> parsed.add(name + "=" + value));

            assertThat(consumed).isEqualTo(headers.length());
            assertThat(parsed).containsExactly("X-Test=value with spaces");
        }
    }

    @Test
    void rejectsHeaderNameOutsideRfcToken() {
        try (Arena arena = Arena.ofConfined()) {
            String headers = "Bad Name: value\r\n\r\n";
            MemorySegment segment = arena.allocateFrom(headers, StandardCharsets.US_ASCII);

            assertThatThrownBy(() -> Http1RequestParser.parseHeaders(segment, 0, headers.length(),
                    (name, value) -> {
                    }))
                    .isInstanceOf(Http1RequestParser.Http1ParseException.class)
                    .hasMessageContaining("Invalid HTTP header field-name");
        }
    }


    @Test
    void knownNamesArriveWithTheCharactersTheWireCarried() {
        // The parser resolves known spellings from a shared table instead of materialising them
        // (RFC-2026-09-01). The visible contract must not move: same characters, same order, and
        // mixed casings the table does not know must still come through as sent.
        try (Arena arena = Arena.ofConfined()) {
            String headers = "Host: example.test\r\n"
                    + "content-length: 7\r\n"
                    + "X-Wholly-Invented: yes\r\n"
                    + "HOST: shouted\r\n"
                    + "\r\n";
            MemorySegment segment = arena.allocateFrom(headers, StandardCharsets.US_ASCII);
            List<String> parsed = new ArrayList<>();

            long consumed = Http1RequestParser.parseHeaders(segment, 0, headers.length(),
                    (name, value) -> parsed.add(name + '=' + value));

            assertThat(consumed).isEqualTo(headers.length());
            assertThat(parsed).containsExactly(
                    "Host=example.test",
                    "content-length=7",
                    "X-Wholly-Invented=yes",
                    "HOST=shouted");
        }
    }

    @Test
    void anInvalidNameIsStillRejectedWhenItLooksLikeATableEntry() {
        // Validation now runs only on the table-miss path, so the case that matters is a name the
        // table could plausibly be asked about: five bytes, exactly the length of "Range", but not
        // a token. If the lookup ever answered on length alone this would be admitted silently.
        try (Arena arena = Arena.ofConfined()) {
            String headers = "Ra ge: value\r\n\r\n";
            MemorySegment segment = arena.allocateFrom(headers, StandardCharsets.US_ASCII);

            assertThatThrownBy(() -> Http1RequestParser.parseHeaders(segment, 0, headers.length(),
                    (name, value) -> {
                    }))
                    .isInstanceOf(Http1RequestParser.Http1ParseException.class)
                    .hasMessageContaining("Invalid HTTP header field-name");
        }
    }

    @Test
    void anEmptyNameIsStillRejected() {
        try (Arena arena = Arena.ofConfined()) {
            String headers = ": value\r\n\r\n";
            MemorySegment segment = arena.allocateFrom(headers, StandardCharsets.US_ASCII);

            assertThatThrownBy(() -> Http1RequestParser.parseHeaders(segment, 0, headers.length(),
                    (name, value) -> {
                    }))
                    .isInstanceOf(Http1RequestParser.Http1ParseException.class)
                    .hasMessageContaining("Invalid HTTP header field-name");
        }
    }
}
