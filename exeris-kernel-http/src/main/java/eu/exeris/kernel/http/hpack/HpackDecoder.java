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
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
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

    /** Transient decode position — valid only within a {@link #decode} invocation. */
    private long decodePos;

    /** Transient header-list size accumulator — valid only within a {@link #decode} invocation. */
    private long decodeHeaderListSize;

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
        this.decodePos = offset;
        this.decodeHeaderListSize = 0;
        boolean sizeUpdateAllowed = true;

        while (decodePos < end) {
            int firstByte = block.get(ValueLayout.JAVA_BYTE, decodePos) & 0xFF;

            if ((firstByte & INDEXED_MASK) == INDEXED_MASK) {
                sizeUpdateAllowed = false;
                decodeIndexed(block, end, listener);
            } else if ((firstByte & LITERAL_INCREMENTAL_MASK) == LITERAL_INCREMENTAL_PATTERN) {
                sizeUpdateAllowed = false;
                decodeLiteralIncremental(block, end, listener);
            } else if ((firstByte & LITERAL_NO_INDEX_MASK) == LITERAL_NEVER_INDEXED_PATTERN) {
                sizeUpdateAllowed = false;
                decodeLiteralNeverIndexed(block, end, listener);
            } else if ((firstByte & SIZE_UPDATE_MASK) == SIZE_UPDATE_PATTERN) {
                if (!sizeUpdateAllowed) {
                    throw new HpackDecodingException(
                            "HPACK: dynamic table size update after header field representation");
                }
                decodeSizeUpdate(block, end);
            } else if ((firstByte & LITERAL_NO_INDEX_MASK) == 0) {
                sizeUpdateAllowed = false;
                decodeLiteralNoIndex(block, end, listener);
            } else {
                throw new HpackDecodingException(
                        "HPACK: unknown header field representation");
            }
        }
    }

    // =========================================================================
    // Representation decoders
    // =========================================================================

    private void decodeIndexed(MemorySegment block, long end, HeaderListener listener) {
        int index = readInteger(block, end, 7);
        if (index == 0) {
            throw new HpackDecodingException("HPACK: indexed field with index 0");
        }
        String name = lookupName(index);
        String value = lookupValue(index);
        checkHeaderListSize(name, value);
        listener.onHeader(name, value, false);
    }

    private void decodeLiteralIncremental(MemorySegment block, long end, HeaderListener listener) {
        int nameIndex = readInteger(block, end, 6);
        String name = resolveName(block, end, nameIndex);
        String value = readStringLiteral(block, end);
        dynamicTable.add(name, value);
        checkHeaderListSize(name, value);
        listener.onHeader(name, value, false);
    }

    private void decodeLiteralNoIndex(MemorySegment block, long end, HeaderListener listener) {
        int nameIndex = readInteger(block, end, 4);
        String name = resolveName(block, end, nameIndex);
        String value = readStringLiteral(block, end);
        checkHeaderListSize(name, value);
        listener.onHeader(name, value, false);
    }

    private void decodeLiteralNeverIndexed(MemorySegment block, long end, HeaderListener listener) {
        int nameIndex = readInteger(block, end, 4);
        String name = resolveName(block, end, nameIndex);
        String value = readStringLiteral(block, end);
        checkHeaderListSize(name, value);
        listener.onHeader(name, value, true);
    }

    private void decodeSizeUpdate(MemorySegment block, long end) {
        int newMaxSize = readInteger(block, end, 5);
        if (newMaxSize > protocolMaxTableSize) {
            throw new HpackDecodingException(
                    "HPACK: dynamic table size update (" + newMaxSize
                            + ") exceeds protocol limit (" + protocolMaxTableSize + ")");
        }
        dynamicTable.setMaxSize(newMaxSize);
    }

    private String resolveName(MemorySegment block, long end, int nameIndex) {
        if (nameIndex == 0) {
            return readStringLiteral(block, end);
        }
        return lookupName(nameIndex);
    }

    // =========================================================================
    // Integer decoding — RFC 7541 §5.1
    // =========================================================================

    private int readInteger(MemorySegment seg, long end, int prefixBits) {
        if (decodePos >= end) {
            throw new HpackDecodingException(
                    "HPACK: unexpected end of block in integer");
        }
        int mask = (1 << prefixBits) - 1;
        long value = seg.get(ValueLayout.JAVA_BYTE, decodePos) & 0xFF & mask;
        decodePos++;

        if (value < mask) {
            return (int) value;
        }

        int shift = 0;
        while (decodePos < end) {
            int octet = seg.get(ValueLayout.JAVA_BYTE, decodePos) & 0xFF;
            decodePos++;
            value += (long) (octet & 0x7F) << shift;
            shift += 7;
            if (shift > MAX_INTEGER_SHIFT) {
                throw new HpackDecodingException("HPACK: integer overflow");
            }
            if ((octet & 0x80) == 0) {
                if (value > Integer.MAX_VALUE) {
                    throw new HpackDecodingException("HPACK: integer overflow");
                }
                return (int) value;
            }
        }
        throw new HpackDecodingException(
                "HPACK: unexpected end of block in integer continuation");
    }

    // =========================================================================
    // String literal decoding — RFC 7541 §5.2
    // =========================================================================

    private String readStringLiteral(MemorySegment seg, long end) {
        if (decodePos >= end) {
            throw new HpackDecodingException(
                    "HPACK: unexpected end of block in string");
        }
        int firstByte = seg.get(ValueLayout.JAVA_BYTE, decodePos) & 0xFF;
        boolean huffmanEncoded = (firstByte & 0x80) != 0;

        int strLen = readInteger(seg, end, 7);

        if (strLen > MAX_STRING_LITERAL) {
            throw new HpackDecodingException(
                    "HPACK: string literal too long: " + strLen);
        }

        long strStart = decodePos;

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

        decodePos = strStart + strLen;
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
        } catch (Huffman.HuffmanDecodingException cause) {
            throw new HpackDecodingException(
                    "HPACK: invalid Huffman-encoded string literal", cause);
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

    private void checkHeaderListSize(String name, String value) {
        decodeHeaderListSize += utf8ByteLength(name) + utf8ByteLength(value) + 32;
        if (decodeHeaderListSize > maxHeaderListSize) {
            throw new HpackDecodingException(
                    "HPACK: header list size exceeds limit ("
                            + decodeHeaderListSize + " > " + maxHeaderListSize + ")");
        }
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

        private static final String ERROR_CODE = KernelErrorCodes.EX_HTTP_4002;

        public HpackDecodingException(String message) {
            super(ERROR_CODE, message, message);
        }

        public HpackDecodingException(String message, Throwable cause) {
            super(ERROR_CODE, message, cause, message);
        }
    }
}
