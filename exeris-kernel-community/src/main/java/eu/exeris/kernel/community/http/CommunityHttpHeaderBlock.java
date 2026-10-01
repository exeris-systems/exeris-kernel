/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.core.http.CanonicalHeaderNames;
import eu.exeris.kernel.core.http.http1.Http1ParseException;
import eu.exeris.kernel.spi.exceptions.FaultOrigin;
import eu.exeris.kernel.spi.http.HttpHeader;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads an HTTP/1 header block off a segment, for the client response path.
 *
 * <p>Provides single-pass header block parsing ({@link #parseHeaders}) for the response decoder
 * to avoid duplicate segment traversals on the hot path, and lightweight framing scanning
 * ({@link #scanFraming}) for incremental read loop checks without allocating header objects.
 */
@SuppressWarnings("PMD.CyclomaticComplexity")
/* default */ final class CommunityHttpHeaderBlock {

    private static final String MSG_CONFLICTING_CONTENT_LENGTH =
            "HTTP/1.1: conflicting Content-Length headers present in HTTP response";
    private static final String MSG_EMPTY_CONTENT_LENGTH =
            "HTTP/1.1: empty Content-Length header value";
    private static final String MSG_INVALID_CONTENT_LENGTH =
            "HTTP/1.1: invalid Content-Length in HTTP response";
    private static final String MSG_CONTENT_LENGTH_OVERFLOW =
            "HTTP/1.1: Content-Length overflow in HTTP response";
    private static final String MSG_MALFORMED_HEADER_LINE =
            "HTTP/1.1: malformed header field: missing or misplaced colon delimiter";

    private static final String HEADER_CONTENT_LENGTH = "Content-Length";
    private static final String HEADER_TRANSFER_ENCODING = "Transfer-Encoding";

    private CommunityHttpHeaderBlock() {
    }

    /**
     * Immutable result of a single-pass header block parse.
     */
    /* default */ record ParsedHeaders(
            List<HttpHeader> headers,
            long contentLength,
            boolean hasTransferEncoding
    ) {}

    /**
     * Framing details extracted in a single lightweight segment traversal without allocating header objects.
     */
    /* default */ record FramingInfo(long contentLength, boolean hasTransferEncoding) {}

    /**
     * Parses the header block in a single traversal, extracting all {@link HttpHeader} fields,
     * resolving and validating {@code Content-Length}, and detecting {@code Transfer-Encoding}.
     *
     * @param segment      the buffer segment containing the header block
     * @param start        the starting offset of the headers
     * @param endExclusive the boundary offset of the header block terminator
     * @return the combined parse result
     * @throws Http1ParseException if framing or Content-Length values are malformed or conflicting
     */
    /* default */ static ParsedHeaders parseHeaders(MemorySegment segment, long start, long endExclusive) {
        return parseHeadersInternal(segment, start, endExclusive, true);
    }

    /**
     * Parses the header block into a list of {@link HttpHeader} fields.
     */
    /* default */ static List<HttpHeader> parse(MemorySegment segment, long start, long endExclusive) {
        return parseHeaders(segment, start, endExclusive).headers();
    }

    private static String fieldName(MemorySegment segment, long start, long end) {
        String known = CanonicalHeaderNames.resolve(segment, start, end);
        return known != null ? known : CommunityHttpBufferOps.asciiString(segment, start, end);
    }

    /**
     * Extracts Content-Length and Transfer-Encoding in a single lightweight segment traversal without
     * allocating header objects or strings.
     *
     * @param segment      the buffer segment containing the header block
     * @param start        the starting offset of the headers
     * @param endExclusive the boundary offset of the header block terminator
     * @return the extracted framing info
     * @throws Http1ParseException if framing or Content-Length values are malformed or conflicting
     */
    /* default */ static FramingInfo scanFraming(MemorySegment segment, long start, long endExclusive) {
        ParsedHeaders parsed = parseHeadersInternal(segment, start, endExclusive, false);
        return new FramingInfo(parsed.contentLength(), parsed.hasTransferEncoding());
    }

    private static ParsedHeaders parseHeadersInternal(
            MemorySegment segment, long start, long endExclusive, boolean collectHeaders) {
        List<HttpHeader> headers = collectHeaders ? new ArrayList<>() : List.of();
        long cursor = start;
        long foundLength = -1;
        boolean hasTransferEncoding = false;
        long searchLimit = Math.min(endExclusive + 2, segment.byteSize());
        while (cursor < endExclusive) {
            long lineEnd = CommunityHttpBufferOps.findCrLf(segment, cursor, searchLimit);
            if (lineEnd < 0 || lineEnd == cursor) {
                break;
            }
            long separator = validatedSeparator(segment, cursor, lineEnd);
            long nameEnd = CommunityHttpBufferOps.trimTrailing(segment, cursor, separator);
            long nameStart = validatedNameStart(segment, cursor, nameEnd, lineEnd);
            long valueStart = CommunityHttpBufferOps.trimLeading(segment, separator + 1, lineEnd);
            long valueEnd = CommunityHttpBufferOps.trimTrailing(segment, valueStart, lineEnd);
            if (CommunityHttpBufferOps.matchesAsciiIgnoreCase(segment, nameStart, nameEnd, HEADER_CONTENT_LENGTH)) {
                foundLength = updateContentLength(segment, valueStart, valueEnd, foundLength);
            } else if (CommunityHttpBufferOps.matchesAsciiIgnoreCase(
                    segment, nameStart, nameEnd, HEADER_TRANSFER_ENCODING)) {
                hasTransferEncoding = true;
            }
            if (collectHeaders) {
                headers.add(new HttpHeader(
                        fieldName(segment, nameStart, nameEnd),
                        CommunityHttpBufferOps.asciiString(segment, valueStart, valueEnd)));
            }
            cursor = lineEnd + 2;
        }
        List<HttpHeader> resultHeaders = collectHeaders ? Collections.unmodifiableList(headers) : headers;
        return new ParsedHeaders(resultHeaders, foundLength, hasTransferEncoding);
    }

    private static long validatedSeparator(MemorySegment segment, long cursor, long lineEnd) {
        long separator = CommunityHttpBufferOps.indexOfByte(segment, cursor, lineEnd, (byte) ':');
        if (separator <= cursor) {
            long fieldSize = lineEnd - cursor;
            throw new Http1ParseException(FaultOrigin.SYSTEM, MSG_MALFORMED_HEADER_LINE, fieldSize);
        }
        return separator;
    }

    private static long validatedNameStart(MemorySegment segment, long cursor, long nameEnd, long lineEnd) {
        long nameStart = CommunityHttpBufferOps.trimLeading(segment, cursor, nameEnd);
        if (nameStart >= nameEnd) {
            long fieldSize = lineEnd - cursor;
            throw new Http1ParseException(FaultOrigin.SYSTEM, MSG_MALFORMED_HEADER_LINE, fieldSize);
        }
        for (long idx = nameStart; idx < nameEnd; idx++) {
            byte byteVal = segment.get(ValueLayout.JAVA_BYTE, idx);
            if (byteVal <= 0x20 || byteVal == 0x7F) {
                long fieldSize = lineEnd - cursor;
                throw new Http1ParseException(FaultOrigin.SYSTEM, MSG_MALFORMED_HEADER_LINE, fieldSize);
            }
        }
        return nameStart;
    }

    private static long updateContentLength(MemorySegment segment, long valueStart, long valueEnd, long foundLength) {
        long parsed = parseContentLength(segment, valueStart, valueEnd);
        if (foundLength >= 0 && foundLength != parsed) {
            throw new Http1ParseException(FaultOrigin.SYSTEM, MSG_CONFLICTING_CONTENT_LENGTH, foundLength, parsed);
        }
        return parsed;
    }

    private static long parseContentLength(MemorySegment segment, long start, long end) {
        if (end <= start) {
            throw new Http1ParseException(FaultOrigin.SYSTEM, MSG_EMPTY_CONTENT_LENGTH);
        }
        long result = 0;
        for (long index = start; index < end; index++) {
            byte byteVal = segment.get(ValueLayout.JAVA_BYTE, index);
            if (byteVal < '0' || byteVal > '9') {
                throw new Http1ParseException(FaultOrigin.SYSTEM, MSG_INVALID_CONTENT_LENGTH, (long) (byteVal & 0xFF));
            }
            int digit = byteVal - '0';
            if (result > (Long.MAX_VALUE - digit) / 10) {
                throw new Http1ParseException(FaultOrigin.SYSTEM, MSG_CONTENT_LENGTH_OVERFLOW, result);
            }
            result = result * 10 + digit;
        }
        return result;
    }
}
