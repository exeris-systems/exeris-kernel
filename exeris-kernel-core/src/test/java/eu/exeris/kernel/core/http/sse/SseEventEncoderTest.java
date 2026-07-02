/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.http.sse;

import static org.assertj.core.api.Assertions.assertThat;

import eu.exeris.kernel.spi.http.StreamEvent;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SseEventEncoderTest {

    private static String encode(StreamEvent event) {
        return new String(SseEventEncoder.encode(event), StandardCharsets.UTF_8);
    }

    @Test
    void dataOnlyEventEmitsSingleDataLineAndBlankTerminator() {
        assertThat(encode(StreamEvent.of("hello"))).isEqualTo("data: hello\n\n");
    }

    @Test
    void typedEventEmitsEventThenData() {
        assertThat(encode(StreamEvent.of("tick", "1"))).isEqualTo("event: tick\ndata: 1\n\n");
    }

    @Test
    void fieldOrderIsEventThenIdThenRetryThenData() {
        assertThat(encode(new StreamEvent("tick", "payload", "42", 3000L)))
                .isEqualTo("event: tick\nid: 42\nretry: 3000\ndata: payload\n\n");
    }

    @Test
    void retryOmittedWhenNotPositive() {
        assertThat(encode(new StreamEvent(null, "x", null, 0L))).isEqualTo("data: x\n\n");
        assertThat(encode(new StreamEvent(null, "x", null, -5L))).isEqualTo("data: x\n\n");
    }

    @Test
    void blankEventAndIdFieldsAreOmitted() {
        assertThat(encode(new StreamEvent("", "x", "  ", 0L))).isEqualTo("data: x\n\n");
    }

    @Test
    void multiLineDataEmitsOneDataLinePerLine() {
        assertThat(encode(StreamEvent.of("a\nb\nc"))).isEqualTo("data: a\ndata: b\ndata: c\n\n");
    }

    @Test
    void crlfAndLoneCrAreNormalisedToDataLines() {
        assertThat(encode(StreamEvent.of("a\r\nb\rc"))).isEqualTo("data: a\ndata: b\ndata: c\n\n");
    }

    @Test
    void emptyDataEmitsEmptyDataLine() {
        assertThat(encode(StreamEvent.of(""))).isEqualTo("data: \n\n");
    }

    @Test
    void trailingNewlineProducesTrailingEmptyDataLine() {
        assertThat(encode(StreamEvent.of("a\n"))).isEqualTo("data: a\ndata: \n\n");
    }

    @Test
    void embeddedNewlinesInEventFieldAreStrippedToPreventInjection() {
        assertThat(encode(new StreamEvent("ev\nid: spoof", "x", null, 0L)))
                .isEqualTo("event: evid: spoof\ndata: x\n\n");
    }

    @Test
    void nulBytesInEventAndIdFieldsAreStripped() {
        // WHATWG HTML §9.2: a NUL in the id field makes the client ignore it — strip defensively.
        // Build the NUL via (char) 0 so no raw 0x00 byte is written into the source file.
        String nul = String.valueOf((char) 0);
        assertThat(encode(new StreamEvent("e" + nul + "v", "x", "i" + nul + "d", 0L)))
                .isEqualTo("event: ev\nid: id\ndata: x\n\n");
    }

    @Test
    void utf8MultibytePayloadRoundTrips() {
        assertThat(encode(StreamEvent.of("zażółć gęślą — €")))
                .isEqualTo("data: zażółć gęślą — €\n\n");
    }

    @Test
    void supplementaryCodePointIsEncodedAsFourUtf8Bytes() {
        // U+1F680 ROCKET (surrogate pair in UTF-16 → 4-byte UTF-8) — the zero-copy encoder must walk
        // by code point, not code unit, or it would split the pair. Bytes must match getBytes(UTF_8).
        String rocket = "🚀";
        byte[] actual = SseEventEncoder.encode(StreamEvent.of(rocket));
        assertThat(actual).isEqualTo(("data: " + rocket + "\n\n").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void unpairedSurrogateIsReplacedLikeGetBytes() {
        // A lone high surrogate is malformed UTF-8 input; the encoder must emit the same single '?'
        // replacement byte the JDK UTF-8 encoder does, so the segment path stays byte-identical.
        String lone = "a\uD83Db";
        byte[] actual = SseEventEncoder.encode(StreamEvent.of(lone));
        assertThat(actual).isEqualTo(("data: " + lone + "\n\n").getBytes(StandardCharsets.UTF_8));
        assertThat(new String(actual, StandardCharsets.UTF_8)).isEqualTo("data: a?b\n\n");
    }
}
