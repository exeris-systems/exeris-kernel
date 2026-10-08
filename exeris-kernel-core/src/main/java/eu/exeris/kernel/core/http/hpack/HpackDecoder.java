/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.hpack;

import eu.exeris.kernel.core.http.hpack.huffman.Huffman;
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
 * @since 0.5
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7541#section-3">RFC 7541 §3</a>
 */
@SuppressWarnings({"PMD.TooManyMethods", "PMD.CyclomaticComplexity"})
public final class HpackDecoder {

    private static final int MAX_INTEGER_SHIFT = 28;
    private static final long MAX_UINT32 = 0xFFFF_FFFFL;

    private static final int INDEXED_MASK = 0x80;
    private static final int LITERAL_INCREMENTAL_MASK = 0xC0;
    private static final int LITERAL_INCREMENTAL_PATTERN = 0x40;
    private static final int LITERAL_NO_INDEX_MASK = 0xF0;
    private static final int LITERAL_NEVER_INDEXED_PATTERN = 0x10;
    private static final int SIZE_UPDATE_MASK = 0xE0;
    private static final int SIZE_UPDATE_PATTERN = 0x20;
    private static final String MSG_SIZE_UPDATE_EXCEEDS_PROTOCOL_LIMIT =
            "HPACK: dynamic table size update exceeds protocol limit";
    private static final String MSG_STRING_LITERAL_TOO_LONG = "HPACK: string literal too long";
    private static final String MSG_INVALID_INDEX = "HPACK: invalid index";
    private static final String MSG_HEADER_LIST_SIZE_EXCEEDS_LIMIT =
            "HPACK: header list size exceeds limit";

    private static final int UTF8_1BYTE_MAX = 0x007F;
    private static final int UTF8_2BYTE_MAX = 0x07FF;
    private static final int UTF8_3BYTE_MAX = 0xFFFF;

    private final HpackDynamicTable dynamicTable;
    private final MemoryAllocator allocator;
    private final long maxHeaderListSize;
    private final int maxStringLiteralSize;
    private long protocolMaxTableSize;

    /** Transient decode position — valid only within a {@link #decode} invocation. */
    private long decodePos;

    /** Transient header-list size accumulator — valid only within a {@link #decode} invocation. */
    private long decodeHeaderListSize;

    /**
     * Set once a size bound has been exceeded in the current {@link #decode} invocation. From then
     * on the block is read to its end for its effect on the dynamic table only: no field reaches
     * the listener and no literal that the table does not need is materialised.
     */
    private boolean limitExceeded;

    /** First bound exceeded in the current invocation, kept for the exception raised at the end. */
    private String limitMessage;
    private long limitObserved;
    private long limitBound;

    /**
     * Callback interface for decoded header fields.
     */
    @FunctionalInterface
    public interface HeaderListener {
        /**
         * Receives one header field, invoked once per field in the order the fields appear
         * in the header block.
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
     * <p>The two bounds are different quantities and neither substitutes for the other.
     * {@code maxHeaderListSize} is cumulative over the whole decoded field section — it is what
     * RFC 9113 §6.5.2 defines SETTINGS_MAX_HEADER_LIST_SIZE against, so it is the one a server
     * advertises. {@code maxStringLiteralSize} bounds a <em>single</em> name or value before its
     * bytes are read, which is what stops one declared length from asking for an allocation the
     * cumulative bound would only notice afterwards.
     *
     * @param dynamicTable         dynamic table for this decoding context
     * @param allocator            memory allocator for Huffman scratch buffers
     * @param maxHeaderListSize    maximum cumulative size of decoded header list (bytes)
     * @param maxStringLiteralSize maximum size of one decoded name or value literal (bytes)
     */
    public HpackDecoder(HpackDynamicTable dynamicTable,
                        MemoryAllocator allocator,
                        long maxHeaderListSize,
                        int maxStringLiteralSize) {
        this.dynamicTable = dynamicTable;
        this.allocator = allocator;
        this.maxHeaderListSize = maxHeaderListSize;
        this.maxStringLiteralSize = maxStringLiteralSize;
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
     * @throws IllegalArgumentException if {@code newProtocolMax} is negative or greater than
     *                                  {@code 2^32-1}
     */
    public void setProtocolMaxTableSize(long newProtocolMax) {
        if (newProtocolMax < 0L || newProtocolMax > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException(
                    "SETTINGS_HEADER_TABLE_SIZE must be in [0, 2^32-1], got: " + newProtocolMax);
        }
        this.protocolMaxTableSize = newProtocolMax;
    }

    /**
     * Decodes a complete header block from the given segment.
     *
     * @param block    segment containing the HPACK-encoded header block
     * @param offset   byte offset into {@code block}
     * @param length   byte length of the header block
     * @param listener callback receiving decoded header fields
     * @throws HpackDecodingException ({@code EX-HTTP-4002}) when the block cannot be decoded;
     *                                {@link HpackDecodingException#kind()} says what that leaves
     *                                behind. Kind {@link HpackDecodingException.Kind#SIZE_LIMIT_EXCEEDED}:
     *                                the block is well formed but exceeded a configured size bound
     *                                (string literal or cumulative header list), and has been read to
     *                                its end first, so every insertion and table-size update has been
     *                                applied, the dynamic table is the one the peer's encoder holds
     *                                and the connection's HPACK state stays usable; fields already
     *                                delivered to {@code listener} are incomplete and must be
     *                                discarded by the caller. Kind
     *                                {@link HpackDecodingException.Kind#MALFORMED}: {@code block} does
     *                                not hold a well-formed header field representation sequence
     *                                (RFC 7541 §3.1, including a malformed or non-decodable string
     *                                literal per §5.2), refers to an invalid table index, or carries
     *                                a dynamic table size update the protocol limit forbids; decoding
     *                                stopped part-way, so the dynamic table can no longer be trusted
     *                                and RFC 9113 §4.3 makes this a connection error.
     * @implNote After a bound is exceeded, a literal that is not indexed is skipped octet-wise and
     *           never allocated. A literal with incremental indexing is still materialised when the
     *           entry could fit the dynamic table, because the table must hold the same entry the
     *           peer's encoder added; the allocation is then bounded by the table size, not by the
     *           length the peer declared. A literal that cannot fit the table is skipped, and the
     *           table is emptied as RFC 7541 §4.4 prescribes for an oversized entry. A string
     *           literal that is skipped is not checked for Huffman well-formedness.
     */
    public void decode(MemorySegment block, long offset, long length,
                       HeaderListener listener) {
        long end = offset + length;
        this.decodePos = offset;
        this.decodeHeaderListSize = 0;
        this.limitExceeded = false;
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
        if (limitExceeded) {
            throw HpackDecodingException.sizeLimitExceeded(limitMessage, limitObserved, limitBound);
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
        emit(name, value, false, listener);
    }

    private void decodeLiteralIncremental(MemorySegment block, long end, HeaderListener listener) {
        int nameIndex = readInteger(block, end, 6);
        String name = resolveName(block, end, nameIndex, true);
        // A name skipped as too large for the table already doomed the entry: the value is read
        // only to be passed over.
        String value = readStringLiteral(block, end, nameIndex != 0 || name != null);
        if (name == null || value == null) {
            // A string the table cannot hold: the peer's encoder emptied its table to make room
            // for an entry that does not fit (RFC 7541 §4.4), and so must this one.
            dynamicTable.clear();
            return;
        }
        dynamicTable.add(name, value);
        emit(name, value, false, listener);
    }

    private void decodeLiteralNoIndex(MemorySegment block, long end, HeaderListener listener) {
        int nameIndex = readInteger(block, end, 4);
        String name = resolveName(block, end, nameIndex, false);
        String value = readStringLiteral(block, end, false);
        emit(name, value, false, listener);
    }

    private void decodeLiteralNeverIndexed(MemorySegment block, long end, HeaderListener listener) {
        int nameIndex = readInteger(block, end, 4);
        String name = resolveName(block, end, nameIndex, false);
        String value = readStringLiteral(block, end, false);
        emit(name, value, true, listener);
    }

    private void decodeSizeUpdate(MemorySegment block, long end) {
        long newMaxSize = readInteger(block, end, 5, MAX_UINT32);
        if (newMaxSize > protocolMaxTableSize) {
            throw new HpackDecodingException(
                    MSG_SIZE_UPDATE_EXCEEDS_PROTOCOL_LIMIT,
                    newMaxSize, protocolMaxTableSize);
        }
        dynamicTable.setMaxSize(newMaxSize);
    }

    private String resolveName(MemorySegment block, long end, int nameIndex, boolean indexed) {
        if (nameIndex == 0) {
            return readStringLiteral(block, end, indexed);
        }
        return lookupName(nameIndex);
    }

    // =========================================================================
    // Integer decoding — RFC 7541 §5.1
    // =========================================================================

    private int readInteger(MemorySegment seg, long end, int prefixBits) {
        return (int) readInteger(seg, end, prefixBits, Integer.MAX_VALUE);
    }

    private long readInteger(MemorySegment seg, long end, int prefixBits, long maxValue) {
        if (decodePos >= end) {
            throw new HpackDecodingException(
                    "HPACK: unexpected end of block in integer");
        }
        int mask = (1 << prefixBits) - 1;
        long value = seg.get(ValueLayout.JAVA_BYTE, decodePos) & 0xFF & mask;
        decodePos++;

        if (value < mask) {
            if (value > maxValue) {
                throw new HpackDecodingException("HPACK: integer overflow");
            }
            return value;
        }

        int shift = 0;
        while (decodePos < end) {
            int octet = seg.get(ValueLayout.JAVA_BYTE, decodePos) & 0xFF;
            decodePos++;
            if (shift > MAX_INTEGER_SHIFT) {
                throw new HpackDecodingException("HPACK: integer overflow");
            }
            value += (long) (octet & 0x7F) << shift;
            if (value > maxValue) {
                throw new HpackDecodingException("HPACK: integer overflow");
            }
            if ((octet & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new HpackDecodingException(
                "HPACK: unexpected end of block in integer continuation");
    }

    // =========================================================================
    // String literal decoding — RFC 7541 §5.2
    // =========================================================================

    /**
     * Reads one string literal, or skips it.
     *
     * @param indexed whether the literal belongs to a field the dynamic table will hold
     * @return the decoded string; {@code null} when the literal was skipped — it is over the
     *         literal bound or a bound was already exceeded and nothing needs its content, or it
     *         is too large for the dynamic table to hold
     */
    private String readStringLiteral(MemorySegment seg, long end, boolean indexed) {
        if (decodePos >= end) {
            throw new HpackDecodingException(
                    "HPACK: unexpected end of block in string");
        }
        int firstByte = seg.get(ValueLayout.JAVA_BYTE, decodePos) & 0xFF;
        boolean huffmanEncoded = (firstByte & 0x80) != 0;

        int strLen = readInteger(seg, end, 7);
        long strStart = decodePos;

        if (strStart + strLen > end) {
            throw new HpackDecodingException(
                    "HPACK: string literal exceeds block boundary");
        }

        if (strLen > maxStringLiteralSize) {
            noteLimitExceeded(MSG_STRING_LITERAL_TOO_LONG, strLen, maxStringLiteralSize);
            if (!indexed || cannotFitTable(strLen, huffmanEncoded)) {
                decodePos = strStart + strLen;
                return null;
            }
        } else if (limitExceeded && !indexed) {
            decodePos = strStart + strLen;
            return null;
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

    /**
     * Whether a literal of the given encoded length decodes to more octets than the dynamic table
     * can hold, so that any entry carrying it is oversized (RFC 7541 §4.4). A Huffman code is at
     * most 30 bits, which gives the least the literal can decode to.
     */
    private boolean cannotFitTable(int encodedLength, boolean huffmanEncoded) {
        long leastDecoded = huffmanEncoded ? (encodedLength * 8L - 7L) / 30L : encodedLength;
        return leastDecoded > dynamicTable.maxSize();
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
            throw new HpackDecodingException(MSG_INVALID_INDEX, index, dynamicTable.size());
        }
        return nameOnly ? dynamicTable.getName(dynIndex) : dynamicTable.getValue(dynIndex);
    }

    /**
     * Delivers one field, unless a bound has been exceeded before it or by this field.
     * {@code name} or {@code value} is {@code null} only once a bound has been exceeded.
     */
    private void emit(String name, String value, boolean sensitive, HeaderListener listener) {
        if (limitExceeded) {
            return;
        }
        decodeHeaderListSize += utf8ByteLength(name) + utf8ByteLength(value) + 32;
        if (decodeHeaderListSize > maxHeaderListSize) {
            noteLimitExceeded(MSG_HEADER_LIST_SIZE_EXCEEDS_LIMIT, decodeHeaderListSize, maxHeaderListSize);
            return;
        }
        listener.onHeader(name, value, sensitive);
    }

    private void noteLimitExceeded(String message, long observed, long bound) {
        if (!limitExceeded) {
            limitExceeded = true;
            limitMessage = message;
            limitObserved = observed;
            limitBound = bound;
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
     * @since 0.5
     */
    public static final class HpackDecodingException extends ExerisKernelException {

        private static final String ERROR_CODE = KernelErrorCodes.EX_HTTP_4002;

        /**
         * What a decoding failure leaves behind, which decides how a caller must answer it.
         */
        public enum Kind {
            /**
             * The block is not a well-formed header block, or refers to a table entry that does
             * not exist. Decoding stopped part-way, so the dynamic table can no longer be trusted:
             * a connection error of type {@code COMPRESSION_ERROR} (RFC 9113 §4.3).
             */
            MALFORMED,

            /**
             * The block is well formed but exceeded a configured size bound (string literal or
             * cumulative header list). The decoder read it to its end, so the dynamic table matches
             * the peer's and the connection's HPACK state is intact: the request is refused with
             * {@code 431 Request Header Fields Too Large} (RFC 9113 §8.2.3, RFC 6585 §5).
             */
            SIZE_LIMIT_EXCEEDED
        }

        /** What this failure leaves behind; see {@link #kind()}. */
        private final Kind kind;

        /**
         * Creates a {@link Kind#MALFORMED} exception with no chained cause.
         *
         * @param messageTemplate static, pre-defined message template — no runtime formatting
         * @param rawArgs         domain arguments for the {@code EX-HTTP-4002} Glass-Box payload
         */
        public HpackDecodingException(String messageTemplate, Object... rawArgs) {
            super(ERROR_CODE, messageTemplate, rawArgs);
            this.kind = Kind.MALFORMED;
        }

        /**
         * Creates a {@link Kind#MALFORMED} exception chained to the failure that caused it, such
         * as a Huffman decoding error.
         *
         * @param messageTemplate static, pre-defined message template — no runtime formatting
         * @param cause           the underlying failure
         * @param rawArgs         domain arguments for the {@code EX-HTTP-4002} Glass-Box payload
         */
        public HpackDecodingException(String messageTemplate, Throwable cause, Object... rawArgs) {
            super(ERROR_CODE, messageTemplate, cause, rawArgs);
            this.kind = Kind.MALFORMED;
        }

        private HpackDecodingException(Kind kind, String messageTemplate, Object... rawArgs) {
            super(ERROR_CODE, messageTemplate, rawArgs);
            this.kind = kind;
        }

        /**
         * Creates a {@link Kind#SIZE_LIMIT_EXCEEDED} exception.
         *
         * @param messageTemplate static, pre-defined message template — no runtime formatting
         * @param rawArgs         the observed size and the bound, for the {@code EX-HTTP-4002} payload
         * @return the exception, ready to throw
         */
        public static HpackDecodingException sizeLimitExceeded(String messageTemplate, Object... rawArgs) {
            return new HpackDecodingException(Kind.SIZE_LIMIT_EXCEEDED, messageTemplate, rawArgs);
        }

        /**
         * Returns what this failure leaves behind.
         *
         * @return {@link Kind#SIZE_LIMIT_EXCEEDED} when the block was fully read and only a size
         *         bound was exceeded; {@link Kind#MALFORMED} otherwise
         */
        public Kind kind() {
            return kind;
        }

        /**
         * Returns whether this failure is a refusal on size alone.
         *
         * @return {@code true} when {@link #kind()} is {@link Kind#SIZE_LIMIT_EXCEEDED}
         */
        public boolean isSizeLimitExceeded() {
            return kind == Kind.SIZE_LIMIT_EXCEEDED;
        }
    }
}
