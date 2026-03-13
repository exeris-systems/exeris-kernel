/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.http.hpack;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Minimal UTF-8 helpers for HPACK hot paths without temporary {@code byte[]} allocation.
 */
final class HpackUtf8 {

    private static final int UTF8_1BYTE_MAX = 0x007F;
    private static final int UTF8_2BYTE_MAX = 0x07FF;
    private static final int UTF8_3BYTE_MAX = 0xFFFF;

    private HpackUtf8() {
    }

    /* package */ static int byteLength(String str) {
        int count = 0;
        final int len = str.length();
        for (int idx = 0; idx < len; idx++) {
            char current = str.charAt(idx);
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
            if (Character.isHighSurrogate(current)
                    && idx + 1 < len
                    && Character.isLowSurrogate(str.charAt(idx + 1))) {
                idx++;
            }
        }
        return count;
    }

    /* package */ static void writeToSegment(String str, MemorySegment target, long offset) {
        long cursor = offset;
        final int len = str.length();
        for (int idx = 0; idx < len; idx++) {
            char current = str.charAt(idx);
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
            if (Character.isHighSurrogate(current)
                    && idx + 1 < len
                    && Character.isLowSurrogate(str.charAt(idx + 1))) {
                idx++;
            }
        }
    }
}
