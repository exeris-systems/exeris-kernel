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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Http1CodecTest {

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
}