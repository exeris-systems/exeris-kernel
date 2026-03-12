/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.http.http1;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("L0: Http1RequestParser — RFC 9112 Contract")
class Http1RequestParserTest {

    // =========================================================================
    // Request-Line
    // =========================================================================

    @Nested
    @DisplayName("parseRequestLine()")
    class ParseRequestLine {

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("requestLineSource")
        @DisplayName("Parses request-line or returns null for invalid input")
        void parseRequestLine(String raw, String expectedMethod, String expectedTarget, String expectedVersion) {
            Http1RequestParser.RequestLine rl = parseLine(raw);
            if (expectedMethod == null) {
                assertThat(rl).isNull();
            } else {
                assertThat(rl).isNotNull();
                assertThat(rl.method()).isEqualTo(expectedMethod);
                assertThat(rl.target()).isEqualTo(expectedTarget);
                assertThat(rl.version()).isEqualTo(expectedVersion);
            }
        }

        static Stream<Arguments> requestLineSource() {
            return Stream.of(
                    Arguments.of("POST /submit HTTP/1.1\r\n",        "POST",   "/submit",      "HTTP/1.1"),
                    Arguments.of("DELETE /resource/1 HTTP/1.0\r\n",  "DELETE", "/resource/1",  "HTTP/1.0"),
                    Arguments.of("GET /",                             null,     null,            null),
                    Arguments.of("GETONLY\r\n",                       null,     null,            null)
            );
        }

        @Test
        @DisplayName("Parses absolute-form target (proxy request)")
        void absoluteFormTarget() {
            Http1RequestParser.RequestLine rl =
                    parseLine("GET http://example.com/path HTTP/1.1\r\n");
            assertThat(rl).isNotNull();
            assertThat(rl.target()).isEqualTo("http://example.com/path");
        }

        private Http1RequestParser.RequestLine parseLine(String raw) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = toSegment(arena, raw);
                return Http1RequestParser.parseRequestLine(seg, 0, seg.byteSize());
            }
        }
    }

    // =========================================================================
    // Header Parsing
    // =========================================================================

    @Nested
    @DisplayName("parseHeaders()")
    class ParseHeaders {

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("singleHeaderSource")
        @DisplayName("Single header: name and value parsed correctly")
        void singleHeaderParsing(String raw, String expectedName, String expectedValue) {
            List<String[]> headers = parseHeaders(raw);
            assertThat(headers).hasSize(1);
            assertThat(headers.getFirst()).containsExactly(expectedName, expectedValue);
        }

        static Stream<Arguments> singleHeaderSource() {
            return Stream.of(
                    Arguments.of("X-Custom: value\r\n\r\n",                         "X-Custom",      "value"),
                    Arguments.of("X-Padded:   padded value   \r\n\r\n",             "X-Padded",      "padded value"),
                    Arguments.of("Authorization: Bearer token:with:colons\r\n\r\n", "Authorization", "Bearer token:with:colons"),
                    Arguments.of("Date: Mon, 21 Oct 2013 20:13:21 GMT\r\n\r\n",     "Date",          "Mon, 21 Oct 2013 20:13:21 GMT")
            );
        }

        @Test
        @DisplayName("Multiple headers in order")
        void multipleHeaders() {
            String raw = "Accept: text/html\r\nContent-Length: 42\r\nX-Trace: abc\r\n\r\n";
            List<String[]> headers = parseHeaders(raw);
            assertThat(headers).hasSize(3);
            assertThat(headers.getFirst()).containsExactly("Accept", "text/html");
            assertThat(headers.get(1)).containsExactly("Content-Length", "42");
            assertThat(headers.get(2)).containsExactly("X-Trace", "abc");
        }

        @Test
        @DisplayName("Returns -1 when double CRLF terminator is absent")
        void returnsNegativeOneWhenNoTerminator() {
            String raw = "Host: example.com\r\n";
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = toSegment(arena, raw);
                long result = Http1RequestParser.parseHeaders(
                        seg, 0, seg.byteSize(), (_, _) -> {});
                assertThat(result).isNegative();
            }
        }

        @Test
        @DisplayName("Immediate double CRLF means empty header section")
        void emptyHeaders() {
            String raw = "\r\n";
            List<String[]> headers = parseHeaders(raw);
            assertThat(headers).isEmpty();
        }

        @Test
        @DisplayName("Line without colon throws EX_HTTP_4004 parse violation")
        void lineWithoutColonThrowsParseViolation() {
            String raw = "InvalidLine\r\nValid: yes\r\n\r\n";
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = toSegment(arena, raw);
                assertThatThrownBy(() -> Http1RequestParser.parseHeaders(
                        seg, 0, seg.byteSize(), (_, _) -> {}))
                        .isInstanceOf(Http1RequestParser.Http1ParseException.class)
                        .satisfies(ex -> assertThat(
                                ((Http1RequestParser.Http1ParseException) ex).errorCode())
                                .isEqualTo(KernelErrorCodes.EX_HTTP_4004));
            }
        }

        @Test
        @DisplayName("DoS header-count violation exposes EX_HTTP_4004")
        void tooManyHeadersExposesHttpErrorCode() {
            String raw = "A: 1\r\nB: 2\r\n\r\n";
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = toSegment(arena, raw);
                assertThatThrownBy(() -> Http1RequestParser.parseHeaders(
                        seg, 0, seg.byteSize(), 1, Http1RequestParser.DEFAULT_MAX_HEADER_SIZE, (_, _) -> {}))
                        .isInstanceOf(Http1RequestParser.Http1ParseException.class)
                        .satisfies(ex -> assertThat(((Http1RequestParser.Http1ParseException) ex).errorCode())
                                .isEqualTo(KernelErrorCodes.EX_HTTP_4004));
            }
        }

        private List<String[]> parseHeaders(String raw) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = toSegment(arena, raw);
                List<String[]> headers = new ArrayList<>();
                Http1RequestParser.parseHeaders(seg, 0, seg.byteSize(),
                        (n, v) -> headers.add(new String[]{n, v}));
                return headers;
            }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static MemorySegment toSegment(Arena arena, String str) {
        byte[] bytes = str.getBytes(StandardCharsets.US_ASCII);
        MemorySegment seg = arena.allocate(bytes.length);
        MemorySegment.copy(MemorySegment.ofArray(bytes), ValueLayout.JAVA_BYTE, 0,
                seg, ValueLayout.JAVA_BYTE, 0, bytes.length);
        return seg;
    }
}


