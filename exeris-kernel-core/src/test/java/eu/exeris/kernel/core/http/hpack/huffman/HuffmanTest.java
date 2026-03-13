/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.http.hpack.huffman;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("L0: Huffman — Encode/Decode Round-Trip Contract")
class HuffmanTest {

    @Nested
    @DisplayName("Round-trip — encode then decode")
    class RoundTrip {

        @Test
        @DisplayName("'www.example.com' survives encode → decode")
        void roundTripWwwExampleCom() {
            roundTrip("www.example.com");
        }

        @Test
        @DisplayName("Empty string survives encode → decode")
        void roundTripEmptyString() {
            roundTrip("");
        }

        @Test
        @DisplayName("Single character 'a' survives encode → decode")
        void roundTripSingleChar() {
            roundTrip("a");
        }

        @Test
        @DisplayName("All printable ASCII survives encode → decode")
        void roundTripAllPrintableAscii() {
            StringBuilder allPrintable = new StringBuilder();
            for (int ch = 0x20; ch < 0x7F; ch++) {
                allPrintable.append((char) ch);
            }
            roundTrip(allPrintable.toString());
        }

        @Test
        @DisplayName("'custom-key' survives encode → decode (RFC 7541 C.2.1)")
        void roundTripCustomKey() {
            roundTrip("custom-key");
        }

        @Test
        @DisplayName("'custom-value' survives encode → decode (RFC 7541 C.2.1)")
        void roundTripCustomValue() {
            roundTrip("custom-value");
        }

        private void roundTrip(String input) {
            byte[] raw = input.getBytes(StandardCharsets.UTF_8);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment src = arena.allocate(raw.length);
                MemorySegment.copy(MemorySegment.ofArray(raw), ValueLayout.JAVA_BYTE, 0,
                        src, ValueLayout.JAVA_BYTE, 0, raw.length);

                MemorySegment encoded = arena.allocate(raw.length * 2 + 16);
                long encodedLen = Huffman.encode(src, encoded);

                MemorySegment decoded = arena.allocate(raw.length + 16);
                int decodedLen = Huffman.decode(encoded, 0, encodedLen, decoded);

                byte[] result = new byte[decodedLen];
                MemorySegment.copy(decoded, ValueLayout.JAVA_BYTE, 0, result, 0, decodedLen);
                assertThat(new String(result, StandardCharsets.UTF_8)).isEqualTo(input);
            }
        }
    }

    @Nested
    @DisplayName("Encoding compression — Huffman is shorter for typical headers")
    class Compression {

        @Test
        @DisplayName("'www.example.com' compresses to fewer bytes than raw")
        void compressionBenefit() {
            byte[] raw = "www.example.com".getBytes(StandardCharsets.UTF_8);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment src = arena.allocate(raw.length);
                MemorySegment.copy(MemorySegment.ofArray(raw), ValueLayout.JAVA_BYTE, 0,
                        src, ValueLayout.JAVA_BYTE, 0, raw.length);
                long huffLen = Huffman.encodedLength(src);
                assertThat(huffLen).isLessThan(raw.length);
            }
        }
    }

    @Nested
    @DisplayName("Error cases — RFC §5.2 MUST violations")
    class ErrorCases {

        @Test
        @DisplayName("Invalid Huffman data throws HuffmanDecodingException")
        void truncatedData() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment bad = arena.allocate(3);
                bad.set(ValueLayout.JAVA_BYTE, 0, (byte) 0x00);
                bad.set(ValueLayout.JAVA_BYTE, 1, (byte) 0x00);
                bad.set(ValueLayout.JAVA_BYTE, 2, (byte) 0x00);

                MemorySegment out = arena.allocate(16);
                assertThatThrownBy(() -> Huffman.decode(bad, 0, 3, out))
                        .isInstanceOf(Huffman.HuffmanDecodingException.class)
                        .satisfies(ex -> assertThat(((Huffman.HuffmanDecodingException) ex).errorCode())
                                .isEqualTo(KernelErrorCodes.EX_HTTP_4001));
            }
        }

        /**
         * RFC §5.2: "A padding strictly longer than 7 bits MUST be treated as a decoding error."
         *
         * <p>Two consecutive 0xFF bytes encode 'a' (5 bits: 00011) followed by 11 bits of
         * 1-padding. The last octet contributes 8 zero-length bits — but the last symbol
         * is emitted after the high nibble of the second byte, leaving the full low nibble
         * (4 bits) + nothing consumed in the first byte's residual as extra padding.
         * In practice, any input where an entire trailing octet consists only of 1-padding
         * nibbles that don't emit a symbol exceeds the 7-bit limit.
         */
        @Test
        @DisplayName("RFC §5.2 — padding > 7 bits throws HuffmanDecodingException")
        void paddingExceedsSevenBits() {
            try (Arena arena = Arena.ofConfined()) {
                // 'a' encodes to 00011 (5 bits, code 0x03 len 5).
                // Padded to 1 byte: 0001_1111 = 0x1F (3 bits of 1-padding, valid).
                // But wrap in two octets: 0x1F 0xFF — second byte is pure 1-padding (8 bits > 7).
                MemorySegment src = arena.allocate(2);
                src.set(ValueLayout.JAVA_BYTE, 0, (byte) 0x1F);
                src.set(ValueLayout.JAVA_BYTE, 1, (byte) 0xFF);
                MemorySegment out = arena.allocate(16);
                assertThatThrownBy(() -> Huffman.decode(src, 0, 2, out))
                        .isInstanceOf(Huffman.HuffmanDecodingException.class)
                        .hasMessageContaining("padding");
            }
        }

        /**
         * RFC §5.2: "A padding not corresponding to the most significant bits of the code
         * for the EOS symbol MUST be treated as a decoding error."
         *
         * <p>The EOS code is 0x3FFFFFFF (30 bits, all ones). Valid padding must be a
         * suffix of all-1-bits (MSBs of EOS). A zero-padded incomplete symbol is invalid.
         */
        @Test
        @DisplayName("RFC §5.2 — non-EOS padding throws HuffmanDecodingException")
        void nonEosPaddingRejected() {
            try (Arena arena = Arena.ofConfined()) {
                // Write a valid 'e' symbol (00101, 5 bits) then pad with 0x00 (zero-bits).
                // 0x28 = 0010_1000 — 'e' in high 5 bits, low 3 bits are 000 (not EOS-aligned).
                MemorySegment src = arena.allocate(1);
                src.set(ValueLayout.JAVA_BYTE, 0, (byte) 0x28);
                MemorySegment out = arena.allocate(4);
                assertThatThrownBy(() -> Huffman.decode(src, 0, 1, out))
                        .isInstanceOf(Huffman.HuffmanDecodingException.class);
            }
        }

        /**
         * RFC §5.2: "A Huffman-encoded string literal containing the EOS symbol MUST be
         * treated as a decoding error."
         *
         * EOS code = 0x3FFFFFFF (30 bits, all-ones prefix 30). We encode EOS directly
         * by writing 30 one-bits followed by 2 padding one-bits = 0xFF 0xFF 0xFF 0xFF
         * but that's actually valid padding. Instead we need to have a symbol before EOS,
         * then EOS inline. Use ' ' (0x14, 6 bits) + EOS (30 bits) = 36 bits = 5 bytes.
         * ' ' = 010100, EOS = 111111111111111111111111111111 (30 ones).
         * bits: 010100 11 | 1111 1111 | 1111 1111 | 1111 1111 | 1111 1111
         *     = 0x53     | 0xFF      | 0xFF      | 0xFF      | 0xFF
         * But the last 2 bits are padding. Actually EOS is 30 bits so after ' '(6) we have
         * 36 bits = 4.5 bytes → 5 bytes with 4 padding bits.
         * 0101_0011 1111_1111 1111_1111 1111_1111 1111_1111 → 0x53 FF FF FF FF
         * Last nibble 0xF is pure 1-padding (4 bits ≤ 7: valid length, but EOS is present).
         */
        @Test
        @DisplayName("RFC §5.2 — EOS symbol in stream throws HuffmanDecodingException")
        void eosInStreamRejected() {
            try (Arena arena = Arena.ofConfined()) {
                byte[] data = {0x53, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
                MemorySegment src = arena.allocate(data.length);
                MemorySegment.copy(MemorySegment.ofArray(data), ValueLayout.JAVA_BYTE, 0,
                        src, ValueLayout.JAVA_BYTE, 0, data.length);
                MemorySegment out = arena.allocate(16);
                assertThatThrownBy(() -> Huffman.decode(src, 0, data.length, out))
                        .isInstanceOf(Huffman.HuffmanDecodingException.class)
                        .hasMessageContaining("EOS");
            }
        }
    }

    // =========================================================================
    // RFC 7541 Appendix B / C reference byte vectors
    // =========================================================================

    @Nested
    @DisplayName("RFC 7541 reference vectors — exact wire bytes")
    class RfcReferenceVectors {

        /**
         * RFC 7541 Appendix C.4.1: Huffman("www.example.com").
         * <pre>f1 e3 c2 e5 f2 3a 6b a0 ab 90 f4 ff  (12 bytes)</pre>
         */
        @Test
        @DisplayName("encode('www.example.com') == RFC C.4.1 bytes")
        void encodeWwwExampleCom() {
            byte[] expected = {
                (byte) 0xf1, (byte) 0xe3, (byte) 0xc2, (byte) 0xe5,
                (byte) 0xf2, 0x3a, 0x6b, (byte) 0xa0,
                (byte) 0xab, (byte) 0x90, (byte) 0xf4, (byte) 0xff
            };
            assertEncode("www.example.com", expected);
        }

        /**
         * RFC 7541 Appendix C.4.2: Huffman("no-cache").
         * <pre>a8 eb 10 64 9c bf  (6 bytes)</pre>
         */
        @Test
        @DisplayName("encode('no-cache') == RFC C.4.2 bytes")
        void encodeNoCache() {
            byte[] expected = {
                (byte) 0xa8, (byte) 0xeb, 0x10, 0x64, (byte) 0x9c, (byte) 0xbf
            };
            assertEncode("no-cache", expected);
        }

        /**
         * RFC 7541 Appendix C.4.3: Huffman("custom-key").
         * <pre>25 a8 49 e9 5b a9 7d 7f  (8 bytes)</pre>
         */
        @Test
        @DisplayName("encode('custom-key') == RFC C.4.3 bytes")
        void encodeCustomKey() {
            byte[] expected = {
                0x25, (byte) 0xa8, 0x49, (byte) 0xe9, 0x5b, (byte) 0xa9, 0x7d, 0x7f
            };
            assertEncode("custom-key", expected);
        }

        /**
         * RFC 7541 Appendix C.4.3: Huffman("custom-value").
         * <pre>25 a8 49 e9 5b b8 e8 b4 bf  (9 bytes)</pre>
         */
        @Test
        @DisplayName("encode('custom-value') == RFC C.4.3 bytes")
        void encodeCustomValue() {
            byte[] expected = {
                0x25, (byte) 0xa8, 0x49, (byte) 0xe9, 0x5b,
                (byte) 0xb8, (byte) 0xe8, (byte) 0xb4, (byte) 0xbf
            };
            assertEncode("custom-value", expected);
        }

        /**
         * RFC 7541 Appendix C.6.1: Huffman("302").
         * <pre>64 02  (2 bytes)</pre>
         */
        @Test
        @DisplayName("encode('302') == RFC C.6.1 bytes")
        void encode302() {
            byte[] expected = {0x64, 0x02};
            assertEncode("302", expected);
        }

        /**
         * RFC 7541 Appendix C.6.1: Huffman("private").
         * <pre>ae c3 77 1a 4b  (5 bytes)</pre>
         */
        @Test
        @DisplayName("encode('private') == RFC C.6.1 bytes")
        void encodePrivate() {
            byte[] expected = {(byte) 0xae, (byte) 0xc3, 0x77, 0x1a, 0x4b};
            assertEncode("private", expected);
        }

        /**
         * RFC 7541 Appendix C.6.2: Huffman("Mon, 21 Oct 2013 20:13:21 GMT").
         * <pre>d0 7a be 94 10 54 d4 44 a8 20 05 95 04 0b 81 66 e0 82 a6 2d 1b ff</pre>
         */
        @Test
        @DisplayName("encode('Mon, 21 Oct 2013 20:13:21 GMT') == RFC C.6.2 bytes")
        void encodeDateHeader() {
            byte[] expected = {
                (byte) 0xd0, 0x7a, (byte) 0xbe, (byte) 0x94, 0x10,
                0x54, (byte) 0xd4, 0x44, (byte) 0xa8, 0x20,
                0x05, (byte) 0x95, 0x04, 0x0b, (byte) 0x81,
                0x66, (byte) 0xe0, (byte) 0x82, (byte) 0xa6, 0x2d,
                0x1b, (byte) 0xff
            };
            assertEncode("Mon, 21 Oct 2013 20:13:21 GMT", expected);
        }

        /**
         * Decode direction: RFC C.4.1 Huffman bytes → "www.example.com".
         */
        @Test
        @DisplayName("decode(RFC C.4.1 bytes) == 'www.example.com'")
        void decodeWwwExampleCom() {
            byte[] huffBytes = {
                (byte) 0xf1, (byte) 0xe3, (byte) 0xc2, (byte) 0xe5,
                (byte) 0xf2, 0x3a, 0x6b, (byte) 0xa0,
                (byte) 0xab, (byte) 0x90, (byte) 0xf4, (byte) 0xff
            };
            assertDecode(huffBytes, "www.example.com");
        }

        /**
         * Decode direction: RFC C.4.3 "custom-key" bytes → "custom-key".
         */
        @Test
        @DisplayName("decode(RFC C.4.3 custom-key bytes) == 'custom-key'")
        void decodeCustomKey() {
            byte[] huffBytes = {
                0x25, (byte) 0xa8, 0x49, (byte) 0xe9, 0x5b, (byte) 0xa9, 0x7d, 0x7f
            };
            assertDecode(huffBytes, "custom-key");
        }

        // =========================================================================
        // Helpers
        // =========================================================================

        private void assertEncode(String input, byte[] expected) {
            byte[] raw = input.getBytes(StandardCharsets.UTF_8);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment src = toSegment(arena, raw);
                MemorySegment dst = arena.allocate(expected.length + 8);
                long len = Huffman.encode(src, dst);
                assertThat(len).isEqualTo(expected.length);
                assertThat(toBytes(dst, len)).isEqualTo(expected);
            }
        }

        private void assertDecode(byte[] huffBytes, String expected) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment src = toSegment(arena, huffBytes);
                MemorySegment dst = arena.allocate(expected.length() * 2 + 8);
                int len = Huffman.decode(src, 0, huffBytes.length, dst);
                byte[] result = new byte[len];
                MemorySegment.copy(dst, ValueLayout.JAVA_BYTE, 0, result, 0, len);
                assertThat(new String(result, StandardCharsets.UTF_8)).isEqualTo(expected);
            }
        }

        private MemorySegment toSegment(Arena arena, byte[] data) {
            MemorySegment seg = arena.allocate(data.length);
            MemorySegment.copy(MemorySegment.ofArray(data), ValueLayout.JAVA_BYTE, 0,
                    seg, ValueLayout.JAVA_BYTE, 0, data.length);
            return seg;
        }

        private byte[] toBytes(MemorySegment seg, long length) {
            byte[] result = new byte[(int) length];
            MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, 0, result, 0, result.length);
            return result;
        }
    }
}
