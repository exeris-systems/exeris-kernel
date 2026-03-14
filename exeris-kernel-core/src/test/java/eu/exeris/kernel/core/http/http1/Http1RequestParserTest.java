/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
}
