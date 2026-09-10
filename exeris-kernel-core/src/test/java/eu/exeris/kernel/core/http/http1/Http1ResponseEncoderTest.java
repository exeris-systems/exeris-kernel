/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.http1;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Http1ResponseEncoderTest {

    @Test
    void writesStatusLineAndHeaders() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(256);
            long pos = 0;
            pos = Http1ResponseEncoder.writeStatusLine(buf, pos, 200, "OK");
            pos = Http1ResponseEncoder.writeHeader(buf, pos, "Content-Length", "0");
            pos = Http1ResponseEncoder.writeHeaderEnd(buf, pos);

            assertThat(readAscii(buf, pos))
                    .isEqualTo("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n");
        }
    }

    @Test
    void rejectsStatusCodesOutsideRfc9110Range() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(64);

            assertThatThrownBy(() -> Http1ResponseEncoder.writeStatusLine(buf, 0, 600, "Invalid"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("[100, 599]");
        }
    }

    @Test
    void rejectsAsciiControlCharactersInFields() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(64);

            assertThatThrownBy(() -> Http1ResponseEncoder.writeStatusLine(buf, 0, 200, "OK\rInjected"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ASCII control character");

            assertThatThrownBy(() -> Http1ResponseEncoder.writeHeader(buf, 0, "X-Test", "safe\nunsafe"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ASCII control character");
        }
    }

    @Test
    void rejectsNullStatusLineAndHeaderArguments() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(64);

            assertThatThrownBy(() -> Http1ResponseEncoder.writeStatusLine(buf, 0, 200, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("reason phrase must not be null");

            assertThatThrownBy(() -> Http1ResponseEncoder.writeHeader(buf, 0, null, "value"))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("header name must not be null");

            assertThatThrownBy(() -> Http1ResponseEncoder.writeHeader(buf, 0, "X-Test", null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("header value must not be null");
        }
    }

    private static String readAscii(MemorySegment seg, long end) {
        byte[] bytes = new byte[(int) end];
        MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, 0, bytes, 0, (int) end);
        return new String(bytes, StandardCharsets.US_ASCII);
    }
}
