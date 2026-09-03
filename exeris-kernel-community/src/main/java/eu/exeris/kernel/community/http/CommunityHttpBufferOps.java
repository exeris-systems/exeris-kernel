/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/* default */ final class CommunityHttpBufferOps {

    private CommunityHttpBufferOps() {
    }

    /* default */ static long findCrLf(MemorySegment segment, long start, long endExclusive) {
        for (long index = start; index + 1 < endExclusive; index++) {
            if (segment.get(ValueLayout.JAVA_BYTE, index) == '\r'
                    && segment.get(ValueLayout.JAVA_BYTE, index + 1) == '\n') {
                return index;
            }
        }
        return -1;
    }

    /**
     * The first occurrence of {@code target} in {@code [start, end)}, or {@code -1}.
     */
    /* default */ static long indexOfByte(MemorySegment segment, long start, long end, byte target) {
        for (long index = start; index < end; index++) {
            if (segment.get(ValueLayout.JAVA_BYTE, index) == target) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Start offset with leading whitespace skipped, reproducing {@link String#trim()} — anything at
     * or below {@code 0x20}, compared <b>unsigned</b>, so a high-bit byte is left alone exactly as
     * US-ASCII decoding leaves it as a replacement character. Signed comparison would read
     * {@code 0x80} as {@code -128} and eat it.
     */
    /* default */ static long trimLeading(MemorySegment segment, long start, long end) {
        long index = start;
        while (index < end && isTrimmable(segment.get(ValueLayout.JAVA_BYTE, index))) {
            index++;
        }
        return index;
    }

    /** End offset with trailing whitespace dropped; see {@link #trimLeading}. */
    /* default */ static long trimTrailing(MemorySegment segment, long start, long end) {
        long index = end;
        while (index > start && isTrimmable(segment.get(ValueLayout.JAVA_BYTE, index - 1))) {
            index--;
        }
        return index;
    }

    private static boolean isTrimmable(byte value) {
        return (value & 0xFF) <= ' ';
    }

    /* default */ static String asciiString(MemorySegment segment, long startInclusive, long endExclusive) {
        int length = Math.toIntExact(endExclusive - startInclusive);
        byte[] bytes = new byte[length];
        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, startInclusive, bytes, 0, length);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    /* default */ static long retainUnreadBytes(LoanedBuffer aggregate, long consumedBytes) {
        long total = aggregate.size();
        return compactUnreadBytes(aggregate, total, consumedBytes);
    }

    /* default */ static long compactUnreadBytes(LoanedBuffer aggregate,
                                                 long bufferedBytes,
                                                 long offset) {
        long unreadBytes = Math.max(bufferedBytes - offset, 0);
        if (unreadBytes > 0 && offset > 0) {
            MemorySegment.copy(
                    aggregate.segment(), offset,
                    aggregate.segment(), 0,
                    unreadBytes);
        }
        aggregate.setSize(unreadBytes);
        return unreadBytes;
    }
}