/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.http.hpack.huffman;

/**
 * RFC 7541 Appendix B — pre-computed Huffman nibble-based decode table.
 *
 * <h2>Layout</h2>
 * <p>The table is a flat {@code int[]} of {@code STATE_COUNT × 16} entries.
 * Each entry packs the decode result into a single 32-bit integer:
 * <ul>
 *   <li>Bit 15 ({@code 0x8000}) — emit flag: 1 = symbol decoded, 0 = state transition only</li>
 *   <li>Bits [8..0] ({@code 0x1FF}) — decoded symbol (0–256; 256 = EOS)</li>
 *   <li>Bits [31..16] — next state index</li>
 *   <li>When emit flag is 0: bits [15..0] = next state index</li>
 * </ul>
 *
 * <h2>Memory</h2>
 * <p>{@code 512 states × 16 nibbles × 4 bytes = 32 KB} of static {@code int[]}
 * allocated once at class-load time on the Java heap. This is bootstrap-time
 * infrastructure — not a hot-path allocation target.
 *
 * <h2>Hot-Path Contract</h2>
 * <p>{@link #getEntry(int, int)} is the only method intended for the decode hot path.
 * It returns a packed {@code int} — zero heap allocation per nibble.
 *
 * @since 0.5.0
 */
@SuppressWarnings({
        "PMD.CyclomaticComplexity",
        "PMD.CognitiveComplexity",
        "PMD.NPathComplexity",
        "PMD.AssignmentInOperand",
        "PMD.ConfusingTernary",
        "PMD.UseVarargs"
})
public final class HuffmanTable {

    private static final int STATE_COUNT = 512;
    private static final int NIBBLE_COUNT = 16;

    private static final byte[] HUFFMAN_LENS = {
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

    private static final int[] HUFFMAN_CODES;
    private static final int[] HUFFMAN_LENS_INT;
    private static final int[] FLAT_TABLE;

    static {
        HUFFMAN_LENS_INT = new int[HUFFMAN_LENS.length];
        for (int idx = 0; idx < HUFFMAN_LENS.length; idx++) {
            HUFFMAN_LENS_INT[idx] = HUFFMAN_LENS[idx] & 0xFF;
        }
        HUFFMAN_CODES = generateCanonicalHuffman(HUFFMAN_LENS_INT);
        FLAT_TABLE = buildFlatTable();
    }

    private HuffmanTable() {
    }

    /**
     * Returns the packed decode entry for the given state and nibble.
     *
     * <p>This is the only method intended for the hot path. Returns a packed
     * {@code int} — zero allocation.
     *
     * @param state  current decoder state (0–511)
     * @param nibble 4-bit nibble value (0–15)
     * @return packed decode entry
     */
    public static int getEntry(int state, int nibble) {
        return FLAT_TABLE[state * NIBBLE_COUNT + nibble];
    }

    /**
     * Returns the canonical Huffman code for the given symbol.
     *
     * @param symbol byte value (0–256; 256 = EOS)
     * @return Huffman code as a right-aligned integer
     */
    public static int getHuffmanCode(int symbol) {
        return HUFFMAN_CODES[symbol];
    }

    /**
     * Returns the bit length of the Huffman code for the given symbol.
     *
     * @param symbol byte value (0–256; 256 = EOS)
     * @return code length in bits
     */
    public static int getHuffmanCodeLength(int symbol) {
        return HUFFMAN_LENS_INT[symbol];
    }

    /** Returns the number of symbols in the Huffman alphabet (257: 0–255 + EOS). */
    public static int symbolCount() {
        return HUFFMAN_LENS.length;
    }

    // =========================================================================
    // Table construction — bootstrap-time only
    // =========================================================================

    @SuppressWarnings("java:S3776") // complexity is inherent to the algorithm; this is not a hot path
    private static int[] buildFlatTable() {
        int capacity = 512;
        int[] left = new int[capacity];
        int[] right = new int[capacity];
        int nextNode = 1;

        for (int sym = 0; sym < HUFFMAN_CODES.length; sym++) {
            int len = HUFFMAN_LENS_INT[sym];
            if (len == 0) {
                continue;
            }
            int code = HUFFMAN_CODES[sym];
            int curr = 0;
            for (int bit = len - 1; bit >= 0; bit--) {
                int direction = (code >> bit) & 1;
                int[] arr = (direction == 0) ? left : right;
                if (arr[curr] == 0) {
                    if (bit == 0) {
                        arr[curr] = -(sym + 1);
                    } else {
                        arr[curr] = nextNode++;
                        if (nextNode >= capacity) {
                            capacity *= 2;
                            left = java.util.Arrays.copyOf(left, capacity);
                            right = java.util.Arrays.copyOf(right, capacity);
                        }
                    }
                }
                curr = arr[curr] > 0 ? arr[curr] : curr;
            }
        }

        int maxState = 0;
        for (int idx = 0; idx < left.length; idx++) {
            if (left[idx] != 0 || right[idx] != 0) {
                maxState = idx;
            }
        }
        maxState++;
        int stateCount = Math.min(maxState, STATE_COUNT);

        int[] table = new int[STATE_COUNT * NIBBLE_COUNT];
        for (int state = 0; state < stateCount; state++) {
            for (int nibble = 0; nibble < NIBBLE_COUNT; nibble++) {
                int curr = state;
                int matchedSymbol = -1;
                for (int bit = 3; bit >= 0; bit--) {
                    int direction = (nibble >> bit) & 1;
                    int next = (direction == 0) ? left[curr] : right[curr];
                    if (next < 0) {
                        matchedSymbol = -(next + 1);
                        curr = 0;
                    } else {
                        curr = next;
                    }
                }
                if (matchedSymbol != -1) {
                    table[state * NIBBLE_COUNT + nibble] =
                            0x8000 | (matchedSymbol & 0x1FF) | (curr << 16);
                } else {
                    table[state * NIBBLE_COUNT + nibble] = curr;
                }
            }
        }
        return table;
    }

    private static int[] generateCanonicalHuffman(int[] lens) {
        int maxLen = 0;
        for (int length : lens) {
            maxLen = Math.max(maxLen, length);
        }
        int[] blCount = new int[maxLen + 1];
        for (int length : lens) {
            if (length > 0) {
                blCount[length]++;
            }
        }
        int[] nextCode = new int[maxLen + 1];
        int code = 0;
        for (int bits = 1; bits <= maxLen; bits++) {
            code = (code + blCount[bits - 1]) << 1;
            nextCode[bits] = code;
        }
        int[] codes = new int[lens.length];
        for (int idx = 0; idx < lens.length; idx++) {
            if (lens[idx] != 0) {
                codes[idx] = nextCode[lens[idx]]++;
            }
        }
        return codes;
    }
}
