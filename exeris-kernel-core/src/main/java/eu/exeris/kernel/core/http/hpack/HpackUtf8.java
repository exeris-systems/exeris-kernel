/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.hpack;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Minimal UTF-8 helpers for HPACK hot paths without temporary {@code byte[]} allocation.
 *
 * <p>Neither method validates {@code target} capacity itself; an undersized destination
 * segment surfaces as the bounds check {@link MemorySegment#set} already performs.
 */
final class HpackUtf8 {

    private static final int UTF8_1BYTE_MAX = 0x007F;
    private static final int UTF8_2BYTE_MAX = 0x07FF;
    private static final int UTF8_3BYTE_MAX = 0xFFFF;

    private HpackUtf8() {
    }

    /**
     * Computes the UTF-8 encoded byte length of {@code str} without allocating an
     * intermediate byte array.
     *
     * @param str string to measure
     * @return the number of bytes {@code str} occupies when encoded as UTF-8
     */
    /* package */ static int byteLength(String str) {
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
     * Encodes {@code str} as UTF-8 directly into {@code target}, starting at {@code offset}.
     *
     * @param str    string to encode
     * @param target destination segment
     * @param offset byte offset in {@code target} to start writing at; exactly
     *               {@link #byteLength(String)} bytes are written from this position
     */
    /* package */ static void writeToSegment(String str, MemorySegment target, long offset) {
        long cursor = offset;
        final int len = str.length();
        int idx = 0;
        while (idx < len) {
            int codePoint = str.codePointAt(idx);
            if (codePoint <= UTF8_1BYTE_MAX) {
                target.set(ValueLayout.JAVA_BYTE, cursor, (byte) codePoint);
                cursor += 1;
            } else if (codePoint <= UTF8_2BYTE_MAX) {
                target.set(ValueLayout.JAVA_BYTE, cursor, (byte) (0xC0 | (codePoint >>> 6)));
                cursor += 1;
                target.set(ValueLayout.JAVA_BYTE, cursor, (byte) (0x80 | (codePoint & 0x3F)));
                cursor += 1;
            } else if (codePoint <= UTF8_3BYTE_MAX) {
                target.set(ValueLayout.JAVA_BYTE, cursor, (byte) (0xE0 | (codePoint >>> 12)));
                cursor += 1;
                target.set(ValueLayout.JAVA_BYTE, cursor, (byte) (0x80 | ((codePoint >>> 6) & 0x3F)));
                cursor += 1;
                target.set(ValueLayout.JAVA_BYTE, cursor, (byte) (0x80 | (codePoint & 0x3F)));
                cursor += 1;
            } else {
                target.set(ValueLayout.JAVA_BYTE, cursor, (byte) (0xF0 | (codePoint >>> 18)));
                cursor += 1;
                target.set(ValueLayout.JAVA_BYTE, cursor, (byte) (0x80 | ((codePoint >>> 12) & 0x3F)));
                cursor += 1;
                target.set(ValueLayout.JAVA_BYTE, cursor, (byte) (0x80 | ((codePoint >>> 6) & 0x3F)));
                cursor += 1;
                target.set(ValueLayout.JAVA_BYTE, cursor, (byte) (0x80 | (codePoint & 0x3F)));
                cursor += 1;
            }
            idx += Character.charCount(codePoint);
        }
    }
}
