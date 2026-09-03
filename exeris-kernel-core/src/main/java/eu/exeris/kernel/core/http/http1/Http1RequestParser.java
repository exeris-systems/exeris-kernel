/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.http1;

import eu.exeris.kernel.core.http.CanonicalHeaderNames;
import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/**
 * RFC 9112 — HTTP/1.1 Request Parser.
 *
 * <h2>Contract</h2>
 * <p>Parses HTTP/1.1 request-line and header fields from a {@link MemorySegment},
 * operating directly on off-heap data. Request-line components (method, target,
 * version) and header names/values are decoded into {@link String} instances during
 * parsing; callers should treat these as the primary decoded representations.
 *
 * <h2>DoS Limits</h2>
 * <p>All {@code parseHeaders(...)} overloads enforce header count and/or header size
 * limits. Use {@link #parseHeaders(MemorySegment, long, long, int, int, HeaderVisitor)}
 * to specify explicit per-connection limits, or rely on the overload that applies
 * {@link #DEFAULT_MAX_HEADERS} and {@link #DEFAULT_MAX_HEADER_SIZE}.
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. Stateless static utility methods can be used concurrently.
 *
 * @since 0.5.0
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9112">RFC 9112</a>
 */
@SuppressWarnings("PMD.CyclomaticComplexity")
public final class Http1RequestParser {

    /** Default maximum number of header fields per request. */
    public static final int DEFAULT_MAX_HEADERS = 100;

    /** Default maximum byte size of a single header field (name + value). */
    public static final int DEFAULT_MAX_HEADER_SIZE = 8_192;

    private static final byte CARRIAGE_RETURN = '\r';
    private static final byte LINE_FEED = '\n';
    private static final byte SPACE = ' ';
    private static final byte COLON = ':';
    private static final long CRLF_SEQUENCE_LENGTH = 2L;
    private static final String MSG_HEADER_SIZE_LIMIT = "HTTP/1.1: header field exceeds size limit";
    private static final String MSG_TOO_MANY_HEADERS = "HTTP/1.1: too many header fields";
    private static final String MSG_MALFORMED_HEADER = "HTTP/1.1: malformed header field (missing ':')";
    private static final String MSG_INVALID_HEADER_NAME = "Invalid HTTP header field-name";
    private static final String MSG_RANGE_OUT_OF_BOUNDS = "HTTP/1.1 parser range out of bounds";

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
     * @return parsed request-line, or {@code null} if the request-line is incomplete
     *         (no CRLF found) or malformed (e.g., missing required spaces between tokens)
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
                rejectMalformedHeaderLine(lineEnd - pos, maxHeaderSize);
            }

            long fieldSize = lineEnd - pos;
            if (fieldSize > maxHeaderSize) {
                throw new Http1ParseException(MSG_HEADER_SIZE_LIMIT, fieldSize, maxHeaderSize);
            }

            headerCount++;
            if (headerCount > maxHeaders) {
                throw new Http1ParseException(MSG_TOO_MANY_HEADERS, headerCount, maxHeaders);
            }

            String name = resolveFieldName(seg, pos, colonPos);
            String rawValue = readAscii(seg, colonPos + 1, lineEnd);
            String value = trimOws(rawValue);
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
    public static final class Http1ParseException extends ExerisKernelException {

        private static final String ERROR_CODE = KernelErrorCodes.EX_HTTP_4004;

        public Http1ParseException(String messageTemplate, Object... rawArgs) {
            super(ERROR_CODE, messageTemplate, rawArgs);
        }

        public Http1ParseException(String messageTemplate, Throwable cause, Object... rawArgs) {
            super(ERROR_CODE, messageTemplate, cause, rawArgs);
        }
    }

    // =========================================================================
    // Internal
    // =========================================================================

    private static void rejectMalformedHeaderLine(long fieldSize, int maxHeaderSize) {
        if (fieldSize > maxHeaderSize) {
            throw new Http1ParseException(MSG_HEADER_SIZE_LIMIT, fieldSize, maxHeaderSize);
        }
        throw new Http1ParseException(MSG_MALFORMED_HEADER, fieldSize);
    }

    private static long findCrLf(MemorySegment seg, long offset, long length) {
        long size = seg.byteSize();
        if (offset < 0 || length < 0 || offset > size) {
            long requestedEnd = (length > Long.MAX_VALUE - offset) ? Long.MAX_VALUE : offset + length;
            throw new Http1ParseException(MSG_RANGE_OUT_OF_BOUNDS, offset, requestedEnd, size);
        }
        if (length < CRLF_SEQUENCE_LENGTH) {
            return -1;
        }
        long maxLength = size - offset;
        if (length > maxLength) {
            long requestedEnd = (length > Long.MAX_VALUE - offset) ? Long.MAX_VALUE : offset + length;
            throw new Http1ParseException(MSG_RANGE_OUT_OF_BOUNDS, offset, requestedEnd, size);
        }

        long end = offset + length;
        long endMinusOne = end - 1;
        for (long pos = offset; pos < endMinusOne; pos++) {
            if (seg.get(ValueLayout.JAVA_BYTE, pos) == CARRIAGE_RETURN
                    && seg.get(ValueLayout.JAVA_BYTE, pos + 1) == LINE_FEED) {
                return pos;
            }
        }
        return -1;
    }

    private static long findByte(MemorySegment seg, long start, long end, byte target) {
        long size = seg.byteSize();
        if (start < 0 || end < start || end > size) {
            throw new Http1ParseException(MSG_RANGE_OUT_OF_BOUNDS, start, end, size);
        }
        for (long pos = start; pos < end; pos++) {
            if (seg.get(ValueLayout.JAVA_BYTE, pos) == target) {
                return pos;
            }
        }
        return -1;
    }

    /**
     * A known spelling resolves to a shared constant with no allocation, and is a valid token by
     * construction — so both the materialisation and the validation are skipped. A miss is exactly
     * the path every field took before (RFC-2026-09-01).
     */
    private static String resolveFieldName(MemorySegment seg, long start, long end) {
        String known = CanonicalHeaderNames.resolve(seg, start, end);
        if (known != null) {
            return known;
        }
        String rawName = readAscii(seg, start, end);
        if (!CanonicalHeaderNames.isValidFieldName(rawName)) {
            throw new Http1ParseException(MSG_INVALID_HEADER_NAME, rawName);
        }
        return rawName;
    }

    private static String readAscii(MemorySegment seg, long start, long end) {
        int len = (int) (end - start);
        byte[] bytes = new byte[len];
        MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, start, bytes, 0, len);
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static String trimOws(String value) {
        int len = value.length();
        int start = 0;
        int end = len;
        while (start < end) {
            char currentChar = value.charAt(start);
            if (currentChar == ' ' || currentChar == '\t') {
                start++;
            } else {
                break;
            }
        }
        while (end > start) {
            char currentChar = value.charAt(end - 1);
            if (currentChar == ' ' || currentChar == '\t') {
                end--;
            } else {
                break;
            }
        }
        if (start == 0 && end == len) {
            return value;
        }
        return value.substring(start, end);
    }
}
