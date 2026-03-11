/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.http.hpack;

import eu.exeris.kernel.http.hpack.huffman.Huffman;
import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/**
 * RFC 7541 §3 — HPACK Header Block Decoder.
 *
 * <h2>Contract</h2>
 * <p>Processes a header block sequentially, yielding header field name-value pairs
 * to a {@link HeaderListener}. Maintains the decoding context (dynamic table)
 * across header blocks within the same connection.
 *
 * <h2>Thread Safety</h2>
 * <p>Not thread-safe. Each HTTP/2 connection must use its own decoder instance
 * (RFC 7541 §2.2 — encoding and decoding contexts are independent).
 *
 * <h2>Memory</h2>
 * <p>Decoding operates on a caller-provided {@link MemorySegment}. Huffman
 * decoding uses a scratch {@link LoanedBuffer} obtained from the
 * {@link MemoryAllocator} injected via the constructor — respecting the
 * tier-specific pooling contract (Community heap-pool or Enterprise slab).
 *
 * @since 0.5.0
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7541#section-3">RFC 7541 §3</a>
 */
@SuppressWarnings({"PMD.TooManyMethods", "PMD.CyclomaticComplexity"})
public final class HpackDecoder {

    private static final int MAX_STRING_LITERAL = 65_536;
    private static final int MAX_INTEGER_SHIFT = 28;

    private static final int INDEXED_MASK = 0x80;
    private static final int LITERAL_INCREMENTAL_MASK = 0xC0;
    private static final int LITERAL_INCREMENTAL_PATTERN = 0x40;
    private static final int LITERAL_NO_INDEX_MASK = 0xF0;
    private static final int LITERAL_NEVER_INDEXED_PATTERN = 0x10;
    private static final int SIZE_UPDATE_MASK = 0xE0;
    private static final int SIZE_UPDATE_PATTERN = 0x20;

    private static final int UTF8_1BYTE_MAX = 0x007F;
    private static final int UTF8_2BYTE_MAX = 0x07FF;
    private static final int UTF8_3BYTE_MAX = 0xFFFF;

    private final HpackDynamicTable dynamicTable;
    private final MemoryAllocator allocator;
    private final long maxHeaderListSize;
    private long protocolMaxTableSize;

    /**
     * Callback interface for decoded header fields.
     */
    @FunctionalInterface
    public interface HeaderListener {
        /**
         * Called for each decoded header field.
         *
         * @param name       header field name
         * @param value      header field value
         * @param sensitive  {@code true} if the field was marked as never-indexed (§6.2.3)
         */
        void onHeader(String name, String value, boolean sensitive);
    }

    /**
     * Creates a decoder with the given dynamic table and header list size limit.
     *
     * <p>The {@code protocolMaxTableSize} is set to the same value as the initial
     * dynamic table max size. Call {@link #setProtocolMaxTableSize(long)} after a
     * SETTINGS_HEADER_TABLE_SIZE acknowledgement (RFC 7541 §4.2).
     *
     * @param dynamicTable      dynamic table for this decoding context
     * @param allocator         memory allocator for Huffman scratch buffers
     * @param maxHeaderListSize maximum cumulative size of decoded header list (bytes)
     */
    public HpackDecoder(HpackDynamicTable dynamicTable,
                        MemoryAllocator allocator,
                        long maxHeaderListSize) {
        this.dynamicTable = dynamicTable;
        this.allocator = allocator;
        this.maxHeaderListSize = maxHeaderListSize;
        this.protocolMaxTableSize = dynamicTable.maxSize();
    }

    /**
     * Updates the protocol-level maximum dynamic table size limit.
     *
     * <p>Must be called after acknowledging a SETTINGS_HEADER_TABLE_SIZE change
     * from the peer (RFC 7541 §4.2). The next header block decoded after this call
     * MUST begin with a dynamic table size update §6.3 if the peer sends one;
     * the decoder will reject any size update exceeding this limit.
     *
     * @param newProtocolMax new SETTINGS_HEADER_TABLE_SIZE value
     */
    public void setProtocolMaxTableSize(long newProtocolMax) {
        this.protocolMaxTableSize = newProtocolMax;
    }

    /**
     * Decodes a complete header block from the given segment.
     *
     * @param block    segment containing the HPACK-encoded header block
     * @param offset   byte offset into {@code block}
     * @param length   byte length of the header block
     * @param listener callback receiving decoded header fields
     * @throws HpackDecodingException on any decoding error (RFC 7541 §3.1)
     */
    public void decode(MemorySegment block, long offset, long length,
                       HeaderListener listener) {
        long end = offset + length;
        long[] cursor = {offset, 0};
        boolean sizeUpdateAllowed = true;

        while (cursor[0] < end) {
            int firstByte = block.get(ValueLayout.JAVA_BYTE, cursor[0]) & 0xFF;

            if ((firstByte & INDEXED_MASK) == INDEXED_MASK) {
                sizeUpdateAllowed = false;
                decodeIndexed(block, cursor, end, listener);
            } else if ((firstByte & LITERAL_INCREMENTAL_MASK) == LITERAL_INCREMENTAL_PATTERN) {
                sizeUpdateAllowed = false;
                decodeLiteralIncremental(block, cursor, end, listener);
            } else if ((firstByte & LITERAL_NO_INDEX_MASK) == LITERAL_NEVER_INDEXED_PATTERN) {
                sizeUpdateAllowed = false;
                decodeLiteralNeverIndexed(block, cursor, end, listener);
            } else if ((firstByte & SIZE_UPDATE_MASK) == SIZE_UPDATE_PATTERN) {
                if (!sizeUpdateAllowed) {
                    throw new HpackDecodingException(
                            "HPACK: dynamic table size update after header field representation");
                }
                decodeSizeUpdate(block, cursor, end);
            } else if ((firstByte & LITERAL_NO_INDEX_MASK) == 0) {
                sizeUpdateAllowed = false;
                decodeLiteralNoIndex(block, cursor, end, listener);
            } else {
                throw new HpackDecodingException(
                        "HPACK: unknown header field representation");
            }
        }
    }

    // =========================================================================
    // Representation decoders
    // =========================================================================

    private void decodeIndexed(MemorySegment block, long[] cursor,
                               long end, HeaderListener listener) {
        int index = readInteger(block, cursor, end, 7);
        if (index == 0) {
            throw new HpackDecodingException("HPACK: indexed field with index 0");
        }
        String name = lookupName(index);
        String value = lookupValue(index);
        cursor[1] = checkHeaderListSize(cursor[1], name, value);
        listener.onHeader(name, value, false);
    }

    private void decodeLiteralIncremental(MemorySegment block, long[] cursor,
                                          long end, HeaderListener listener) {
        int nameIndex = readInteger(block, cursor, end, 6);
        String name = resolveName(block, cursor, end, nameIndex);
        String value = readStringLiteral(block, cursor, end);
        dynamicTable.add(name, value);
        cursor[1] = checkHeaderListSize(cursor[1], name, value);
        listener.onHeader(name, value, false);
    }

    private void decodeLiteralNoIndex(MemorySegment block, long[] cursor,
                                      long end, HeaderListener listener) {
        int nameIndex = readInteger(block, cursor, end, 4);
        String name = resolveName(block, cursor, end, nameIndex);
        String value = readStringLiteral(block, cursor, end);
        cursor[1] = checkHeaderListSize(cursor[1], name, value);
        listener.onHeader(name, value, false);
    }

    private void decodeLiteralNeverIndexed(MemorySegment block, long[] cursor,
                                           long end, HeaderListener listener) {
        int nameIndex = readInteger(block, cursor, end, 4);
        String name = resolveName(block, cursor, end, nameIndex);
        String value = readStringLiteral(block, cursor, end);
        cursor[1] = checkHeaderListSize(cursor[1], name, value);
        listener.onHeader(name, value, true);
    }

    private void decodeSizeUpdate(MemorySegment block, long[] cursor, long end) {
        int newMaxSize = readInteger(block, cursor, end, 5);
        if (newMaxSize > protocolMaxTableSize) {
            throw new HpackDecodingException(
                    "HPACK: dynamic table size update (" + newMaxSize
                            + ") exceeds protocol limit (" + protocolMaxTableSize + ")");
        }
        dynamicTable.setMaxSize(newMaxSize);
    }

    private String resolveName(MemorySegment block, long[] cursor,
                               long end, int nameIndex) {
        if (nameIndex == 0) {
            return readStringLiteral(block, cursor, end);
        }
        return lookupName(nameIndex);
    }

    // =========================================================================
    // Integer decoding — RFC 7541 §5.1
    // =========================================================================

    private static int readInteger(MemorySegment seg, long[] cursor, long end,
                                   int prefixBits) {
        long pos = cursor[0];
        if (pos >= end) {
            throw new HpackDecodingException(
                    "HPACK: unexpected end of block in integer");
        }
        int mask = (1 << prefixBits) - 1;
        int value = seg.get(ValueLayout.JAVA_BYTE, pos) & 0xFF & mask;
        pos++;

        if (value < mask) {
            cursor[0] = pos;
            return value;
        }

        int shift = 0;
        while (pos < end) {
            int octet = seg.get(ValueLayout.JAVA_BYTE, pos) & 0xFF;
            pos++;
            value += (octet & 0x7F) << shift;
            shift += 7;
            if (shift > MAX_INTEGER_SHIFT) {
                throw new HpackDecodingException("HPACK: integer overflow");
            }
            if ((octet & 0x80) == 0) {
                cursor[0] = pos;
                return value;
            }
        }
        throw new HpackDecodingException(
                "HPACK: unexpected end of block in integer continuation");
    }

    // =========================================================================
    // String literal decoding — RFC 7541 §5.2
    // =========================================================================

    private String readStringLiteral(MemorySegment seg, long[] cursor, long end) {
        long pos = cursor[0];
        if (pos >= end) {
            throw new HpackDecodingException(
                    "HPACK: unexpected end of block in string");
        }
        int firstByte = seg.get(ValueLayout.JAVA_BYTE, pos) & 0xFF;
        boolean huffmanEncoded = (firstByte & 0x80) != 0;

        int strLen = readInteger(seg, cursor, end, 7);

        if (strLen > MAX_STRING_LITERAL) {
            throw new HpackDecodingException(
                    "HPACK: string literal too long: " + strLen);
        }

        long strStart = cursor[0];

        if (strStart + strLen > end) {
            throw new HpackDecodingException(
                    "HPACK: string literal exceeds block boundary");
        }

        String value;
        if (huffmanEncoded) {
            value = decodeHuffmanString(seg, strStart, strLen);
        } else {
            byte[] bytes = new byte[strLen];
            MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, strStart,
                    bytes, 0, strLen);
            value = new String(bytes, StandardCharsets.UTF_8);
        }

        cursor[0] = strStart + strLen;
        return value;
    }

    private String decodeHuffmanString(MemorySegment seg, long strStart, int strLen) {
        try (LoanedBuffer scratch = allocator.allocateNetwork(strLen * 2)) {
            MemorySegment decoded = scratch.segment();
            int decodedLen = Huffman.decode(seg, strStart, strLen, decoded);
            byte[] bytes = new byte[decodedLen];
            MemorySegment.copy(decoded, ValueLayout.JAVA_BYTE, 0,
                    bytes, 0, decodedLen);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    // =========================================================================
    // Table lookup
    // =========================================================================

    private String lookupName(int index) {
        return lookupEntry(index, true);
    }

    private String lookupValue(int index) {
        return lookupEntry(index, false);
    }

    private String lookupEntry(int index, boolean nameOnly) {
        if (index <= HpackStaticTable.SIZE) {
            return nameOnly ? HpackStaticTable.getName(index) : HpackStaticTable.getValue(index);
        }
        int dynIndex = index - HpackStaticTable.SIZE - 1;
        if (dynIndex >= dynamicTable.size()) {
            throw new HpackDecodingException("HPACK: invalid index " + index);
        }
        return nameOnly ? dynamicTable.getName(dynIndex) : dynamicTable.getValue(dynIndex);
    }

    private long checkHeaderListSize(long current, String name, String value) {
        long size = current + utf8ByteLength(name) + utf8ByteLength(value) + 32;
        if (size > maxHeaderListSize) {
            throw new HpackDecodingException(
                    "HPACK: header list size exceeds limit ("
                            + size + " > " + maxHeaderListSize + ")");
        }
        return size;
    }

    private static int utf8ByteLength(String str) {
        int count = 0;
        final int len = str.length();
        int idx = 0;
        while (idx < len) {
            int codePoint = str.codePointAt(idx);
            if (codePoint <= UTF8_1BYTE_MAX) {
                count++;
            } else if (codePoint <= UTF8_2BYTE_MAX) {
                count += 2;
            } else if (codePoint <= UTF8_3BYTE_MAX) {
                count += 3;
            } else {
                count += 4;
            }
            idx += Character.charCount(codePoint);
        }
        return count;
    }

    /**
     * Unchecked exception for HPACK decoding errors (RFC 7541 §3 violations).
     *
     * @since 0.5.0
     */
    public static final class HpackDecodingException extends ExerisKernelException {

        private static final String ERROR_CODE = "EX-HTTP-4002";

        public HpackDecodingException(String message) {
            super(ERROR_CODE, message, message);
        }
    }
}
