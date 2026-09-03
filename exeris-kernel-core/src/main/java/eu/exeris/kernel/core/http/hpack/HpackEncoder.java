/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.hpack;

import eu.exeris.kernel.core.http.hpack.huffman.Huffman;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * RFC 7541 — HPACK Header Block Encoder.
 *
 * <h2>Contract</h2>
 * <p>Encodes header fields into an HPACK-compressed header block. Uses the combined
 * index address space (static + dynamic table) to produce indexed representations
 * when possible, falling back to literal representations otherwise.
 *
 * <h2>Encoding Strategy</h2>
 * <ul>
 *   <li>Full match (name + value) → §6.1 Indexed Header Field</li>
 *   <li>Name match only → §6.2.1 Literal with Incremental Indexing (indexed name)</li>
 *   <li>No match → §6.2.1 Literal with Incremental Indexing (new name)</li>
 *   <li>Sensitive fields → §6.2.3 Literal Never Indexed</li>
 * </ul>
 *
 * <h2>Huffman</h2>
 * <p>String literals are Huffman-encoded when the encoded form is shorter than the
 * raw form (RFC 7541 §5.2).
 *
 * <h2>Thread Safety</h2>
 * <p>Not thread-safe. Each HTTP/2 connection must use its own encoder.
 *
 * @since 0.5.0
 */
public final class HpackEncoder {

    private static final long MIN_DYNAMIC_TABLE_SIZE = 0L;
    private static final long MAX_UINT32 = 0xFFFF_FFFFL;

    private final HpackDynamicTable dynamicTable;
    private final MemoryAllocator allocator;
    private final boolean useHuffman;

    /**
     * Creates an encoder with the given dynamic table.
     *
     * @param dynamicTable dynamic table for this encoding context
     * @param allocator    memory allocator for Huffman scratch buffers
     * @param useHuffman   {@code true} to apply Huffman encoding to string literals
     */
    public HpackEncoder(HpackDynamicTable dynamicTable,
                        MemoryAllocator allocator,
                        boolean useHuffman) {
        this.dynamicTable = dynamicTable;
        this.allocator = allocator;
        this.useHuffman = useHuffman;
    }

    /**
     * Encodes a single header field into the output segment.
     *
     * @param output    destination segment
     * @param pos       byte offset to start writing at
     * @param name      header field name
     * @param value     header field value
     * @param sensitive {@code true} to use Never Indexed representation (§6.2.3)
     * @return new byte position after encoding
     */
    @SuppressWarnings("PMD.AssignmentInOperand")
    public long encodeHeader(MemorySegment output, long pos, String name, String value,
                             boolean sensitive) {
        if (sensitive) {
            return encodeLiteralNeverIndexed(output, pos, name, value);
        }

        int match = dynamicTable.find(name, value);
        boolean fullMatch = (match & 0x10000) != 0;
        int index = match & 0xFFFF;

        if (fullMatch && index > 0) {
            return encodeIndexed(output, pos, index);
        }

        long cursor = pos;
        if (index > 0) {
            cursor = encodeInteger(output, cursor, 0x40, 6, index);
        } else {
            output.set(ValueLayout.JAVA_BYTE, cursor++, (byte) 0x40);
            cursor = encodeString(output, cursor, name);
        }
        cursor = encodeString(output, cursor, value);
        dynamicTable.add(name, value);
        return cursor;
    }

    /**
     * Signals a dynamic table size update (§6.3).
     *
     * @param output  destination segment
     * @param pos     byte offset
     * @param newSize new maximum table size
     * @return new byte position
     */
    public long encodeSizeUpdate(MemorySegment output, long pos, long newSize) {
        if (newSize < MIN_DYNAMIC_TABLE_SIZE) {
            throw new IllegalArgumentException(
                    "HPACK dynamic table size must be non-negative, got: " + newSize);
        }
        if (newSize > MAX_UINT32) {
            throw new IllegalArgumentException(
                    "HPACK dynamic table size must be <= 2^32-1, got: " + newSize);
        }
        dynamicTable.setMaxSize(newSize);
        return encodeInteger(output, pos, 0x20, 5, newSize);
    }

    // =========================================================================
    // Internal encoding
    // =========================================================================

    private static long encodeIndexed(MemorySegment output, long pos, int index) {
        return encodeInteger(output, pos, 0x80, 7, index);
    }

    @SuppressWarnings("PMD.AssignmentInOperand")
    private long encodeLiteralNeverIndexed(MemorySegment output, long pos, String name,
                                           String value) {
        int staticResult = HpackStaticTable.find(name, value);
        int nameIndex = staticResult & 0xFF;

        long cursor = pos;
        if (nameIndex > 0) {
            cursor = encodeInteger(output, cursor, 0x10, 4, nameIndex);
        } else {
            output.set(ValueLayout.JAVA_BYTE, cursor++, (byte) 0x10);
            cursor = encodeString(output, cursor, name);
        }
        cursor = encodeString(output, cursor, value);
        return cursor;
    }

    private long encodeString(MemorySegment output, long pos, String str) {
        int rawLength = HpackUtf8.byteLength(str);
        if (!useHuffman) {
            long cursor = encodeInteger(output, pos, 0x00, 7, rawLength);
            HpackUtf8.writeToSegment(str, output, cursor);
            return cursor + rawLength;
        }

        try (LoanedBuffer scratch = allocator.allocateNetwork(rawLength * 2 + 8)) {
            MemorySegment scratchSeg = scratch.segment();
            MemorySegment rawSeg = scratchSeg.asSlice(0, rawLength);
            HpackUtf8.writeToSegment(str, rawSeg, 0);
            long huffLen = Huffman.encodedLength(rawSeg);

            if (huffLen < rawLength) {
                MemorySegment huffBuf = scratchSeg.asSlice(rawLength, huffLen + 8);
                long actualLen = Huffman.encode(rawSeg, huffBuf);
                long cursor = encodeInteger(output, pos, 0x80, 7, actualLen);
                MemorySegment.copy(huffBuf, ValueLayout.JAVA_BYTE, 0,
                        output, ValueLayout.JAVA_BYTE, cursor, actualLen);
                return cursor + actualLen;
            }

            long cursor = encodeInteger(output, pos, 0x00, 7, rawLength);
            MemorySegment.copy(rawSeg, ValueLayout.JAVA_BYTE, 0,
                    output, ValueLayout.JAVA_BYTE, cursor, rawLength);
            return cursor + rawLength;
        }
    }

    /**
     * RFC 7541 §5.1 — Integer encoding.
     */
    @SuppressWarnings("PMD.AssignmentInOperand")
    private static long encodeInteger(MemorySegment output, long pos, int prefix, int prefixBits,
                                      long value) {
        int mask = (1 << prefixBits) - 1;
        if (value < mask) {
            output.set(ValueLayout.JAVA_BYTE, pos, (byte) (prefix | (int) value));
            return pos + 1;
        }
        long cursor = pos;
        output.set(ValueLayout.JAVA_BYTE, cursor++, (byte) (prefix | mask));
        long remaining = value - mask;
        while (remaining >= 128) {
            output.set(ValueLayout.JAVA_BYTE, cursor++, (byte) (((int) remaining & 0x7F) | 0x80));
            remaining >>>= 7;
        }
        output.set(ValueLayout.JAVA_BYTE, cursor++, (byte) (int) remaining);
        return cursor;
    }
}
