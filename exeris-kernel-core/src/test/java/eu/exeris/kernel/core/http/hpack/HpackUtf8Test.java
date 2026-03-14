/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.http.hpack;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HpackUtf8Test {

    @Test
    void byteLengthMatchesJdkUtf8Length() {
        String value = "ASCII-Łódź-🙂";
        byte[] expected = value.getBytes(StandardCharsets.UTF_8);

        assertThat(HpackUtf8.byteLength(value)).isEqualTo(expected.length);
    }

    @Test
    void writeToSegmentMatchesJdkUtf8Bytes() {
        String value = "abc-€-🚀";
        byte[] expected = value.getBytes(StandardCharsets.UTF_8);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(expected.length);
            HpackUtf8.writeToSegment(value, seg, 0);

            int index = 0;
            for (byte expectedByte : expected) {
                assertThat(seg.get(ValueLayout.JAVA_BYTE, index)).isEqualTo(expectedByte);
                index++;
            }
        }
    }
}
