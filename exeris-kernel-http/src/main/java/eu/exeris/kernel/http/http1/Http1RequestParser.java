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
 * RFC 9112 — HTTP/1.1 Request Parser.
 *
 * <h2>Contract</h2>
 * <p>Parses HTTP/1.1 request-line and header fields from a {@link MemorySegment}.
 * Operates directly on off-heap data — no intermediate String allocation until
 * the caller requests decoded header values.
 *
 * <h2>DoS Limits</h2>
 * <p>Use {@link #parseHeaders(MemorySegment, long, long, int, int, HeaderVisitor)}
 * to enforce per-connection header count and size limits before handing off to
 * application logic. The unbounded overload is retained for internal use by
 * {@link Http1Codec} which applies its own limits.
 *
 * <h2>Thread Safety</h2>
 * <p>Not thread-safe. Each connection/request uses its own parser instance.
 *
 * @since 0.5.0
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9112">RFC 9112</a>
 */
public final class Http1RequestParser {

    /** Default maximum number of header fields per request. */
    public static final int DEFAULT_MAX_HEADERS = 100;

    /** Default maximum byte size of a single header field (name + value). */
    public static final int DEFAULT_MAX_HEADER_SIZE = 8_192;

    private static final byte CARRIAGE_RETURN = '\r';
    private static final byte LINE_FEED = '\n';
    private static final byte SPACE = ' ';
    private static final byte COLON = ':';

    /**
     * Parsed request-line — Valhalla-ready.
     *
     * @param method  HTTP method (e.g. "GET")
     * @param target  request target (e.g. "/index.html")
     * @param version HTTP version (e.g. "HTTP/1.1")
     */
    public record RequestLine(String method, String target, String version) {}

    /**
     * Callback for parsed header fields.
     */
    @FunctionalInterface
    public interface HeaderVisitor {
        void onHeader(String name, String value);
    }

    private Http1RequestParser() {
    }

    /**
     * Parses the request-line from the given segment.
     *
     * @param seg    source segment
     * @param offset start offset
     * @param length available bytes
     * @return parsed request-line, or {@code null} if incomplete (no CRLF found)
     */
    public static RequestLine parseRequestLine(MemorySegment seg, long offset, long length) {
        long lineEnd = findCrLf(seg, offset, length);
        if (lineEnd < 0) {
            return null;
        }

        long pos = offset;
        long sp1 = findByte(seg, pos, lineEnd, SPACE);
        if (sp1 < 0) {
            return null;
        }
        String method = readAscii(seg, pos, sp1);
        pos = sp1 + 1;

        long sp2 = findByte(seg, pos, lineEnd, SPACE);
        if (sp2 < 0) {
            return null;
        }
        String target = readAscii(seg, pos, sp2);
        pos = sp2 + 1;

        String version = readAscii(seg, pos, lineEnd);
        return new RequestLine(method, target, version);
    }

    /**
     * Parses header fields with {@link #DEFAULT_MAX_HEADERS} and
     * {@link #DEFAULT_MAX_HEADER_SIZE} limits applied.
     *
     * @param seg     source segment
     * @param offset  offset to start of first header line
     * @param length  available bytes
     * @param visitor callback for each parsed header
     * @return byte position after the terminal CRLF CRLF, or {@code -1} if incomplete
     * @throws Http1ParseException if the header count or a single header size limit
     *                              is exceeded
     */
    public static long parseHeaders(MemorySegment seg, long offset, long length,
                                    HeaderVisitor visitor) {
        return parseHeaders(seg, offset, length,
                DEFAULT_MAX_HEADERS, DEFAULT_MAX_HEADER_SIZE, visitor);
    }

    /**
     * Parses header fields with explicit DoS limits.
     *
     * @param seg           source segment
     * @param offset        offset to start of first header line
     * @param length        available bytes
     * @param maxHeaders    maximum number of header fields to accept
     * @param maxHeaderSize maximum byte size of a single header field (name + value)
     * @param visitor       callback for each parsed header
     * @return byte position after the terminal CRLF CRLF, or {@code -1} if incomplete
     * @throws Http1ParseException if {@code maxHeaders} or {@code maxHeaderSize} is exceeded
     */
    @SuppressWarnings("PMD.CyclomaticComplexity")
    public static long parseHeaders(MemorySegment seg, long offset, long length,
                                    int maxHeaders, int maxHeaderSize,
                                    HeaderVisitor visitor) {
        long pos = offset;
        long end = offset + length;
        int headerCount = 0;

        while (pos < end) {
            if (pos + 1 < end
                    && seg.get(ValueLayout.JAVA_BYTE, pos) == CARRIAGE_RETURN
                    && seg.get(ValueLayout.JAVA_BYTE, pos + 1) == LINE_FEED) {
                return pos + 2;
            }

            long lineEnd = findCrLf(seg, pos, end - pos);
            if (lineEnd < 0) {
                return -1;
            }

            long colonPos = findByte(seg, pos, lineEnd, COLON);
            if (colonPos < 0) {
                pos = lineEnd + 2;
                continue;
            }

            long fieldSize = lineEnd - pos;
            if (fieldSize > maxHeaderSize) {
                throw new Http1ParseException(
                        "HTTP/1.1: header field exceeds size limit (" + fieldSize
                                + " > " + maxHeaderSize + ")");
            }

            headerCount++;
            if (headerCount > maxHeaders) {
                throw new Http1ParseException(
                        "HTTP/1.1: too many header fields (limit " + maxHeaders + ")");
            }

            String name = readAscii(seg, pos, colonPos).strip();
            String value = readAscii(seg, colonPos + 1, lineEnd).strip();
            visitor.onHeader(name, value);
            pos = lineEnd + 2;
        }
        return -1;
    }

    /**
     * Unchecked exception for HTTP/1.1 protocol parse violations (DoS limits, malformed
     * framing).
     *
     * @since 0.5.0
     */
    public static final class Http1ParseException extends RuntimeException {
        public Http1ParseException(String message) {
            super(message);
        }
    }

    // =========================================================================
    // Internal
    // =========================================================================

    private static long findCrLf(MemorySegment seg, long offset, long length) {
        long end = offset + length - 1;
        for (long pos = offset; pos < end; pos++) {
            if (seg.get(ValueLayout.JAVA_BYTE, pos) == CARRIAGE_RETURN
                    && seg.get(ValueLayout.JAVA_BYTE, pos + 1) == LINE_FEED) {
                return pos;
            }
        }
        return -1;
    }

    private static long findByte(MemorySegment seg, long start, long end, byte target) {
        for (long pos = start; pos < end; pos++) {
            if (seg.get(ValueLayout.JAVA_BYTE, pos) == target) {
                return pos;
            }
        }
        return -1;
    }

    private static String readAscii(MemorySegment seg, long start, long end) {
        int len = (int) (end - start);
        byte[] bytes = new byte[len];
        MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, start, bytes, 0, len);
        return new String(bytes, StandardCharsets.US_ASCII);
    }
}
