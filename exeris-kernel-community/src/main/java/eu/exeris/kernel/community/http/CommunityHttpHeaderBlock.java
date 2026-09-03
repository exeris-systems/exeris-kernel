/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.core.http.CanonicalHeaderNames;
import eu.exeris.kernel.spi.http.HttpHeader;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads an HTTP/1 header block off a segment, for the client response path.
 *
 * <p>Its own class because a header block is read for two different reasons and only one of them
 * wants a list: the read loop needs {@code Content-Length} to know when to stop reading, and the
 * decoder needs the fields. Until v0.12 both went through one list-building parse, so every name and
 * value of every response was materialised twice.
 */
/* default */ final class CommunityHttpHeaderBlock {

    private CommunityHttpHeaderBlock() {
    }

    /**
     * Parses the header block without materialising a line, a name substring or a value substring.
     *
     * <p>Field boundaries are found on the segment and only the value is turned into a {@code
     * String}; a known field-name resolves to a shared constant and allocates nothing
     * (RFC-2026-09-01). Trimming is done on byte offsets and reproduces {@link String#trim()} —
     * anything at or below {@code 0x20}, compared unsigned so a high-bit byte is left alone exactly
     * as US-ASCII decoding leaves it as a replacement character.
     */
    /* default */ static List<HttpHeader> parse(MemorySegment segment, long start, long endExclusive) {
        List<HttpHeader> headers = new ArrayList<>();
        long cursor = start;
        while (cursor < endExclusive) {
            long lineEnd = CommunityHttpBufferOps.findCrLf(segment, cursor, endExclusive + 2);
            if (lineEnd < 0 || lineEnd == cursor) {
                break;
            }
            long separator = CommunityHttpBufferOps.indexOfByte(segment, cursor, lineEnd, (byte) ':');
            if (separator > cursor) {
                long nameEnd = CommunityHttpBufferOps.trimTrailing(segment, cursor, separator);
                long nameStart = CommunityHttpBufferOps.trimLeading(segment, cursor, nameEnd);
                long valueStart = CommunityHttpBufferOps.trimLeading(segment, separator + 1, lineEnd);
                long valueEnd = CommunityHttpBufferOps.trimTrailing(segment, valueStart, lineEnd);
                headers.add(new HttpHeader(
                        fieldName(segment, nameStart, nameEnd),
                        CommunityHttpBufferOps.asciiString(segment, valueStart, valueEnd)));
            }
            cursor = lineEnd + 2;
        }
        // A view, not a copy: the list is created here and the only reference that leaves is the
        // view itself. Should it ever escape by another route, copy it again.
        return Collections.unmodifiableList(headers);
    }

    private static String fieldName(MemorySegment segment, long start, long end) {
        String known = CanonicalHeaderNames.resolve(segment, start, end);
        return known != null ? known : CommunityHttpBufferOps.asciiString(segment, start, end);
    }

    /**
     * The {@code Content-Length} value, or {@code -1} if absent or unparseable — read without
     * building a header list. The value is still materialised and handed to {@link Long#parseLong},
     * so what counts as parseable is unchanged.
     */
    /* default */ static long findContentLength(MemorySegment segment, long start, long endExclusive) {
        long cursor = start;
        while (cursor < endExclusive) {
            long lineEnd = CommunityHttpBufferOps.findCrLf(segment, cursor, endExclusive + 2);
            if (lineEnd < 0 || lineEnd == cursor) {
                return -1;
            }
            long separator = CommunityHttpBufferOps.indexOfByte(segment, cursor, lineEnd, (byte) ':');
            if (separator > cursor) {
                long nameEnd = CommunityHttpBufferOps.trimTrailing(segment, cursor, separator);
                long nameStart = CommunityHttpBufferOps.trimLeading(segment, cursor, nameEnd);
                if (rangeEqualsIgnoreCase(segment, nameStart, nameEnd, "Content-Length")) {
                    long valueStart = CommunityHttpBufferOps.trimLeading(segment, separator + 1, lineEnd);
                    long valueEnd = CommunityHttpBufferOps.trimTrailing(segment, valueStart, lineEnd);
                    return parseContentLength(CommunityHttpBufferOps.asciiString(segment, valueStart, valueEnd));
                }
            }
            cursor = lineEnd + 2;
        }
        return -1;
    }

    private static long parseContentLength(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException _) {
            return -1;
        }
    }

    private static boolean rangeEqualsIgnoreCase(MemorySegment segment, long start, long end,
                                                 String candidate) {
        if (end - start != candidate.length()) {
            return false;
        }
        for (int offset = 0; offset < candidate.length(); offset++) {
            char actual = (char) (segment.get(ValueLayout.JAVA_BYTE, start + offset) & 0xFF);
            if (Character.toLowerCase(actual) != Character.toLowerCase(candidate.charAt(offset))) {
                return false;
            }
        }
        return true;
    }
}
