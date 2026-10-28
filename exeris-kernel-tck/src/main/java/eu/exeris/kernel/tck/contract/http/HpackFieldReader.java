/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.http;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * Decodes a complete HPACK header block (RFC 7541) into its fields, for a suite that has to read what a
 * server's HTTP/2 encoder wrote and cannot assume how it chose to encode it: indexed fields, literals
 * with and without indexing, a dynamic table that the block grows and resizes, and Huffman-coded
 * strings are all decoded. A reader is single-use per connection direction, because the dynamic table
 * carries over from one block to the next.
 *
 * <p><b>Allocation:</b> allocates freely; test code.
 * <p><b>Thread confinement:</b> one thread.
 * <p><b>Ownership:</b> holds no resource.
 */
final class HpackFieldReader {

    /** One decoded field. */
    /* default */ record Field(String name, String value) {
    }

    private static final String METHOD = ":method";
    private static final String PATH = ":path";
    private static final String SCHEME = ":scheme";
    private static final String STATUS = ":status";

    private static final String[] STATIC_NAMES = {
        "", ":authority", METHOD, METHOD, PATH, PATH, SCHEME, SCHEME, STATUS, STATUS,
        STATUS, STATUS, STATUS, STATUS, STATUS, "accept-charset", "accept-encoding",
        "accept-language", "accept-ranges", "accept", "access-control-allow-origin", "age", "allow",
        "authorization", "cache-control", "content-disposition", "content-encoding", "content-language",
        "content-length", "content-location", "content-range", "content-type", "cookie", "date", "etag",
        "expect", "expires", "from", "host", "if-match", "if-modified-since", "if-none-match", "if-range",
        "if-unmodified-since", "last-modified", "link", "location", "max-forwards", "proxy-authenticate",
        "proxy-authorization", "range", "referer", "refresh", "retry-after", "server", "set-cookie",
        "strict-transport-security", "transfer-encoding", "user-agent", "vary", "via", "www-authenticate"
    };

    /** Values of the static entries that have one, indexed from 1; every other entry's value is empty. */
    private static final String[] STATIC_VALUES = new String[STATIC_NAMES.length];

    static {
        Arrays.fill(STATIC_VALUES, "");
        STATIC_VALUES[2] = "GET";
        STATIC_VALUES[3] = "POST";
        STATIC_VALUES[4] = "/";
        STATIC_VALUES[5] = "/index.html";
        STATIC_VALUES[6] = "http";
        STATIC_VALUES[7] = "https";
        STATIC_VALUES[8] = "200";
        STATIC_VALUES[9] = "204";
        STATIC_VALUES[10] = "206";
        STATIC_VALUES[11] = "304";
        STATIC_VALUES[12] = "400";
        STATIC_VALUES[13] = "404";
        STATIC_VALUES[14] = "500";
        STATIC_VALUES[16] = "gzip, deflate";
    }

    private static final int EOS = 256;
    private static final int STATIC_COUNT = STATIC_NAMES.length - 1;
    private static final int ENTRY_OVERHEAD = 32;
    private static final int HUFFMAN_MAX_LENGTH = 30;

    /** RFC 7541 Appendix B code lengths, symbols 0 to 255 and then the end-of-string symbol. */
    private static final byte[] HUFFMAN_LENGTHS = {
        13, 23, 28, 28, 28, 28, 28, 28, 28, 24, 30, 28, 28, 30, 28, 28,
        28, 28, 28, 28, 28, 28, 30, 28, 28, 28, 28, 28, 28, 28, 28, 28,
         6, 10, 10, 12, 13,  6,  8, 11, 10, 10,  8, 11,  8,  6,  6,  6,
         5,  5,  5,  6,  6,  6,  6,  6,  6,  6,  7,  8, 15,  6, 12, 10,
        13,  6,  7,  7,  7,  7,  7,  7,  7,  7,  7,  7,  7,  7,  7,  7,
         7,  7,  7,  7,  7,  7,  7,  7,  8,  7,  8, 13, 19, 13, 14,  6,
        15,  5,  6,  5,  6,  5,  6,  6,  6,  5,  7,  7,  6,  6,  6,  5,
         6,  7,  6,  5,  5,  6,  7,  7,  7,  7,  7, 15, 11, 14, 13, 28,
        20, 22, 20, 20, 22, 22, 22, 23, 22, 23, 23, 23, 23, 23, 24, 23,
        24, 24, 22, 23, 24, 23, 23, 23, 23, 21, 22, 23, 22, 23, 23, 24,
        22, 21, 20, 22, 22, 23, 23, 21, 23, 22, 22, 24, 21, 22, 23, 23,
        21, 21, 22, 21, 23, 22, 23, 23, 20, 22, 22, 22, 23, 22, 22, 23,
        26, 26, 20, 19, 22, 23, 22, 25, 26, 26, 26, 27, 27, 26, 24, 25,
        19, 21, 26, 27, 27, 26, 27, 24, 21, 21, 26, 26, 28, 27, 27, 27,
        20, 24, 20, 21, 22, 21, 21, 23, 22, 22, 25, 25, 24, 24, 26, 23,
        26, 27, 26, 26, 27, 27, 27, 27, 27, 28, 27, 27, 27, 27, 27, 26,
        30
    };

    private static final int[] HUFFMAN_CODES = canonicalCodes();

    private final Deque<Field> dynamicTable = new ArrayDeque<>();
    private int dynamicSize;
    private int dynamicLimit = 4_096;
    private byte[] block;
    private int position;

    /** Creates a reader whose dynamic table starts empty at the protocol's default size. */
    /* default */ HpackFieldReader() {
        // state is the dynamic table, grown by the blocks decoded
    }

    /**
     * Decodes one complete header block.
     *
     * @param encoded the concatenated header block fragments of one HEADERS frame and its CONTINUATIONs
     * @return the fields in wire order
     * @throws IllegalArgumentException if the block is not a valid HPACK block
     */
    /* default */ List<Field> decode(byte[] encoded) {
        this.block = encoded.clone();
        this.position = 0;
        List<Field> fields = new ArrayList<>();
        while (position < block.length) {
            int first = block[position] & 0xFF;
            if ((first & 0x80) == 0x80) {
                fields.add(entry(readInteger(7)));
            } else if ((first & 0xC0) == 0x40) {
                Field field = literal(6);
                insert(field);
                fields.add(field);
            } else if ((first & 0xE0) == 0x20) {
                dynamicLimit = readInteger(5);
                evict(0);
            } else {
                fields.add(literal(4));
            }
        }
        return fields;
    }

    private Field literal(int prefixBits) {
        int nameIndex = readInteger(prefixBits);
        String name = nameIndex == 0 ? readString() : entry(nameIndex).name();
        return new Field(name, readString());
    }

    private Field entry(int index) {
        if (index >= 1 && index <= STATIC_COUNT) {
            return new Field(STATIC_NAMES[index], STATIC_VALUES[index]);
        }
        int offset = index - STATIC_COUNT - 1;
        if (index < 1 || offset >= dynamicTable.size()) {
            throw new IllegalArgumentException("HPACK index " + index + " is in neither table");
        }
        return dynamicTable.stream().skip(offset).findFirst().orElseThrow();
    }

    private void insert(Field field) {
        int size = field.name().length() + field.value().length() + ENTRY_OVERHEAD;
        evict(size);
        if (size <= dynamicLimit) {
            dynamicTable.addFirst(field);
            dynamicSize += size;
        }
    }

    private void evict(int incoming) {
        while (!dynamicTable.isEmpty() && dynamicSize + incoming > dynamicLimit) {
            Field removed = dynamicTable.removeLast();
            dynamicSize -= removed.name().length() + removed.value().length() + ENTRY_OVERHEAD;
        }
    }

    private int readInteger(int prefixBits) {
        int maxPrefix = (1 << prefixBits) - 1;
        int value = block[position++] & maxPrefix;
        if (value < maxPrefix) {
            return value;
        }
        int shift = 0;
        int next;
        do {
            next = block[position++] & 0xFF;
            value += (next & 0x7F) << shift;
            shift += 7;
        } while ((next & 0x80) != 0);
        return value;
    }

    private String readString() {
        boolean huffman = (block[position] & 0x80) != 0;
        int length = readInteger(7);
        byte[] raw = Arrays.copyOfRange(block, position, position + length);
        position += length;
        return new String(huffman ? huffmanDecode(raw) : raw, StandardCharsets.ISO_8859_1);
    }

    private static byte[] huffmanDecode(byte[] encoded) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = 0;
        int length = 0;
        for (byte octet : encoded) {
            for (int bit = 7; bit >= 0; bit--) {
                code = (code << 1) | ((octet >>> bit) & 1);
                length++;
                int symbol = symbolFor(code, length);
                if (symbol == EOS) {
                    throw new IllegalArgumentException("HPACK Huffman string contains the end-of-string symbol");
                }
                if (symbol >= 0) {
                    out.write(symbol);
                    code = 0;
                    length = 0;
                } else if (length > HUFFMAN_MAX_LENGTH) {
                    throw new IllegalArgumentException("HPACK Huffman string has no matching code");
                }
            }
        }
        return out.toByteArray();
    }

    private static int symbolFor(int code, int length) {
        for (int symbol = 0; symbol < HUFFMAN_LENGTHS.length; symbol++) {
            if (HUFFMAN_LENGTHS[symbol] == length && HUFFMAN_CODES[symbol] == code) {
                return symbol;
            }
        }
        return -1;
    }

    private static int[] canonicalCodes() {
        int maxLength = 0;
        for (byte length : HUFFMAN_LENGTHS) {
            maxLength = Math.max(maxLength, length);
        }
        int[] countByLength = new int[maxLength + 1];
        for (byte length : HUFFMAN_LENGTHS) {
            countByLength[length]++;
        }
        int[] nextCode = new int[maxLength + 1];
        int code = 0;
        for (int bits = 1; bits <= maxLength; bits++) {
            code = (code + countByLength[bits - 1]) << 1;
            nextCode[bits] = code;
        }
        int[] codes = new int[HUFFMAN_LENGTHS.length];
        for (int symbol = 0; symbol < codes.length; symbol++) {
            codes[symbol] = nextCode[HUFFMAN_LENGTHS[symbol]]++;
        }
        return codes;
    }
}
