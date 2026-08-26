/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.hpack;

import eu.exeris.kernel.spi.memory.MemoryAllocator;
import org.junit.jupiter.api.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RFC 7541 Appendix C — HPACK Reference Test Vectors.
 *
 * <p>These tests pin the exact wire-format bytes produced and consumed by the encoder and
 * decoder against the normative examples published in the RFC. A round-trip test that passes
 * cannot detect a symmetric encoding bug; only byte-level assertions against the RFC vectors
 * guarantee interoperability with external implementations (Netty, nghttp2, Go net/http2).
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7541#appendix-C">RFC 7541 Appendix C</a>
 */
@DisplayName("L2: RFC 7541 Appendix C — Reference Wire-Format Vectors")
class HpackRfc7541AppendixCTest {

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
    // Appendix C.2 — Literal Header Field Representations (no Huffman)
    // =========================================================================

    @Nested
    @DisplayName("C.2 — Literal Header Field Representations")
    class AppendixC2 {

        /**
         * RFC 7541 Appendix C.2.1 — Literal Header Field with Incremental Indexing.
         *
         * <pre>
         * custom-key: custom-header
         *
         * Wire (hex): 40 0a 63 75 73 74 6f 6d 2d 6b 65 79
         *             0d 63 75 73 74 6f 6d 2d 68 65 61 64 65 72
         * </pre>
         */
        @Test
        @DisplayName("C.2.1 — Literal with Incremental Indexing (new name)")
        void c21LiteralIncrementalNewName() {
            byte[] expected = {
                0x40,                                           // literal incremental, name index 0
                0x0a,                                           // name length 10
                'c','u','s','t','o','m','-','k','e','y',        // "custom-key"
                0x0d,                                           // value length 13
                'c','u','s','t','o','m','-','h','e','a','d','e','r' // "custom-header"
            };

            HpackDynamicTable encTable = new HpackDynamicTable(4096);
            HpackEncoder encoder = new HpackEncoder(encTable, allocator, false);
            MemorySegment buf = testArena.allocate(64);
            long len = encoder.encodeHeader(buf, 0, "custom-key", "custom-header", false);

            assertThat(toBytes(buf, len)).isEqualTo(expected);
            assertThat(encTable.size()).isEqualTo(1);
            assertThat(encTable.getName(0)).isEqualTo("custom-key");
            assertThat(encTable.getValue(0)).isEqualTo("custom-header");
        }

        /**
         * RFC 7541 Appendix C.2.2 — Literal Header Field without Indexing.
         *
         * <pre>
         * :path: /sample/path
         *
         * Wire (hex): 04 0c 2f 73 61 6d 70 6c 65 2f 70 61 74 68
         * </pre>
         *
         * {@code :path} has name index 4 in the static table; the encoder issues a
         * no-index literal when the sensitive flag is set to false but the encoder is
         * configured without Huffman.
         */
        @Test
        @DisplayName("C.2.2 — Literal without Indexing (indexed name)")
        void c22LiteralNoIndexIndexedName() {
            // The RFC C.2.2 example uses literal-without-indexing with :path name index 4.
            // We verify by decoding the exact RFC bytes and asserting the header fields.
            byte[] rfcBytes = {
                0x04,                                           // no-index, name index 4 (:path)
                0x0c,                                           // value length 12
                '/','s','a','m','p','l','e','/','p','a','t','h'
            };

            HpackDynamicTable decTable = new HpackDynamicTable(4096);
            HpackDecoder decoder = new HpackDecoder(decTable, allocator, 65_536);
            List<String[]> headers = decode(decoder, rfcBytes);

            assertThat(headers).hasSize(1);
            assertThat(headers.get(0)).containsExactly(":path", "/sample/path");
            assertThat(decTable.size()).isZero();
        }

        /**
         * RFC 7541 Appendix C.2.3 — Literal Never Indexed.
         *
         * <pre>
         * password: secret
         *
         * Wire (hex): 10 08 70 61 73 73 77 6f 72 64 06 73 65 63 72 65 74
         * </pre>
         */
        @Test
        @DisplayName("C.2.3 — Literal Never Indexed (new name)")
        void c23LiteralNeverIndexed() {
            byte[] rfcBytes = {
                0x10,                                           // never-indexed, name index 0
                0x08,                                           // name length 8
                'p','a','s','s','w','o','r','d',                // "password"
                0x06,                                           // value length 6
                's','e','c','r','e','t'                         // "secret"
            };

            HpackDynamicTable decTable = new HpackDynamicTable(4096);
            HpackDecoder decoder = new HpackDecoder(decTable, allocator, 65_536);
            List<Boolean> sensitive = new ArrayList<>();
            MemorySegment block = fromBytes(rfcBytes);
            decoder.decode(block, 0, rfcBytes.length,
                    (name, value, sens) -> sensitive.add(sens));

            assertThat(sensitive).containsExactly(true);
            assertThat(decTable.size()).isZero();
        }

        /**
         * RFC 7541 Appendix C.2.4 — Indexed Header Field.
         *
         * <pre>
         * :method: GET   (static index 2)
         *
         * Wire (hex): 82
         * </pre>
         */
        @Test
        @DisplayName("C.2.4 — Indexed Header Field (:method GET = 0x82)")
        void c24IndexedHeaderField() {
            byte[] rfcBytes = {(byte) 0x82};

            HpackDecoder decoder = new HpackDecoder(
                    new HpackDynamicTable(4096), allocator, 65_536);
            List<String[]> headers = decode(decoder, rfcBytes);

            assertThat(headers).hasSize(1);
            assertThat(headers.get(0)).containsExactly(":method", "GET");

            // Also verify the encoder produces 0x82 for :method GET
            HpackEncoder encoder = new HpackEncoder(
                    new HpackDynamicTable(4096), allocator, false);
            MemorySegment buf = testArena.allocate(4);
            long len = encoder.encodeHeader(buf, 0, ":method", "GET", false);
            assertThat(toBytes(buf, len)).isEqualTo(rfcBytes);
        }
    }

    // =========================================================================
    // Appendix C.3 — Request Examples without Huffman Coding
    // =========================================================================

    @Nested
    @DisplayName("C.3 — Request Examples without Huffman")
    class AppendixC3 {

        /**
         * RFC 7541 Appendix C.3.1 — First Request.
         *
         * <pre>
         * :method: GET
         * :scheme: http
         * :path: /
         * :authority: www.example.com
         *
         * Wire (hex): 82 86 84 41 0f 77 77 77 2e 65 78 61 6d 70 6c 65 2e 63 6f 6d
         * </pre>
         */
        @Test
        @DisplayName("C.3.1 — First Request (4 pseudo-headers)")
        void c31FirstRequest() {
            byte[] rfcBytes = {
                (byte) 0x82,                                    // :method GET (indexed, 2)
                (byte) 0x86,                                    // :scheme http (indexed, 6)
                (byte) 0x84,                                    // :path / (indexed, 4)
                0x41,                                           // literal+index, name idx 1 (:authority)
                0x0f,                                           // value length 15
                'w','w','w','.','e','x','a','m','p','l','e','.','c','o','m'
            };

            HpackDynamicTable decTable = new HpackDynamicTable(4096);
            HpackDecoder decoder = new HpackDecoder(decTable, allocator, 65_536);
            List<String[]> headers = decode(decoder, rfcBytes);

            assertThat(headers).hasSize(4);
            assertThat(headers.get(0)).containsExactly(":method", "GET");
            assertThat(headers.get(1)).containsExactly(":scheme", "http");
            assertThat(headers.get(2)).containsExactly(":path", "/");
            assertThat(headers.get(3)).containsExactly(":authority", "www.example.com");

            assertThat(decTable.size()).isEqualTo(1);
            assertThat(decTable.getName(0)).isEqualTo(":authority");
            assertThat(decTable.getValue(0)).isEqualTo("www.example.com");
            assertThat(decTable.byteSize()).isEqualTo(57L);
        }

        /**
         * RFC 7541 Appendix C.3.2 — Second Request.
         *
         * <pre>
         * :method: GET
         * :scheme: http
         * :path: /
         * :authority: www.example.com
         * cache-control: no-cache
         *
         * Wire (hex): 82 86 84 be 58 08 6e 6f 2d 63 61 63 68 65
         * </pre>
         *
         * Dynamic table after first request has :authority at index 62.
         * {@code 0xBE} = 1011_1110 = indexed 62.
         */
        @Test
        @DisplayName("C.3.2 — Second Request (dynamic table reuse + new cache-control)")
        void c32SecondRequest() {
            HpackDynamicTable decTable = new HpackDynamicTable(4096);
            HpackDecoder decoder = new HpackDecoder(decTable, allocator, 65_536);

            byte[] firstRequest = {
                (byte) 0x82, (byte) 0x86, (byte) 0x84,
                0x41, 0x0f,
                'w','w','w','.','e','x','a','m','p','l','e','.','c','o','m'
            };
            decode(decoder, firstRequest);

            byte[] rfcBytes = {
                (byte) 0x82,                                    // :method GET
                (byte) 0x86,                                    // :scheme http
                (byte) 0x84,                                    // :path /
                (byte) 0xbe,                                    // :authority www.example.com (dyn idx 62)
                0x58,                                           // literal+index, name idx 24 (cache-control)
                0x08,                                           // value length 8
                'n','o','-','c','a','c','h','e'
            };

            List<String[]> headers = decode(decoder, rfcBytes);

            assertThat(headers).hasSize(5);
            assertThat(headers.get(3)).containsExactly(":authority", "www.example.com");
            assertThat(headers.get(4)).containsExactly("cache-control", "no-cache");

            assertThat(decTable.size()).isEqualTo(2);
            assertThat(decTable.getName(0)).isEqualTo("cache-control");
            assertThat(decTable.getValue(0)).isEqualTo("no-cache");
        }

        /**
         * RFC 7541 Appendix C.3.3 — Third Request.
         *
         * <pre>
         * :method: GET
         * :scheme: https
         * :path: /index.html
         * :authority: www.example.com
         * custom-key: custom-value
         *
         * Wire (hex): 82 87 85 bf 40 0a 63 75 73 74 6f 6d 2d 6b 65 79
         *             0c 63 75 73 74 6f 6d 2d 76 61 6c 75 65
         * </pre>
         */
        @Test
        @DisplayName("C.3.3 — Third Request (custom header added to dynamic table)")
        void c33ThirdRequest() {
            HpackDynamicTable decTable = new HpackDynamicTable(4096);
            HpackDecoder decoder = new HpackDecoder(decTable, allocator, 65_536);

            byte[] firstRequest = {
                (byte) 0x82, (byte) 0x86, (byte) 0x84,
                0x41, 0x0f,
                'w','w','w','.','e','x','a','m','p','l','e','.','c','o','m'
            };
            byte[] secondRequest = {
                (byte) 0x82, (byte) 0x86, (byte) 0x84,
                (byte) 0xbe,
                0x58, 0x08, 'n','o','-','c','a','c','h','e'
            };
            decode(decoder, firstRequest);
            decode(decoder, secondRequest);

            byte[] rfcBytes = {
                (byte) 0x82,                                    // :method GET (idx 2)
                (byte) 0x87,                                    // :scheme https (idx 7)
                (byte) 0x85,                                    // :path /index.html (idx 5)
                (byte) 0xbf,                                    // :authority www.example.com (dyn idx 63)
                0x40,                                           // literal+index, new name
                0x0a,                                           // name length 10
                'c','u','s','t','o','m','-','k','e','y',
                0x0c,                                           // value length 12
                'c','u','s','t','o','m','-','v','a','l','u','e'
            };

            List<String[]> headers = decode(decoder, rfcBytes);

            assertThat(headers).hasSize(5);
            assertThat(headers.get(0)).containsExactly(":method", "GET");
            assertThat(headers.get(1)).containsExactly(":scheme", "https");
            assertThat(headers.get(2)).containsExactly(":path", "/index.html");
            assertThat(headers.get(3)).containsExactly(":authority", "www.example.com");
            assertThat(headers.get(4)).containsExactly("custom-key", "custom-value");

            assertThat(decTable.size()).isEqualTo(3);
            assertThat(decTable.getName(0)).isEqualTo("custom-key");
            assertThat(decTable.getValue(0)).isEqualTo("custom-value");
            assertThat(decTable.byteSize()).isEqualTo(164L);
        }
    }

    // =========================================================================
    // Appendix C.4 — Request Examples with Huffman Coding
    // =========================================================================

    @Nested
    @DisplayName("C.4 — Request Examples with Huffman Coding")
    class AppendixC4 {

        /**
         * RFC 7541 Appendix C.4.1 — First Request with Huffman.
         *
         * <pre>
         * :method: GET
         * :scheme: http
         * :path: /
         * :authority: www.example.com
         *
         * Wire (hex): 82 86 84 41 8c f1 e3 c2 e5 f2 3a 6b a0 ab 90 f4 ff
         * </pre>
         *
         * The Huffman-encoded form of "www.example.com" is:
         * {@code f1 e3 c2 e5 f2 3a 6b a0 ab 90 f4 ff} (12 bytes, saves 3).
         */
        @Test
        @DisplayName("C.4.1 — First Request with Huffman (:authority Huffman-encoded)")
        void c41FirstRequestHuffman() {
            byte[] rfcBytes = {
                (byte) 0x82,                                    // :method GET
                (byte) 0x86,                                    // :scheme http
                (byte) 0x84,                                    // :path /
                0x41,                                           // literal+index, name idx 1
                (byte) 0x8c,                                    // value: Huffman=1, length 12
                (byte) 0xf1, (byte) 0xe3, (byte) 0xc2, (byte) 0xe5,
                (byte) 0xf2, 0x3a, 0x6b, (byte) 0xa0,
                (byte) 0xab, (byte) 0x90, (byte) 0xf4, (byte) 0xff
            };

            HpackDynamicTable decTable = new HpackDynamicTable(4096);
            HpackDecoder decoder = new HpackDecoder(decTable, allocator, 65_536);
            List<String[]> headers = decode(decoder, rfcBytes);

            assertThat(headers).hasSize(4);
            assertThat(headers.get(3)).containsExactly(":authority", "www.example.com");
            assertThat(decTable.size()).isEqualTo(1);
            assertThat(decTable.byteSize()).isEqualTo(57L);
        }

        /**
         * RFC 7541 Appendix C.4.3 — Third Request with Huffman.
         *
         * <pre>
         * :method: GET
         * :scheme: https
         * :path: /index.html
         * :authority: www.example.com
         * custom-key: custom-value
         *
         * Huffman("custom-key")   = 25 a8 49 e9 5b a9 7d 7f (8 bytes)
         * Huffman("custom-value") = 25 a8 49 e9 5b b8 e8 b4 bf (9 bytes)
         * </pre>
         */
        @Test
        @DisplayName("C.4.3 — Third Request with Huffman (custom header Huffman-encoded)")
        void c43ThirdRequestHuffman() {
            byte[] rfcBytes = {
                (byte) 0x82,                                    // :method GET
                (byte) 0x87,                                    // :scheme https
                (byte) 0x85,                                    // :path /index.html
                (byte) 0xbf,                                    // :authority (dyn idx 63)
                0x40,                                           // literal+index, new name, no Huffman prefix
                (byte) 0x88,                                    // name: Huffman=1, length 8
                0x25, (byte) 0xa8, 0x49, (byte) 0xe9, 0x5b, (byte) 0xa9, 0x7d, 0x7f,
                (byte) 0x89,                                    // value: Huffman=1, length 9
                0x25, (byte) 0xa8, 0x49, (byte) 0xe9, 0x5b, (byte) 0xb8,
                (byte) 0xe8, (byte) 0xb4, (byte) 0xbf
            };

            HpackDynamicTable decTable = new HpackDynamicTable(4096);
            HpackDecoder decoder = new HpackDecoder(decTable, allocator, 65_536);

            byte[] firstRequest = {
                (byte) 0x82, (byte) 0x86, (byte) 0x84,
                0x41,
                (byte) 0x8c,
                (byte) 0xf1, (byte) 0xe3, (byte) 0xc2, (byte) 0xe5,
                (byte) 0xf2, 0x3a, 0x6b, (byte) 0xa0,
                (byte) 0xab, (byte) 0x90, (byte) 0xf4, (byte) 0xff
            };
            byte[] secondRequest = {
                (byte) 0x82, (byte) 0x86, (byte) 0x84,
                (byte) 0xbe,
                0x58,
                (byte) 0x86,
                (byte) 0xa8, (byte) 0xeb, 0x10, 0x64, (byte) 0x9c, (byte) 0xbf
            };
            decode(decoder, firstRequest);
            decode(decoder, secondRequest);

            List<String[]> headers = decode(decoder, rfcBytes);

            assertThat(headers).hasSize(5);
            assertThat(headers.get(4)).containsExactly("custom-key", "custom-value");
            assertThat(decTable.size()).isEqualTo(3);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private List<String[]> decode(HpackDecoder decoder, byte[] bytes) {
        MemorySegment block = fromBytes(bytes);
        List<String[]> result = new ArrayList<>();
        decoder.decode(block, 0, bytes.length,
                (name, value, sensitive) -> result.add(new String[]{name, value}));
        return result;
    }

    private MemorySegment fromBytes(byte[] bytes) {
        MemorySegment seg = testArena.allocate(bytes.length);
        MemorySegment.copy(MemorySegment.ofArray(bytes), ValueLayout.JAVA_BYTE, 0,
                seg, ValueLayout.JAVA_BYTE, 0, bytes.length);
        return seg;
    }

    private static byte[] toBytes(MemorySegment seg, long length) {
        byte[] result = new byte[(int) length];
        MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, 0, result, 0, result.length);
        return result;
    }
}
