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
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

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

    private static String readAscii(MemorySegment seg, long end) {
        byte[] bytes = new byte[(int) end];
        MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, 0, bytes, 0, (int) end);
        return new String(bytes, StandardCharsets.US_ASCII);
    }
}
