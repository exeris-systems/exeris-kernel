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

class Http1CodecTest {

    @Test
    void configuredHeaderCountLimitIsEnforced() {
        // The property ADR-071 exists for: a codec built with the operator's bound uses it, rather
        // than the parser default. Three headers against a bound of two -- the default of 100 would
        // accept this, so a pass here cannot be the default quietly still in charge.
        try (Arena arena = Arena.ofConfined()) {
            String headers = "A: 1\r\nB: 2\r\nC: 3\r\n\r\n";
            MemorySegment segment = arena.allocateFrom(headers, StandardCharsets.US_ASCII);
            Http1Codec codec = new Http1Codec(2, 8_192);

            assertThatThrownBy(() -> codec.parseHeaders(segment, 0, headers.length()))
                    .as("the configured header-count bound must be the one enforced")
                    .isInstanceOf(Http1RequestParser.Http1ParseException.class);
        }
    }

    @Test
    void configuredHeaderSizeLimitIsEnforced() {
        try (Arena arena = Arena.ofConfined()) {
            String headers = "X-Long: " + "v".repeat(64) + "\r\n\r\n";
            MemorySegment segment = arena.allocateFrom(headers, StandardCharsets.US_ASCII);
            Http1Codec codec = new Http1Codec(100, 16);

            assertThatThrownBy(() -> codec.parseHeaders(segment, 0, headers.length()))
                    .as("the configured single-header size bound must be the one enforced")
                    .isInstanceOf(Http1RequestParser.Http1ParseException.class);
        }
    }

    @Test
    void aRaisedLimitAdmitsWhatTheDefaultWouldRefuse() {
        // The direction an operator actually hits. Before ADR-071 the key was read, validated and
        // ignored, so raising it changed nothing and the request was still refused at 8 192 with
        // nothing saying why.
        try (Arena arena = Arena.ofConfined()) {
            String headers = "X-Big: " + "v".repeat(9_000) + "\r\n\r\n";
            MemorySegment segment = arena.allocateFrom(headers, StandardCharsets.US_ASCII);

            assertThatThrownBy(() -> new Http1Codec().parseHeaders(segment, 0, headers.length()))
                    .as("the default bound of 8 192 refuses it")
                    .isInstanceOf(Http1RequestParser.Http1ParseException.class);

            Http1Codec raised = new Http1Codec(100, 16_384);
            assertThat(raised.parseHeaders(segment, 0, headers.length()))
                    .as("a raised bound admits it")
                    .isEqualTo(headers.length());
        }
    }

    @Test
    void zeroBoundsAreRefusedAtConstruction() {
        // Zero is not "unlimited" here -- maxHeaders 0 refuses the first header and maxHeaderSize 0
        // refuses any non-empty field, so a codec built with either serves nothing but 400s. Refused
        // where the value is named rather than once per request, where it reads as a client fault.
        assertThatThrownBy(() -> new Http1Codec(0, 8_192))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxHeaders must be > 0");
        assertThatThrownBy(() -> new Http1Codec(100, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxHeaderSize must be > 0");
    }

    @Test
    void parsesPositiveContentLength() {
        try (Arena arena = Arena.ofConfined()) {
            String headers = "Content-Length: 42\r\n\r\n";
            MemorySegment segment = arena.allocateFrom(headers, StandardCharsets.US_ASCII);
            Http1Codec codec = new Http1Codec();

            long consumed = codec.parseHeaders(segment, 0, headers.length());

            assertThat(consumed).isEqualTo(headers.length());
            assertThat(codec.pendingContentLength()).isEqualTo(42L);
        }
    }

    @Test
    void rejectsNegativeContentLength() {
        try (Arena arena = Arena.ofConfined()) {
            String headers = "Content-Length: -1\r\n\r\n";
            MemorySegment segment = arena.allocateFrom(headers, StandardCharsets.US_ASCII);
            Http1Codec codec = new Http1Codec();

            assertThatThrownBy(() -> codec.parseHeaders(segment, 0, headers.length()))
                    .isInstanceOf(Http1RequestParser.Http1ParseException.class)
                    .hasMessageContaining("invalid Content-Length");
        }
    }

    @Test
    void rejectsNonNumericContentLength() {
        try (Arena arena = Arena.ofConfined()) {
            String headers = "Content-Length: nope\r\n\r\n";
            MemorySegment segment = arena.allocateFrom(headers, StandardCharsets.US_ASCII);
            Http1Codec codec = new Http1Codec();

            assertThatThrownBy(() -> codec.parseHeaders(segment, 0, headers.length()))
                    .isInstanceOf(Http1RequestParser.Http1ParseException.class)
                    .hasMessageContaining("invalid Content-Length");
        }
    }

    @Test
    void theVisitingOverloadYieldsEveryFieldOnceWhileDerivingConnectionState() {
        // The property the single pass exists for. Connection state and the caller's header list
        // come off the SAME traversal, so a list of exactly three entries is what says the block
        // was read once -- the two-pass shape this replaced would put six here if a caller had been
        // able to observe both passes.
        try (Arena arena = Arena.ofConfined()) {
            String headers = "Host: example.test\r\nContent-Length: 7\r\nConnection: close\r\n\r\n";
            MemorySegment segment = arena.allocateFrom(headers, StandardCharsets.US_ASCII);
            Http1Codec codec = new Http1Codec();
            List<String> seen = new ArrayList<>();

            long consumed = codec.parseHeaders(segment, 0, headers.length(),
                    (name, value) -> seen.add(name + '=' + value));

            assertThat(consumed).isEqualTo(headers.length());
            assertThat(seen)
                    .as("every field exactly once, in wire order")
                    .containsExactly("Host=example.test", "Content-Length=7", "Connection=close");
            assertThat(codec.pendingContentLength())
                    .as("the same pass still derives Content-Length")
                    .isEqualTo(7L);
            assertThat(codec.isKeepAlive())
                    .as("the same pass still derives keep-alive")
                    .isFalse();
        }
    }

    @Test
    void aFieldTheCodecRejectsNeverReachesTheVisitor() {
        // The ordering the overload's contract promises. The visitor runs after the field has
        // updated connection state, so a caller building a header list never records a field the
        // codec refused -- and does record the ones it accepted before the refusal.
        try (Arena arena = Arena.ofConfined()) {
            String headers = "Host: example.test\r\nContent-Length: nope\r\n\r\n";
            MemorySegment segment = arena.allocateFrom(headers, StandardCharsets.US_ASCII);
            Http1Codec codec = new Http1Codec();
            List<String> seen = new ArrayList<>();

            assertThatThrownBy(() -> codec.parseHeaders(segment, 0, headers.length(),
                    (name, value) -> seen.add(name)))
                    .isInstanceOf(Http1RequestParser.Http1ParseException.class)
                    .hasMessageContaining("invalid Content-Length");

            assertThat(seen)
                    .as("the accepted field was visited; the refused one was not")
                    .containsExactly("Host");
        }
    }

    @Test
    void anIncompleteBlockReportsItselfRatherThanLeavingTheVisitorToGuess() {
        // No terminal CRLF CRLF. The visitor has already been handed what was there, which is why
        // the contract says the return value -- not the visitor -- decides whether the block is
        // usable, and a caller that gets -1 discards.
        try (Arena arena = Arena.ofConfined()) {
            String headers = "Host: example.test\r\nAccept: */*\r\n";
            MemorySegment segment = arena.allocateFrom(headers, StandardCharsets.US_ASCII);
            Http1Codec codec = new Http1Codec();
            List<String> seen = new ArrayList<>();

            long consumed = codec.parseHeaders(segment, 0, headers.length(),
                    (name, value) -> seen.add(name));

            assertThat(consumed).as("incomplete").isEqualTo(-1L);
            assertThat(seen).as("what the visitor saw is not a usable header block").isNotEmpty();
        }
    }

    @Test
    void theVisitingOverloadEnforcesTheConfiguredBounds() {
        // The path a request actually takes now, so ADR-071's bound has to hold on it too. Three
        // headers against a bound of two: the parser default of 100 would accept this.
        try (Arena arena = Arena.ofConfined()) {
            String headers = "A: 1\r\nB: 2\r\nC: 3\r\n\r\n";
            MemorySegment segment = arena.allocateFrom(headers, StandardCharsets.US_ASCII);
            Http1Codec codec = new Http1Codec(2, 8_192);

            assertThatThrownBy(() -> codec.parseHeaders(segment, 0, headers.length(),
                    (name, value) -> { }))
                    .as("the configured header-count bound must be the one enforced")
                    .isInstanceOf(Http1RequestParser.Http1ParseException.class);
        }
    }

    @Test
    void aNullVisitorIsRefused() {
        try (Arena arena = Arena.ofConfined()) {
            String headers = "Host: example.test\r\n\r\n";
            MemorySegment segment = arena.allocateFrom(headers, StandardCharsets.US_ASCII);
            Http1Codec codec = new Http1Codec();

            assertThatThrownBy(() -> codec.parseHeaders(segment, 0, headers.length(), null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("visitor");
        }
    }
}
