/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.http.hpack.huffman;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * RFC 7541 Appendix B — Huffman encoder and decoder operating directly on
 * {@link MemorySegment} buffers.
 *
 * <h2>Hot-Path Contract</h2>
 * <p>{@link #decode} uses {@link HuffmanTable#getEntry(int, int)} directly —
 * each nibble lookup returns a packed {@code int}, zero heap allocation per step.
 *
 * <h2>Encoding</h2>
 * <p>{@link #encode} writes Huffman-coded bits into the output segment with
 * RFC 7541 §5.2 EOS-padded alignment.
 *
 * @since 0.5.0
 */
@SuppressWarnings({
        "PMD.CyclomaticComplexity",
        "PMD.CognitiveComplexity",
        "PMD.AssignmentInOperand",
        "PMD.ConfusingTernary"
})
public final class Huffman {

    private static final int EOS_SYMBOL = 256;
    private static final int EMIT_FLAG = 0x8000;
    private static final int SYMBOL_MASK = 0x1FF;
    private static final int STATE_SHIFT = 16;
    private static final int STATE_MASK = 0xFFFF;
    private static final int MAX_PADDING_BITS = 7;

    private Huffman() {
    }

    /**
     * Decodes Huffman-encoded bytes from {@code input} into {@code output}.
     *
     * @param input       source segment containing Huffman-encoded data
     * @param inputOffset byte offset into {@code input}
     * @param inputLength number of bytes to decode
     * @param output      destination segment for decoded octets
     * @return number of bytes written to {@code output}
     * @throws HuffmanDecodingException on EOS in data stream, output overflow, or invalid padding
     */
    @SuppressWarnings({"PMD.CyclomaticComplexity", "java:S3776"}) // Complexity justified by direct table-driven decode
    public static int decode(MemorySegment input, long inputOffset, long inputLength,
                             MemorySegment output) {
        if (inputLength < 0) {
            throw new HuffmanDecodingException(
                    "Huffman decode: negative input length: " + inputLength);
        }
        int state = 0;
        int outPos = 0;
        final long maxOut = output.byteSize();
        int paddingBits = 0;

        long remaining = inputLength;
        long idx = 0;
        while (remaining > 0) {
            remaining--;
            int octet = input.get(ValueLayout.JAVA_BYTE, inputOffset + idx) & 0xFF;
            idx++;

            for (int shift = 4; shift >= 0; shift -= 4) {
                int nibble = (octet >> shift) & 0x0F;
                int entry = HuffmanTable.getEntry(state, nibble);

                if ((entry & EMIT_FLAG) != 0) {
                    int symbol = entry & SYMBOL_MASK;
                    if (symbol == EOS_SYMBOL) {
                        throw new HuffmanDecodingException("Huffman decode: EOS found in data");
                    }
                    if (outPos >= maxOut) {
                        throw new HuffmanDecodingException("Huffman decode: output buffer overflow");
                    }
                    output.set(ValueLayout.JAVA_BYTE, outPos++, (byte) symbol);
                    state = entry >>> STATE_SHIFT;
                    paddingBits = 0;
                } else {
                    state = entry & STATE_MASK;
                    paddingBits += 4;
                }
            }
        }

        validatePadding(state, paddingBits);
        return outPos;
    }

    /**
     * Encodes the contents of {@code input} into Huffman-coded bits in {@code output}.
     *
     * @param input  source segment containing raw octets
     * @param output destination segment (must be large enough for worst-case expansion)
     * @return number of bytes written to {@code output} (byte-aligned, EOS-padded)
     */
    public static long encode(MemorySegment input, MemorySegment output) {
        long bitPos = 0;
        for (long byteIdx = 0; byteIdx < input.byteSize(); byteIdx++) {
            int sym = input.get(ValueLayout.JAVA_BYTE, byteIdx) & 0xFF;
            long code = HuffmanTable.getHuffmanCode(sym);
            int len = HuffmanTable.getHuffmanCodeLength(sym);
            writeBits(output, bitPos, code, len);
            bitPos += len;
        }
        int remBits = (int) (bitPos % 8);
        if (remBits != 0) {
            int padding = 8 - remBits;
            writeBits(output, bitPos, (1L << padding) - 1, padding);
            bitPos += padding;
        }
        return (bitPos + 7) / 8;
    }

    /**
     * Returns the encoded byte length of the given raw octets under Huffman coding.
     *
     * @param input source segment
     * @return encoded length in bytes (including EOS padding)
     */
    public static long encodedLength(MemorySegment input) {
        long totalBits = 0;
        for (long byteIdx = 0; byteIdx < input.byteSize(); byteIdx++) {
            int sym = input.get(ValueLayout.JAVA_BYTE, byteIdx) & 0xFF;
            totalBits += HuffmanTable.getHuffmanCodeLength(sym);
        }
        return (totalBits + 7) / 8;
    }

    // =========================================================================
    // Internal
    // =========================================================================

    private static void validatePadding(int state, int paddingBits) {
        if (paddingBits > MAX_PADDING_BITS) {
            throw new HuffmanDecodingException(
                    "Huffman decode: padding exceeds 7 bits (" + paddingBits + ")");
        }
        if (state == 0) {
            return;
        }
        int tempState = state;
        for (int step = 0; step < 8; step++) {
            int entry = HuffmanTable.getEntry(tempState, 0xF);
            if ((entry & EMIT_FLAG) != 0) {
                int symbol = entry & SYMBOL_MASK;
                if (symbol == EOS_SYMBOL) {
                    return;
                }
                throw new HuffmanDecodingException(
                        "Invalid Huffman padding: decodes to symbol " + symbol);
            }
            tempState = entry & STATE_MASK;
        }
        throw new HuffmanDecodingException("Invalid Huffman padding: does not lead to EOS");
    }

    private static void writeBits(MemorySegment out, long bitPos, long code, int len) {
        if (len < 0) {
            throw new HuffmanDecodingException("Invalid Huffman code length: " + len);
        }
        long totalBits = bitPos + len;
        long capacityBits = out.byteSize() * 8L;
        if (totalBits < 0 || totalBits > capacityBits) {
            throw new HuffmanDecodingException(
                    "Huffman encode overflow: bitPos=" + bitPos + ", len=" + len
                            + ", capacityBits=" + capacityBits);
        }
        int bitIdx = 0;
        while (bitIdx < len) {
            long absoluteBit = bitPos + bitIdx;
            long byteIndex = absoluteBit >> 3;
            int bitInByte = 7 - (int) (absoluteBit & 7);
            byte current = out.get(ValueLayout.JAVA_BYTE, byteIndex);
            if (((code >> (len - 1 - bitIdx)) & 1) != 0) {
                current |= (byte) (1 << bitInByte);
            } else {
                current &= (byte) ~(1 << bitInByte);
            }
            out.set(ValueLayout.JAVA_BYTE, byteIndex, current);
            bitIdx++;
        }
    }

    /**
     * Unchecked exception for Huffman decoding failures (RFC 7541 §5.2 violations).
     *
     * <p>Extends {@link ExerisKernelException} for consistency with the kernel exception
     * hierarchy. Not a checked exception — per Performance Contract, hot-path failures
     * use unchecked exceptions with pre-defined error codes.
     *
     * @since 0.5.0
     */
    public static final class HuffmanDecodingException extends ExerisKernelException {

        private static final String ERROR_CODE = "EX-HTTP-4001";

        public HuffmanDecodingException(String message) {
            super(ERROR_CODE, message, message);
        }
    }
}
