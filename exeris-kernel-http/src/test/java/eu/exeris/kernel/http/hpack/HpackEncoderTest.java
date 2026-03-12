/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.http.hpack;

import eu.exeris.kernel.spi.memory.MemoryAllocator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("L0: HpackEncoder — RFC 7541 §6 Encoding Strategy")
class HpackEncoderTest {

    private static Arena testArena;
    private static MemoryAllocator allocator;

    @BeforeAll
    static void init() {
        testArena = Arena.ofShared(); //NOPMD DirectArena — test harness
        allocator = new TestAllocator(testArena);
    }

    @AfterAll
    static void teardown() {
        testArena.close();
    }

    // =========================================================================
    // §6.1 — Indexed Header Field Representation
    // =========================================================================

    @Nested
    @DisplayName("Indexed representation — §6.1")
    class IndexedRepresentation {

        @Test
        @DisplayName("Static full match produces single byte 0x82 for :method GET")
        void staticFullMatchProducesSingleByte() {
            HpackEncoder encoder = encoder(false);
            MemorySegment buf = testArena.allocate(8);
            long len = encoder.encodeHeader(buf, 0, ":method", "GET", false);

            assertThat(len).isEqualTo(1);
            assertThat(buf.get(ValueLayout.JAVA_BYTE, 0) & 0xFF).isEqualTo(0x82);
        }

        @Test
        @DisplayName("Static full match for :path / produces 0x84")
        void staticFullMatchPath() {
            HpackEncoder encoder = encoder(false);
            MemorySegment buf = testArena.allocate(8);
            long len = encoder.encodeHeader(buf, 0, ":path", "/", false);

            assertThat(len).isEqualTo(1);
            assertThat(buf.get(ValueLayout.JAVA_BYTE, 0) & 0xFF).isEqualTo(0x84);
        }

        @Test
        @DisplayName("Dynamic full match produces indexed representation")
        void dynamicFullMatch() {
            HpackDynamicTable table = new HpackDynamicTable(4096);
            HpackEncoder encoder = new HpackEncoder(table, allocator, false);
            MemorySegment buf = testArena.allocate(64);

            encoder.encodeHeader(buf, 0, "x-custom", "val", false);
            assertThat(table.size()).isEqualTo(1);

            long len = encoder.encodeHeader(buf, 0, "x-custom", "val", false);
            assertThat(len).isEqualTo(1);
            int firstByte = buf.get(ValueLayout.JAVA_BYTE, 0) & 0xFF;
            assertThat(firstByte & 0x80).isEqualTo(0x80);
            assertThat(firstByte & 0x7F).isEqualTo(HpackStaticTable.SIZE + 1);
        }
    }

    // =========================================================================
    // §6.2.1 — Literal with Incremental Indexing
    // =========================================================================

    @Nested
    @DisplayName("Literal with Incremental Indexing — §6.2.1")
    class LiteralIncremental {

        @Test
        @DisplayName("Static name-only match: first byte encodes 0x40 | nameIndex")
        void staticNameOnlyMatch() {
            HpackEncoder encoder = encoder(false);
            MemorySegment buf = testArena.allocate(64);
            long len = encoder.encodeHeader(buf, 0, ":authority", "example.com", false);

            int firstByte = buf.get(ValueLayout.JAVA_BYTE, 0) & 0xFF;
            assertThat(firstByte).isEqualTo(0x40 | 1);
            assertThat(len).isGreaterThan(1);
        }

        @Test
        @DisplayName("New name: first byte is exactly 0x40, followed by name string literal")
        void newName() {
            HpackEncoder encoder = encoder(false);
            MemorySegment buf = testArena.allocate(64);
            long len = encoder.encodeHeader(buf, 0, "x-new-header", "value", false);

            assertThat(buf.get(ValueLayout.JAVA_BYTE, 0) & 0xFF).isEqualTo(0x40);
            assertThat(len).isGreaterThan(1);
        }

        @Test
        @DisplayName("Entry is added to dynamic table after encoding")
        void addsEntryToDynamicTable() {
            HpackDynamicTable table = new HpackDynamicTable(4096);
            HpackEncoder encoder = new HpackEncoder(table, allocator, false);
            MemorySegment buf = testArena.allocate(64);
            encoder.encodeHeader(buf, 0, "x-trace", "abc", false);

            assertThat(table.size()).isEqualTo(1);
            assertThat(table.getName(0)).isEqualTo("x-trace");
            assertThat(table.getValue(0)).isEqualTo("abc");
        }

        @Test
        @DisplayName("Encoded length matches: 1 (prefix) + 1 (name len) + name + 1 (val len) + val")
        void encodedLengthCorrect() {
            HpackEncoder encoder = encoder(false);
            MemorySegment buf = testArena.allocate(64);
            long len = encoder.encodeHeader(buf, 0, "ab", "cd", false);

            assertThat(len).isEqualTo(1L + 1 + 2 + 1 + 2);
        }
    }

    // =========================================================================
    // §6.2.3 — Literal Never Indexed
    // =========================================================================

    @Nested
    @DisplayName("Literal Never Indexed — §6.2.3")
    class LiteralNeverIndexed {

        @Test
        @DisplayName("Sensitive=true with new name: first byte is 0x10")
        void sensitiveNewName() {
            HpackEncoder encoder = encoder(false);
            MemorySegment buf = testArena.allocate(64);
            // "x-secret" has no static table entry — new name, prefix = 0x10 | 0 = 0x10
            encoder.encodeHeader(buf, 0, "x-secret", "token", true);

            assertThat(buf.get(ValueLayout.JAVA_BYTE, 0) & 0xFF).isEqualTo(0x10);
        }

        @Test
        @DisplayName("Sensitive=true with static name at idx <= 15: first byte is 0x10 | nameIdx")
        void sensitiveStaticNameMatch() {
            HpackEncoder encoder = encoder(false);
            MemorySegment buf = testArena.allocate(64);
            // "accept-charset" is at static index 15 — fits in 4-bit prefix
            encoder.encodeHeader(buf, 0, "accept-charset", "utf-8", true);

            int firstByte = buf.get(ValueLayout.JAVA_BYTE, 0) & 0xFF;
            assertThat(firstByte & 0xF0).isEqualTo(0x10);
            assertThat(firstByte & 0x0F).isEqualTo(15);
        }

        @Test
        @DisplayName("Sensitive=true with static name at idx > 15: 0x1F + continuation byte")
        void sensitiveStaticNameMatchMultiByte() {
            HpackEncoder encoder = encoder(false);
            MemorySegment buf = testArena.allocate(64);
            // "authorization" is at static index 23 — requires multi-byte integer
            encoder.encodeHeader(buf, 0, "authorization", "Bearer tok", true);

            int firstByte = buf.get(ValueLayout.JAVA_BYTE, 0) & 0xFF;
            assertThat(firstByte).isEqualTo(0x1F);
            // continuation byte: 23 - 15 = 8, no more continuation (< 128)
            int secondByte = buf.get(ValueLayout.JAVA_BYTE, 1) & 0xFF;
            assertThat(secondByte).isEqualTo(8);
        }

        @Test
        @DisplayName("Sensitive=true does NOT add entry to dynamic table")
        void sensitiveDoesNotIndex() {
            HpackDynamicTable table = new HpackDynamicTable(4096);
            HpackEncoder encoder = new HpackEncoder(table, allocator, false);
            MemorySegment buf = testArena.allocate(64);
            encoder.encodeHeader(buf, 0, "x-secret", "token", true);

            assertThat(table.size()).isZero();
        }
    }

    // =========================================================================
    // Huffman encoding strategy
    // =========================================================================

    @Nested
    @DisplayName("Huffman encoding strategy")
    class HuffmanStrategy {

        @Test
        @DisplayName("Huffman=true: string with shorter Huffman form sets high bit on length byte")
        void huffmanUsedWhenShorter() {
            HpackEncoder encoder = encoder(true);
            MemorySegment buf = testArena.allocate(64);
            long huffLen = encoder.encodeHeader(buf, 0, "x-h", "www.example.com", false);

            HpackEncoder rawEncoder = encoder(false);
            MemorySegment rawBuf = testArena.allocate(64);
            long rawLen = rawEncoder.encodeHeader(rawBuf, 0, "x-h", "www.example.com", false);

            assertThat(huffLen).isLessThan(rawLen);
        }

        @Test
        @DisplayName("Huffman=false: length byte for string has no high bit set (0x80)")
        void rawEncodingWhenHuffmanDisabled() {
            HpackEncoder encoder = encoder(false);
            MemorySegment buf = testArena.allocate(64);
            encoder.encodeHeader(buf, 0, "x-a", "val", false);

            // First byte = 0x40 (new name literal); byte 1 = name length without 0x80
            int nameLenByte = buf.get(ValueLayout.JAVA_BYTE, 1) & 0xFF;
            assertThat(nameLenByte & 0x80).isZero();
        }

        @Test
        @DisplayName("Huffman=true: EOS symbol value ('www.example.com') matches RFC C.4 bytes")
        void huffmanWwwExampleComMatchesRfc() {
            HpackEncoder encoder = encoder(true);
            MemorySegment buf = testArena.allocate(64);
            // Encode only the value "www.example.com" via a known :authority header
            encoder.encodeHeader(buf, 0, ":authority", "www.example.com", false);

            // RFC C.4.1: :authority encoded as literal+index(1), then Huffman value
            // 0x41 | Huffman-length-byte(0x8c) | 12 Huffman bytes
            assertThat(buf.get(ValueLayout.JAVA_BYTE, 0) & 0xFF).isEqualTo(0x41);
            int valueLenByte = buf.get(ValueLayout.JAVA_BYTE, 1) & 0xFF;
            assertThat(valueLenByte & 0x80).isEqualTo(0x80);
            int huffmanLen = valueLenByte & 0x7F;
            assertThat(huffmanLen).isEqualTo(12);

            byte[] expected = {
                (byte) 0xf1, (byte) 0xe3, (byte) 0xc2, (byte) 0xe5,
                (byte) 0xf2, 0x3a, 0x6b, (byte) 0xa0,
                (byte) 0xab, (byte) 0x90, (byte) 0xf4, (byte) 0xff
            };
            byte[] actual = new byte[12];
            MemorySegment.copy(buf, ValueLayout.JAVA_BYTE, 2, actual, 0, 12);
            assertThat(actual).isEqualTo(expected);
        }
    }

    // =========================================================================
    // §6.3 — Dynamic Table Size Update
    // =========================================================================

    @Nested
    @DisplayName("Dynamic Table Size Update — §6.3")
    class SizeUpdate {

        @Test
        @DisplayName("encodeSizeUpdate writes 0x20 | newSize for values < 32")
        void sizeUpdateSmallValue() {
            HpackEncoder encoder = encoder(false);
            MemorySegment buf = testArena.allocate(4);
            long len = encoder.encodeSizeUpdate(buf, 0, 0);

            assertThat(len).isEqualTo(1);
            assertThat(buf.get(ValueLayout.JAVA_BYTE, 0) & 0xFF).isEqualTo(0x20);
        }

        @Test
        @DisplayName("encodeSizeUpdate also updates the dynamic table max size")
        void sizeUpdateReducesTable() {
            HpackDynamicTable table = new HpackDynamicTable(4096);
            HpackEncoder encoder = new HpackEncoder(table, allocator, false);
            MemorySegment buf = testArena.allocate(64);
            encoder.encodeHeader(buf, 0, "x-k", "v", false);
            assertThat(table.size()).isEqualTo(1);

            encoder.encodeSizeUpdate(buf, 0, 0);
            assertThat(table.size()).isZero();
            assertThat(table.maxSize()).isZero();
        }

        @Test
        @DisplayName("encodeSizeUpdate supports uint32 max and emits multi-byte integer")
        void sizeUpdateSupportsUint32Max() {
            HpackDynamicTable table = new HpackDynamicTable(4096);
            HpackEncoder encoder = new HpackEncoder(table, allocator, false);
            MemorySegment buf = testArena.allocate(16);

            long len = encoder.encodeSizeUpdate(buf, 0, 0xFFFF_FFFFL);

            assertThat(len).isEqualTo(6);
            assertThat(buf.get(ValueLayout.JAVA_BYTE, 0) & 0xFF).isEqualTo(0x3F);
            assertThat(buf.get(ValueLayout.JAVA_BYTE, 1) & 0xFF).isEqualTo(0xE0);
            assertThat(buf.get(ValueLayout.JAVA_BYTE, 2) & 0xFF).isEqualTo(0xFF);
            assertThat(buf.get(ValueLayout.JAVA_BYTE, 3) & 0xFF).isEqualTo(0xFF);
            assertThat(buf.get(ValueLayout.JAVA_BYTE, 4) & 0xFF).isEqualTo(0xFF);
            assertThat(buf.get(ValueLayout.JAVA_BYTE, 5) & 0xFF).isEqualTo(0x0F);
            assertThat(table.maxSize()).isEqualTo(0xFFFF_FFFFL);
        }

        @Test
        @DisplayName("encodeSizeUpdate rejects value greater than uint32")
        void sizeUpdateRejectsGreaterThanUint32() {
            HpackEncoder encoder = encoder(false);
            MemorySegment buf = testArena.allocate(8);

            assertThatThrownBy(() -> encoder.encodeSizeUpdate(buf, 0, 0x1_0000_0000L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("2^32-1");
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private HpackEncoder encoder(boolean huffman) {
        return new HpackEncoder(new HpackDynamicTable(4096), allocator, huffman);
    }
}


