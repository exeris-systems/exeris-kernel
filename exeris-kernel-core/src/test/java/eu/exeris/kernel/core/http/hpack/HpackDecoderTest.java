/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.http.hpack;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import org.junit.jupiter.api.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("L0: HpackDecoder — RFC 7541 §3 Decoding Contract")
class HpackDecoderTest {

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
    // RFC 7541 Appendix C.3 — Indexed Header Field (§6.1)
    // =========================================================================

    @Nested
    @DisplayName("Indexed Header Field — §6.1")
    class IndexedRepresentation {

        @Test
        @DisplayName("Static table index 2 resolves :method GET")
        void staticIndexMethodGet() {
            MemorySegment block = testArena.allocate(1);
            block.set(ValueLayout.JAVA_BYTE, 0, (byte) (0x80 | 2));

            List<String[]> decoded = decode(block, 0, 1);
            assertThat(decoded).hasSize(1);
            assertThat(decoded.get(0)).containsExactly(":method", "GET");
        }

        @Test
        @DisplayName("Index 0 is invalid — throws HpackDecodingException")
        void indexZeroIsInvalid() {
            MemorySegment block = testArena.allocate(1);
            block.set(ValueLayout.JAVA_BYTE, 0, (byte) 0x80);

            assertThatThrownBy(() -> decode(block, 0, 1))
                    .isInstanceOf(HpackDecoder.HpackDecodingException.class)
                    .satisfies(ex -> assertThat(((HpackDecoder.HpackDecodingException) ex).errorCode())
                            .isEqualTo(KernelErrorCodes.EX_HTTP_4002));
        }

        @Test
        @DisplayName("Out-of-range dynamic index throws HpackDecodingException")
        void outOfRangeDynamicIndex() {
            MemorySegment block = testArena.allocate(1);
            // index = static size + 5 (no entries in dynamic table)
            int idx = HpackStaticTable.SIZE + 5;
            block.set(ValueLayout.JAVA_BYTE, 0, (byte) (0x80 | idx));

            assertThatThrownBy(() -> decode(block, 0, 1))
                    .isInstanceOf(HpackDecoder.HpackDecodingException.class);
        }
    }

    // =========================================================================
    // Literal Header — No Indexing (§6.2.2)
    // =========================================================================

    @Nested
    @DisplayName("Literal Header — No Indexing §6.2.2")
    class LiteralNoIndex {

        @Test
        @DisplayName("New name, new value — does NOT add to dynamic table")
        void newNameNoIndex() {
            HpackDynamicTable table = new HpackDynamicTable(4096);
            HpackDecoder decoder = new HpackDecoder(table, allocator, 65_536);

            // First byte: 0x00 (no-index, name index = 0)
            // Then: string literal "x-test" (no Huffman), then value "v"
            MemorySegment block = testArena.allocate(32);
            long pos = 0;
            pos = writeLiteralNoIndex(block, pos, "x-test", "v");

            List<String[]> decoded = new ArrayList<>();
            decoder.decode(block, 0, pos, (n, v, s) -> decoded.add(new String[]{n, v}));

            assertThat(decoded).hasSize(1);
            assertThat(decoded.get(0)).containsExactly("x-test", "v");
            assertThat(table.size()).isZero();
        }
    }

    // =========================================================================
    // Literal Header — Never Indexed (§6.2.3)
    // =========================================================================

    @Nested
    @DisplayName("Literal Header — Never Indexed §6.2.3")
    class LiteralNeverIndexed {

        @Test
        @DisplayName("Never-indexed field arrives with sensitive=true")
        void neverIndexedSensitiveFlag() {
            HpackDecoder decoder = new HpackDecoder(
                    new HpackDynamicTable(4096), allocator, 65_536);

            MemorySegment block = testArena.allocate(64);
            long pos = writeLiteralNeverIndexed(block, 0, "authorization", "bearer token");

            List<Boolean> flags = new ArrayList<>();
            decoder.decode(block, 0, pos, (n, v, sensitive) -> flags.add(sensitive));

            assertThat(flags).containsExactly(true);
        }
    }

    // =========================================================================
    // Dynamic Table Size Update (§6.3)
    // =========================================================================

    @Nested
    @DisplayName("Dynamic Table Size Update — §6.3 + §4.2 position rules")
    class SizeUpdate {

        @Test
        @DisplayName("Size update at beginning of block is accepted")
        void sizeUpdateEmitsNoHeader() {
            HpackDynamicTable table = new HpackDynamicTable(4096);
            HpackDecoder decoder = new HpackDecoder(table, allocator, 65_536);

            MemorySegment block = testArena.allocate(1);
            block.set(ValueLayout.JAVA_BYTE, 0, (byte) 0x20);

            List<String[]> decoded = new ArrayList<>();
            decoder.decode(block, 0, 1, (n, v, s) -> decoded.add(new String[]{n, v}));

            assertThat(decoded).isEmpty();
            assertThat(table.maxSize()).isZero();
        }

        @Test
        @DisplayName("Two size updates at beginning of block are accepted (RFC §4.2 — at most two)")
        void twoSizeUpdatesAtStartAccepted() {
            HpackDynamicTable table = new HpackDynamicTable(4096);
            HpackDecoder decoder = new HpackDecoder(table, allocator, 65_536);

            // First size update: 0 (fits in 5-bit prefix: 0x20 | 0 = 0x20)
            // Second size update: 32 → 32 >= 31 so: 0x3F (prefix saturated) + 0x01 (32-31=1)
            // Then indexed :method GET = 0x82
            MemorySegment block = testArena.allocate(5);
            block.set(ValueLayout.JAVA_BYTE, 0, (byte) 0x20);       // size update → 0
            block.set(ValueLayout.JAVA_BYTE, 1, (byte) 0x3F);       // size update prefix saturated
            block.set(ValueLayout.JAVA_BYTE, 2, (byte) 0x01);       // 32-31 = 1
            block.set(ValueLayout.JAVA_BYTE, 3, (byte) 0x82);       // indexed idx 2 → :method GET

            List<String[]> decoded = new ArrayList<>();
            decoder.decode(block, 0, 4, (n, v, s) -> decoded.add(new String[]{n, v}));

            assertThat(decoded).hasSize(1);
            assertThat(table.maxSize()).isEqualTo(32);
        }

        /**
         * RFC §4.2: "This dynamic table size update MUST occur at the beginning of the
         * first header block following the change to the dynamic table size."
         * A size update after a literal or indexed representation is a decoding error.
         */
        @Test
        @DisplayName("RFC §4.2 — size update after indexed field throws HpackDecodingException")
        void sizeUpdateAfterIndexedRejected() {
            HpackDynamicTable table = new HpackDynamicTable(4096);
            HpackDecoder decoder = new HpackDecoder(table, allocator, 65_536);

            MemorySegment block = testArena.allocate(2);
            block.set(ValueLayout.JAVA_BYTE, 0, (byte) 0x82); // indexed idx 2
            block.set(ValueLayout.JAVA_BYTE, 1, (byte) 0x20); // size update → illegal here

            assertThatThrownBy(() -> decoder.decode(block, 0, 2, (n, v, s) -> {}))
                    .isInstanceOf(HpackDecoder.HpackDecodingException.class)
                    .hasMessageContaining("size update");
        }

        @Test
        @DisplayName("RFC §4.2 — size update after literal field throws HpackDecodingException")
        void sizeUpdateAfterLiteralRejected() {
            HpackDynamicTable table = new HpackDynamicTable(4096);
            HpackDecoder decoder = new HpackDecoder(table, allocator, 65_536);

            MemorySegment block = testArena.allocate(8);
            long pos = writeLiteralNoIndex(block, 0, "x", "y");
            block.set(ValueLayout.JAVA_BYTE, pos, (byte) 0x20);

            assertThatThrownBy(() -> decoder.decode(block, 0, pos + 1, (n, v, s) -> {}))
                    .isInstanceOf(HpackDecoder.HpackDecodingException.class)
                    .hasMessageContaining("size update");
        }

        /**
         * RFC §6.3: "The new maximum size MUST be lower than or equal to the limit
         * determined by the protocol using HPACK."
         */
        @Test
        @DisplayName("RFC §6.3 — size update exceeding protocol limit throws HpackDecodingException")
        void sizeUpdateExceedingProtocolLimitRejected() {
            HpackDynamicTable table = new HpackDynamicTable(4096);
            HpackDecoder decoder = new HpackDecoder(table, allocator, 65_536);
            decoder.setProtocolMaxTableSize(256);

            MemorySegment block = testArena.allocate(4);
            // Encode size update with newSize = 512 (0x200) in 5-bit prefix
            // 0x3F = prefix saturated (31), then 512-31 = 481 = 0x1E1
            // 0x1E1 & 0x7F | 0x80 = 0xE1, 0x1E1 >> 7 = 3
            block.set(ValueLayout.JAVA_BYTE, 0, (byte) (0x20 | 0x1F)); // 0x3F
            block.set(ValueLayout.JAVA_BYTE, 1, (byte) 0xE1);           // 481 & 0x7F | 0x80
            block.set(ValueLayout.JAVA_BYTE, 2, (byte) 0x03);           // 481 >> 7

            assertThatThrownBy(() -> decoder.decode(block, 0, 3, (n, v, s) -> {}))
                    .isInstanceOf(HpackDecoder.HpackDecodingException.class)
                    .hasMessageContaining("protocol limit");
        }

        @Test
        @DisplayName("setProtocolMaxTableSize respected — size update within limit accepted")
        void sizeUpdateWithinProtocolLimitAccepted() {
            HpackDynamicTable table = new HpackDynamicTable(4096);
            HpackDecoder decoder = new HpackDecoder(table, allocator, 65_536);
            decoder.setProtocolMaxTableSize(256);

            MemorySegment block = testArena.allocate(2);
            // size update to 128 (fits in 5-bit prefix as 128-0=128; 128 > 31 → multi-byte)
            // 0x3F | 0x20 = 0x3F, then 97 = 128-31
            block.set(ValueLayout.JAVA_BYTE, 0, (byte) (0x20 | 0x1F)); // prefix saturated
            block.set(ValueLayout.JAVA_BYTE, 1, (byte) 0x61);           // 128-31 = 97

            decoder.decode(block, 0, 2, (n, v, s) -> {});
            assertThat(table.maxSize()).isEqualTo(128);
        }

        @Test
        @DisplayName("setProtocolMaxTableSize rejects negative value")
        void setProtocolMaxTableSizeRejectsNegative() {
            HpackDecoder decoder = new HpackDecoder(new HpackDynamicTable(4096), allocator, 65_536);
            assertThatThrownBy(() -> decoder.setProtocolMaxTableSize(-1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SETTINGS_HEADER_TABLE_SIZE");
        }

        @Test
        @DisplayName("setProtocolMaxTableSize rejects value exceeding 2^32-1")
        void setProtocolMaxTableSizeRejectsAboveUint32Max() {
            HpackDecoder decoder = new HpackDecoder(new HpackDynamicTable(4096), allocator, 65_536);
            assertThatThrownBy(() -> decoder.setProtocolMaxTableSize(0x1_0000_0000L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SETTINGS_HEADER_TABLE_SIZE");
        }

        @Test
        @DisplayName("setProtocolMaxTableSize accepts boundary value 0xFFFF_FFFFL")
        void setProtocolMaxTableSizeAcceptsUint32Max() {
            HpackDecoder decoder = new HpackDecoder(new HpackDynamicTable(4096), allocator, 65_536);
            assertThatCode(() -> decoder.setProtocolMaxTableSize(0xFFFF_FFFFL))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("§6.3 size update accepts uint32 max value")
        void sizeUpdateAcceptsUint32Max() {
            HpackDynamicTable table = new HpackDynamicTable(4096);
            HpackDecoder decoder = new HpackDecoder(table, allocator, 65_536);
            decoder.setProtocolMaxTableSize(0xFFFF_FFFFL);

            MemorySegment block = testArena.allocate(8);
            block.set(ValueLayout.JAVA_BYTE, 0, (byte) 0x3F);
            block.set(ValueLayout.JAVA_BYTE, 1, (byte) 0xE0);
            block.set(ValueLayout.JAVA_BYTE, 2, (byte) 0xFF);
            block.set(ValueLayout.JAVA_BYTE, 3, (byte) 0xFF);
            block.set(ValueLayout.JAVA_BYTE, 4, (byte) 0xFF);
            block.set(ValueLayout.JAVA_BYTE, 5, (byte) 0x0F);

            decoder.decode(block, 0, 6, (n, v, s) -> {});

            assertThat(table.maxSize()).isEqualTo(0xFFFF_FFFFL);
        }

        @Test
        @DisplayName("§6.3 size update rejects value above uint32")
        void sizeUpdateRejectsAboveUint32() {
            HpackDynamicTable table = new HpackDynamicTable(4096);
            HpackDecoder decoder = new HpackDecoder(table, allocator, 65_536);
            decoder.setProtocolMaxTableSize(0xFFFF_FFFFL);

            MemorySegment block = testArena.allocate(8);
            block.set(ValueLayout.JAVA_BYTE, 0, (byte) 0x3F);
            block.set(ValueLayout.JAVA_BYTE, 1, (byte) 0xE1);
            block.set(ValueLayout.JAVA_BYTE, 2, (byte) 0xFF);
            block.set(ValueLayout.JAVA_BYTE, 3, (byte) 0xFF);
            block.set(ValueLayout.JAVA_BYTE, 4, (byte) 0xFF);
            block.set(ValueLayout.JAVA_BYTE, 5, (byte) 0x0F);

            assertThatThrownBy(() -> decoder.decode(block, 0, 6, (n, v, s) -> {}))
                    .isInstanceOf(HpackDecoder.HpackDecodingException.class)
                    .hasMessageContaining("integer overflow");
        }
    }

    // =========================================================================
    // Error Conditions
    // =========================================================================

    @Nested
    @DisplayName("Error conditions — §3.1 violations")
    class ErrorConditions {

        @Test
        @DisplayName("Truncated block in integer throws HpackDecodingException")
        void truncatedInteger() {
            MemorySegment block = testArena.allocate(1);
            // Multi-byte integer: value == prefix_max, continuation expected but block ends
            block.set(ValueLayout.JAVA_BYTE, 0, (byte) 0xFF);

            assertThatThrownBy(() -> decode(block, 0, 1))
                    .isInstanceOf(HpackDecoder.HpackDecodingException.class);
        }

        @Test
        @DisplayName("String literal exceeding MAX_STRING_LITERAL throws")
        void stringTooLong() {
            HpackDecoder decoder = new HpackDecoder(
                    new HpackDynamicTable(4096), allocator, 65_536);

            // Build a literal no-index frame with length > 65536
            MemorySegment block = testArena.allocate(16);
            long pos = 0;
            block.set(ValueLayout.JAVA_BYTE, pos++, (byte) 0x00); // no-index, new name
            block.set(ValueLayout.JAVA_BYTE, pos++, (byte) 0x00); // name length = 0 (invalid but reaches string check)
            // value: huffman=0, length encoded as multi-byte > 127 to exceed limit
            // Encode length = 65537 (0x10001) in 7-bit prefix HPACK integer
            block.set(ValueLayout.JAVA_BYTE, pos++, (byte) 0x7F); // prefix saturated
            block.set(ValueLayout.JAVA_BYTE, pos++, (byte) 0x82); // continuation: (0x02 << 0) + 0x80 flag
            block.set(ValueLayout.JAVA_BYTE, pos, (byte) 0x04);   // continuation: (1 << 7) = bit, value = 2 + 4*128 = 514 => 127+514=641, still not > 65536; use bigger value
            // Encode 65537-127 = 65410 = 0xFF82 in little-endian 7-bit groups:
            // 65410 = 0x82 | 0x80 (low 7), next = (65410 >> 7) & 0x7F | ...
            // Just verify that the decoder reaches the length guard; the above is close enough.
            // Reset and write properly:
            block = testArena.allocate(32);
            pos = 0;
            block.set(ValueLayout.JAVA_BYTE, pos++, (byte) 0x00); // no-index, new name
            // name: length 1, literal "x"
            block.set(ValueLayout.JAVA_BYTE, pos++, (byte) 0x01);
            block.set(ValueLayout.JAVA_BYTE, pos++, (byte) 'x');
            // value: non-huffman, length = 65537
            // 7-bit prefix: 127 (0x7F), then encode (65537 - 127) = 65410 in 7-bit groups
            // 65410 = 0b1111111_1000010
            // byte1 = (65410 & 0x7F) | 0x80 = 0x02 | 0x80 = 0x82
            // byte2 = (65410 >> 7) & 0x7F = 0x82 & 0x7F → wait: 65410 >> 7 = 511 = 0x1FF
            // byte2 = (511 & 0x7F) | 0x80 = 0x7F | 0x80 = 0xFF
            // byte3 = 511 >> 7 = 3 = 0x03
            block.set(ValueLayout.JAVA_BYTE, pos++, (byte) 0x7F);
            block.set(ValueLayout.JAVA_BYTE, pos++, (byte) 0x82); // (65410 & 0x7F) | 0x80
            block.set(ValueLayout.JAVA_BYTE, pos++, (byte) 0xFF); // (511 & 0x7F) | 0x80
            block.set(ValueLayout.JAVA_BYTE, pos, (byte) 0x03);   // 3

            final MemorySegment finalBlock = block;
            final long finalPos = pos + 1;
            assertThatThrownBy(() -> decoder.decode(finalBlock, 0, finalPos, (n, v, s) -> {}))
                    .isInstanceOf(HpackDecoder.HpackDecodingException.class);
        }

        @Test
        @DisplayName("Header list size exceeding limit throws HpackDecodingException")
        void headerListSizeExceeded() {
            HpackDecoder decoder = new HpackDecoder(
                    new HpackDynamicTable(4096), allocator, 50);

            HpackDynamicTable encTable = new HpackDynamicTable(4096);
            HpackEncoder encoder = new HpackEncoder(encTable, allocator, false);
            MemorySegment buf = testArena.allocate(256);
            long len = encoder.encodeHeader(buf, 0,
                    "x-long-header-name", "x-long-header-value", false);

            assertThatThrownBy(() -> decoder.decode(buf, 0, len, (n, v, s) -> {}))
                    .isInstanceOf(HpackDecoder.HpackDecodingException.class);
        }

        @Test
        @DisplayName("Unknown representation byte throws HpackDecodingException")
        void unknownRepresentation() {
            MemorySegment block = testArena.allocate(1);
            // 0x70 = 0111_0000 — not matched by any RFC 7541 rule
            block.set(ValueLayout.JAVA_BYTE, 0, (byte) 0x70);

            assertThatThrownBy(() -> decode(block, 0, 1))
                    .isInstanceOf(HpackDecoder.HpackDecodingException.class);
        }
    }

    // =========================================================================
    // Huffman decode path — §5.2
    // =========================================================================

    @Nested
    @DisplayName("Huffman string decoding — §5.2")
    class HuffmanDecodePath {

        /**
         * Writes a literal-incremental header block with ":authority" name index 1
         * and Huffman("www.example.com") = RFC C.4.1 bytes, then decodes it.
         *
         * <p>This is the only L0 test that exercises {@code decodeHuffmanString} directly.
         * The block is constructed by hand so the test does not depend on the encoder.
         */
        @Test
        @DisplayName("Decodes Huffman-encoded value (RFC C.4.1 — www.example.com)")
        void huffmanValueDecoded() {
            byte[] block = {
                0x41,                                           // literal+index, name idx 1
                (byte) 0x8c,                                    // Huffman=1, length 12
                (byte) 0xf1, (byte) 0xe3, (byte) 0xc2, (byte) 0xe5,
                (byte) 0xf2, 0x3a, 0x6b, (byte) 0xa0,
                (byte) 0xab, (byte) 0x90, (byte) 0xf4, (byte) 0xff
            };
            MemorySegment seg = testArena.allocate(block.length);
            MemorySegment.copy(MemorySegment.ofArray(block), ValueLayout.JAVA_BYTE, 0,
                    seg, ValueLayout.JAVA_BYTE, 0, block.length);

            List<String[]> decoded = decode(seg, 0, block.length);

            assertThat(decoded).hasSize(1);
            assertThat(decoded.get(0)).containsExactly(":authority", "www.example.com");
        }

        @Test
        @DisplayName("Decodes Huffman-encoded name (RFC C.4.3 — custom-key)")
        void huffmanNameDecoded() {
            // literal+index, new name; name = Huffman("custom-key"), value = Huffman("custom-value")
            byte[] block = {
                0x40,                                           // literal+index, new name
                (byte) 0x88,                                    // Huffman=1, length 8
                0x25, (byte) 0xa8, 0x49, (byte) 0xe9, 0x5b, (byte) 0xa9, 0x7d, 0x7f,
                (byte) 0x89,                                    // Huffman=1, length 9
                0x25, (byte) 0xa8, 0x49, (byte) 0xe9, 0x5b,
                (byte) 0xb8, (byte) 0xe8, (byte) 0xb4, (byte) 0xbf
            };
            MemorySegment seg = testArena.allocate(block.length);
            MemorySegment.copy(MemorySegment.ofArray(block), ValueLayout.JAVA_BYTE, 0,
                    seg, ValueLayout.JAVA_BYTE, 0, block.length);

            List<String[]> decoded = decode(seg, 0, block.length);

            assertThat(decoded).hasSize(1);
            assertThat(decoded.get(0)).containsExactly("custom-key", "custom-value");
        }

        @Test
        @DisplayName("Malformed Huffman literal is wrapped as HpackDecodingException")
        void malformedHuffmanLiteralWrapped() {
            byte[] block = {
                0x41,             // literal+index, name idx 1 (:authority)
                (byte) 0x82,      // Huffman=1, length 2
                0x1F, (byte) 0xFF // invalid Huffman payload (padding > 7 bits)
            };
            MemorySegment seg = testArena.allocate(block.length);
            MemorySegment.copy(MemorySegment.ofArray(block), ValueLayout.JAVA_BYTE, 0,
                    seg, ValueLayout.JAVA_BYTE, 0, block.length);

            assertThatThrownBy(() -> decode(seg, 0, block.length))
                    .isInstanceOf(HpackDecoder.HpackDecodingException.class)
                    .hasCauseInstanceOf(
                            eu.exeris.kernel.core.http.hpack.huffman.Huffman.HuffmanDecodingException.class)
                    .hasMessageContaining("invalid Huffman-encoded string literal");
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private List<String[]> decode(MemorySegment block, long offset, long length) {
        HpackDecoder decoder = new HpackDecoder(
                new HpackDynamicTable(4096), allocator, 65_536);
        List<String[]> result = new ArrayList<>();
        decoder.decode(block, offset, length,
                (name, value, sensitive) -> result.add(new String[]{name, value}));
        return result;
    }

    private static long writeLiteralNoIndex(MemorySegment seg, long pos,
                                            String name, String value) {
        seg.set(ValueLayout.JAVA_BYTE, pos++, (byte) 0x00);
        pos = writeStringLiteral(seg, pos, name);
        pos = writeStringLiteral(seg, pos, value);
        return pos;
    }

    private static long writeLiteralNeverIndexed(MemorySegment seg, long pos,
                                                  String name, String value) {
        seg.set(ValueLayout.JAVA_BYTE, pos++, (byte) 0x10);
        pos = writeStringLiteral(seg, pos, name);
        pos = writeStringLiteral(seg, pos, value);
        return pos;
    }

    private static long writeStringLiteral(MemorySegment seg, long pos, String str) {
        byte[] bytes = str.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        seg.set(ValueLayout.JAVA_BYTE, pos++, (byte) bytes.length);
        for (byte b : bytes) {
            seg.set(ValueLayout.JAVA_BYTE, pos++, b);
        }
        return pos;
    }
}


