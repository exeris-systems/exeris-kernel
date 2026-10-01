/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.hpack.huffman;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("L0: HuffmanTable — Static Flat Table Contract")
class HuffmanTableTest {

    @Test
    @DisplayName("symbolCount() returns 257 (0–255 + EOS)")
    void symbolCountIs257() {
        assertThat(HuffmanTable.symbolCount()).isEqualTo(257);
    }

    @Test
    @DisplayName("getEntry(0, 0) returns a valid packed int (state 0, nibble 0)")
    void getEntryStateZeroNibbleZero() {
        int entry = HuffmanTable.getEntry(0, 0);
        assertThat(entry).isNotNegative();
    }

    @Test
    @DisplayName("Huffman code length for '0' (0x30) is 5 bits per RFC 7541")
    void huffmanLengthForDigitZero() {
        assertThat(HuffmanTable.getHuffmanCodeLength(0x30)).isEqualTo(5);
    }

    @Test
    @DisplayName("Huffman code for space (0x20) has length 6")
    void huffmanLengthForSpace() {
        assertThat(HuffmanTable.getHuffmanCodeLength(0x20)).isEqualTo(6);
    }

    @Nested
    @DisplayName("Nibble decode walk — ASCII 'a' (0x61)")
    class NibbleDecodeWalk {

        @Test
        @DisplayName("Decoding nibbles for 'a' reaches an emit state")
        void decodingNibblesForA() {
            int code = HuffmanTable.getHuffmanCode(0x61);
            int len = HuffmanTable.getHuffmanCodeLength(0x61);
            assertThat(len).isEqualTo(5);

            int state = 0;
            boolean emitted = false;
            int emittedSymbol = -1;

            for (int nibbleStart = len - 1; nibbleStart >= 0; nibbleStart -= 4) {
                int nibble = 0;
                for (int bit = 0; bit < 4 && (nibbleStart - bit) >= 0; bit++) {
                    int bitIdx = nibbleStart - bit;
                    nibble |= ((code >> bitIdx) & 1) << (3 - bit);
                }
                int entry = HuffmanTable.getEntry(state, nibble);
                if ((entry & 0x8000) != 0) {
                    emitted = true;
                    emittedSymbol = entry & 0x1FF;
                    state = entry >>> 16;
                } else {
                    state = entry & 0xFFFF;
                }
            }

            assertThat(emitted).isTrue();
            assertThat(emittedSymbol).isEqualTo(0x61);
        }
    }
}
