/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.http.http1;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/**
 * RFC 9112 — HTTP/1.1 Response Encoder.
 *
 * <h2>Contract</h2>
 * <p>Writes HTTP/1.1 status-line and header fields directly into a
 * {@link MemorySegment} — no intermediate String concatenation on the hot path.
 *
 * @since 0.5.0
 */
public final class Http1ResponseEncoder {

    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] COLON_SPACE = {':', ' '};
    private static final byte[] HTTP_11_PREFIX = "HTTP/1.1 ".getBytes(StandardCharsets.US_ASCII);

    private Http1ResponseEncoder() {
    }

    /**
     * Writes the status-line into the segment.
     *
     * @param seg        destination segment
     * @param offset     byte offset
     * @param statusCode HTTP status code (e.g. 200)
     * @param reasonPhrase reason phrase (e.g. "OK")
     * @return new byte position after the status-line CRLF
     */
    @SuppressWarnings("PMD.AssignmentInOperand")
    public static long writeStatusLine(MemorySegment seg, long offset,
                                       int statusCode, String reasonPhrase) {
        long pos = offset;
        pos = writeBytes(seg, pos, HTTP_11_PREFIX);
        pos = writeAsciiInt(seg, pos, statusCode);
        seg.set(ValueLayout.JAVA_BYTE, pos++, (byte) ' ');
        pos = writeAscii(seg, pos, reasonPhrase);
        pos = writeBytes(seg, pos, CRLF);
        return pos;
    }

    /**
     * Writes a single header field.
     *
     * @param seg    destination segment
     * @param offset byte offset
     * @param name   header name
     * @param value  header value
     * @return new byte position after the header CRLF
     */
    public static long writeHeader(MemorySegment seg, long offset, String name, String value) {
        long pos = offset;
        pos = writeAscii(seg, pos, name);
        pos = writeBytes(seg, pos, COLON_SPACE);
        pos = writeAscii(seg, pos, value);
        pos = writeBytes(seg, pos, CRLF);
        return pos;
    }

    /**
     * Writes the terminal CRLF that separates headers from the body.
     *
     * @param seg    destination segment
     * @param offset byte offset
     * @return new byte position
     */
    public static long writeHeaderEnd(MemorySegment seg, long offset) {
        return writeBytes(seg, offset, CRLF);
    }

    // =========================================================================
    // Internal
    // =========================================================================

    private static long writeBytes(MemorySegment seg, long pos, byte[] data) {
        MemorySegment.copy(MemorySegment.ofArray(data), ValueLayout.JAVA_BYTE, 0,
                seg, ValueLayout.JAVA_BYTE, pos, data.length);
        return pos + data.length;
    }

    private static long writeAscii(MemorySegment seg, long pos, String str) {
        final int len = str.length();
        for (int i = 0; i < len; i++) {
            seg.set(ValueLayout.JAVA_BYTE, pos + i, (byte) str.charAt(i));
        }
        return pos + len;
    }

    private static long writeAsciiInt(MemorySegment seg, long pos, int value) {
        if (value == 0) {
            seg.set(ValueLayout.JAVA_BYTE, pos, (byte) '0');
            return pos + 1;
        }
        long magnitude = value;
        long writePos = pos;
        if (magnitude < 0) {
            seg.set(ValueLayout.JAVA_BYTE, writePos, (byte) '-');
            writePos++;
            magnitude = -magnitude;
        }
        int digits = 0;
        long tmp = magnitude;
        while (tmp > 0) {
            tmp /= 10;
            digits++;
        }
        long endPos = writePos + digits;
        long digitPos = endPos - 1;
        while (magnitude > 0) {
            seg.set(ValueLayout.JAVA_BYTE, digitPos, (byte) ('0' + (int) (magnitude % 10)));
            digitPos--;
            magnitude /= 10;
        }
        return endPos;
    }
}
