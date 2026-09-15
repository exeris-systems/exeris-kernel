/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.core.http.http1.Http1ParseException;
import eu.exeris.kernel.spi.exceptions.FaultOrigin;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.transport.TransportConnection;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;

/**
 * Package-private static decoder for inbound HTTP/1.x responses received by
 * {@link CommunityHttpClientEngine}.
 *
 * <p>Owns status-line parsing, header parsing, body length resolution, and the incremental
 * completeness helpers that {@link CommunityHttpClientResponseReader}'s read loop consults to know
 * when to stop reading. Request encoding is the separate, symmetric responsibility of
 * {@link CommunityHttpClientRequestEncoder}.
 */
@SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.TooManyMethods"})
// decoder parsing, completeness checks, and zero-allocation token matching
final class CommunityHttpClientResponseDecoder {

    private static final String MSG_STATUS_LINE_TERMINATOR =
            "HTTP/1.1: invalid HTTP response: missing status line terminator";
    private static final String MSG_STATUS_LINE_SPACE =
            "HTTP/1.1: invalid HTTP response: missing status line space delimiter";
    private static final String MSG_HEADER_TERMINATOR =
            "HTTP/1.1: invalid HTTP response: missing header terminator";
    private static final String MSG_AMBIGUOUS_FRAMING =
            "HTTP/1.1: ambiguous framing: both Transfer-Encoding and Content-Length present in HTTP response";
    private static final String MSG_BODY_START_OVERFLOW =
            "HTTP/1.1: invalid HTTP response: body start exceeds received bytes";
    private static final String MSG_TRUNCATED_BODY =
            "HTTP/1.1: truncated HTTP response body";
    private static final String MSG_UNCONSUMED_TRAILING_BYTES =
            "HTTP/1.1: unconsumed trailing bytes in HTTP response";
    private static final String MSG_PROTOCOL_VERSION =
            "HTTP/1.1: invalid or unsupported HTTP protocol version in status line";
    private static final String MSG_STATUS_CODE =
            "HTTP/1.1: invalid HTTP status code in status line";
    private static final String MSG_UNSUPPORTED_TRANSFER_ENCODING =
            "HTTP/1.1: Transfer-Encoding is not supported by CommunityHttpClientEngine";

    private static final String HEADER_CONTENT_LENGTH = "Content-Length";
    private static final String HEADER_CONNECTION = "Connection";
    private static final String HEADER_TRANSFER_ENCODING = "Transfer-Encoding";
    private static final String CONNECTION_CLOSE = "close";
    private static final String CONNECTION_KEEP_ALIVE = "keep-alive";
    private static final String HEADER_UPGRADE = "Upgrade";
    private static final String CONNECTION_UPGRADE = "upgrade";
    private static final int HTTP_VERSION_STANDARD_LENGTH = 8;
    private static final HttpStatus EMPTY_REASON_200 = new HttpStatus(200, "");
    private static final HttpStatus EMPTY_REASON_204 = new HttpStatus(204, "");
    private static final HttpStatus EMPTY_REASON_304 = new HttpStatus(304, "");

    private CommunityHttpClientResponseDecoder() {
        // package-private static utility — never instantiated.
    }

    private static Http1ParseException parseException(String messageTemplate, Object... rawArgs) {
        return new Http1ParseException(FaultOrigin.SYSTEM, messageTemplate, rawArgs);
    }

    /**
     * Caches the offset of the header-block terminator ({@code CRLF CRLF}) once
     * found — read-loop consults this to know when headers are complete.
     */
    /* default */ static long resolveHeaderTerminator(long currentHeaderTerminator,
                                                      MemorySegment segment,
                                                      long totalBytes) {
        if (currentHeaderTerminator >= 0) {
            return currentHeaderTerminator;
        }
        return CommunityHttpBufferOps.findHeaderTerminator(segment, 0, totalBytes);
    }

    /**
     * Caches the expected response total size in bytes (header block + body) once
     * the status line and headers have been sufficiently received. Returns {@code -1}
     * if the expected total cannot yet be determined.
     */
    /* default */ static long resolveExpectedTotal(long currentExpectedTotal,
                                                   MemorySegment segment,
                                                   long totalBytes,
                                                   long headerTerminator,
                                                   boolean bodyless) {
        if (currentExpectedTotal >= 0 || headerTerminator < 0) {
            return currentExpectedTotal;
        }
        long statusLineEnd = CommunityHttpBufferOps.findCrLf(segment, 0, totalBytes);
        if (statusLineEnd < 0) {
            return -1;
        }
        int statusCode = CommunityHttpBufferOps.parseStatusCode(segment, 0, statusLineEnd);
        if (isBodyless(bodyless, statusCode)) {
            // RFC 9110 §6.4.1: HEAD, 1xx, 204 (No Content), and 304 (Not Modified) responses MUST NOT contain a body.
            return headerTerminator + 4;
        }
        long headerStart = statusLineEnd + 2;
        long effectiveHeaderEnd = Math.max(headerStart, headerTerminator);
        CommunityHttpHeaderBlock.FramingInfo framing = CommunityHttpHeaderBlock.scanFraming(
                segment, headerStart, effectiveHeaderEnd);
        if (framing.hasTransferEncoding()) {
            if (framing.contentLength() >= 0) {
                throw parseException(MSG_AMBIGUOUS_FRAMING);
            }
            throw parseException(MSG_UNSUPPORTED_TRANSFER_ENCODING);
        }
        if (framing.contentLength() < 0) {
            return -1;
        }
        return headerTerminator + 4 + framing.contentLength();
    }

    /**
     * Whether enough bytes have been read to decode the full response — {@code false} while
     * {@code expectedTotalBytes} is not yet known ({@code <= 0}, i.e. header terminator or
     * {@code Content-Length} still unresolved).
     */
    /* default */ static boolean isResponseComplete(long totalBytes, long expectedTotalBytes) {
        return expectedTotalBytes > 0 && totalBytes >= expectedTotalBytes;
    }

    /**
     * Decodes the fully aggregated response buffer into an {@link HttpResponse}.
     * Returns a zero-copy slice of {@code aggregate} if the response carries a non-empty body;
     * ownership transfers to the returned response.
     */
    /* default */ static HttpResponse decodeResponse(@SuppressWarnings("unused") MemoryAllocator allocator,
                                                     LoanedBuffer aggregate,
                                                     long total,
                                                     boolean bodyless) {
        long statusLineEnd = CommunityHttpBufferOps.findCrLf(aggregate.segment(), 0, total);
        if (statusLineEnd < 0) {
            throw parseException(MSG_STATUS_LINE_TERMINATOR);
        }

        StatusLine parsedStatus = parseStatusLine(aggregate.segment(), 0, statusLineEnd);

        long headerStart = statusLineEnd + 2;
        long headerEnd = CommunityHttpBufferOps.findHeaderTerminator(aggregate.segment(), statusLineEnd, total);
        if (headerEnd < 0) {
            throw parseException(MSG_HEADER_TERMINATOR);
        }

        long effectiveHeaderEnd = Math.max(headerStart, headerEnd);
        CommunityHttpHeaderBlock.ParsedHeaders parsedHeaders = CommunityHttpHeaderBlock.parseHeaders(
                aggregate.segment(), headerStart, effectiveHeaderEnd);
        List<HttpHeader> headers = parsedHeaders.headers();
        long contentLength = parsedHeaders.contentLength();
        if (parsedHeaders.hasTransferEncoding()) {
            if (contentLength >= 0) {
                throw parseException(MSG_AMBIGUOUS_FRAMING);
            }
            throw parseException(MSG_UNSUPPORTED_TRANSFER_ENCODING);
        }

        long bodyStart = headerEnd + 4;
        long availableBodyBytes = total - bodyStart;
        if (availableBodyBytes < 0) {
            throw parseException(MSG_BODY_START_OVERFLOW);
        }
        boolean noBodyAllowed = isBodyless(bodyless, parsedStatus.status().code());
        long bodyLength;
        if (noBodyAllowed) {
            bodyLength = 0L;
        } else if (contentLength >= 0) {
            bodyLength = contentLength;
        } else {
            bodyLength = Math.max(availableBodyBytes, 0L);
        }

        if (bodyLength > availableBodyBytes) {
            throw parseException(
                    MSG_TRUNCATED_BODY, bodyLength, availableBodyBytes);
        }
        if (bodyLength < availableBodyBytes && (noBodyAllowed || contentLength >= 0)) {
            throw parseException(
                    MSG_UNCONSUMED_TRAILING_BYTES, bodyLength, availableBodyBytes);
        }

        LoanedBuffer bodyBuffer = null;
        if (bodyLength > 0) {
            bodyBuffer = aggregate.slice(bodyStart, bodyLength);
        }
        return new HttpResponse(parsedStatus.status(), parsedStatus.version(), headers, bodyBuffer);
    }

    private static StatusLine parseStatusLine(MemorySegment segment,
                                              long start,
                                              long end) {
        long spaceIndex = CommunityHttpBufferOps.indexOfByte(segment, start, end, (byte) ' ');
        if (spaceIndex < 0) {
            throw parseException(MSG_STATUS_LINE_SPACE);
        }
        HttpVersion version = resolveHttpVersion(segment, start, spaceIndex);
        int code = CommunityHttpBufferOps.parseStatusCode(segment, start, end);
        if (code < 100 || code > 599) {
            throw parseException(MSG_STATUS_CODE, (long) code);
        }
        long reasonStart = CommunityHttpBufferOps.trimLeading(segment, spaceIndex + 4, end);
        long reasonEnd = CommunityHttpBufferOps.trimTrailing(segment, reasonStart, end);
        HttpStatus status = resolveHttpStatus(segment, code, reasonStart, reasonEnd);
        return new StatusLine(version, status);
    }

    private static HttpStatus resolveHttpStatus(MemorySegment segment,
                                                int code,
                                                long reasonStart,
                                                long reasonEnd) {
        long reasonLen = reasonEnd - reasonStart;
        if (reasonLen <= 0) {
            return resolveEmptyReasonHttpStatus(code);
        }
        HttpStatus canonical = matchCanonicalHttpStatus(segment, code, reasonStart, reasonLen);
        if (canonical != null) {
            return canonical;
        }
        return new HttpStatus(code, CommunityHttpBufferOps.asciiString(segment, reasonStart, reasonEnd));
    }

    private static HttpStatus resolveEmptyReasonHttpStatus(int code) {
        return switch (code) {
            case 200 -> EMPTY_REASON_200;
            case 204 -> EMPTY_REASON_204;
            case 304 -> EMPTY_REASON_304;
            default -> new HttpStatus(code, "");
        };
    }

    @SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.CognitiveComplexity"})
    private static HttpStatus matchCanonicalHttpStatus(MemorySegment segment,
                                                       int code,
                                                       long start,
                                                       long len) {
        return switch (code) {
            case 100 -> (len == 8 && matchesAsciiIgnoreCase(segment, start, "Continue"))
                    ? HttpStatus.CONTINUE : null;
            case 101 -> (len == 19 && matchesAsciiIgnoreCase(segment, start, "Switching Protocols"))
                    ? HttpStatus.SWITCHING_PROTOCOLS : null;
            case 103 -> (len == 11 && matchesAsciiIgnoreCase(segment, start, "Early Hints"))
                    ? HttpStatus.EARLY_HINTS : null;
            case 200 -> (len == 2 && matchesAsciiIgnoreCase(segment, start, "OK")) ? HttpStatus.OK : null;
            case 201 -> (len == 7 && matchesAsciiIgnoreCase(segment, start, "Created")) ? HttpStatus.CREATED : null;
            case 202 -> (len == 8 && matchesAsciiIgnoreCase(segment, start, "Accepted")) ? HttpStatus.ACCEPTED : null;
            case 204 -> (len == 10 && matchesAsciiIgnoreCase(segment, start, "No Content"))
                    ? HttpStatus.NO_CONTENT : null;
            case 206 -> (len == 15 && matchesAsciiIgnoreCase(segment, start, "Partial Content"))
                    ? HttpStatus.PARTIAL_CONTENT : null;
            case 301 -> (len == 17 && matchesAsciiIgnoreCase(segment, start, "Moved Permanently"))
                    ? HttpStatus.MOVED_PERMANENTLY : null;
            case 302 -> (len == 5 && matchesAsciiIgnoreCase(segment, start, "Found")) ? HttpStatus.FOUND : null;
            case 304 -> (len == 12 && matchesAsciiIgnoreCase(segment, start, "Not Modified"))
                    ? HttpStatus.NOT_MODIFIED : null;
            case 307 -> (len == 18 && matchesAsciiIgnoreCase(segment, start, "Temporary Redirect"))
                    ? HttpStatus.TEMPORARY_REDIRECT : null;
            case 308 -> (len == 18 && matchesAsciiIgnoreCase(segment, start, "Permanent Redirect"))
                    ? HttpStatus.PERMANENT_REDIRECT : null;
            case 400 -> (len == 11 && matchesAsciiIgnoreCase(segment, start, "Bad Request"))
                    ? HttpStatus.BAD_REQUEST : null;
            case 401 -> (len == 12 && matchesAsciiIgnoreCase(segment, start, "Unauthorized"))
                    ? HttpStatus.UNAUTHORIZED : null;
            case 403 -> (len == 9 && matchesAsciiIgnoreCase(segment, start, "Forbidden")) ? HttpStatus.FORBIDDEN : null;
            case 404 -> (len == 9 && matchesAsciiIgnoreCase(segment, start, "Not Found")) ? HttpStatus.NOT_FOUND : null;
            case 405 -> (len == 18 && matchesAsciiIgnoreCase(segment, start, "Method Not Allowed"))
                    ? HttpStatus.METHOD_NOT_ALLOWED : null;
            case 408 -> (len == 15 && matchesAsciiIgnoreCase(segment, start, "Request Timeout"))
                    ? HttpStatus.REQUEST_TIMEOUT : null;
            case 409 -> (len == 8 && matchesAsciiIgnoreCase(segment, start, "Conflict")) ? HttpStatus.CONFLICT : null;
            case 410 -> (len == 4 && matchesAsciiIgnoreCase(segment, start, "Gone")) ? HttpStatus.GONE : null;
            case 413 -> (len == 17 && matchesAsciiIgnoreCase(segment, start, "Content Too Large"))
                    ? HttpStatus.CONTENT_TOO_LARGE : null;
            case 414 -> (len == 12 && matchesAsciiIgnoreCase(segment, start, "URI Too Long"))
                    ? HttpStatus.URI_TOO_LONG : null;
            case 429 -> (len == 17 && matchesAsciiIgnoreCase(segment, start, "Too Many Requests"))
                    ? HttpStatus.TOO_MANY_REQUESTS : null;
            case 431 -> (len == 31 && matchesAsciiIgnoreCase(segment, start, "Request Header Fields Too Large"))
                    ? HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE : null;
            case 500 -> (len == 21 && matchesAsciiIgnoreCase(segment, start, "Internal Server Error"))
                    ? HttpStatus.INTERNAL_SERVER_ERROR : null;
            case 501 -> (len == 15 && matchesAsciiIgnoreCase(segment, start, "Not Implemented"))
                    ? HttpStatus.NOT_IMPLEMENTED : null;
            case 502 -> (len == 11 && matchesAsciiIgnoreCase(segment, start, "Bad Gateway"))
                    ? HttpStatus.BAD_GATEWAY : null;
            case 503 -> (len == 19 && matchesAsciiIgnoreCase(segment, start, "Service Unavailable"))
                    ? HttpStatus.SERVICE_UNAVAILABLE : null;
            case 504 -> (len == 15 && matchesAsciiIgnoreCase(segment, start, "Gateway Timeout"))
                    ? HttpStatus.GATEWAY_TIMEOUT : null;
            case 505 -> (len == 26 && matchesAsciiIgnoreCase(segment, start, "HTTP Version Not Supported"))
                    ? HttpStatus.HTTP_VERSION_NOT_SUPPORTED : null;
            default -> null;
        };
    }

    private static HttpVersion resolveHttpVersion(MemorySegment segment,
                                                  long start,
                                                  long end) {
        long length = end - start;
        if (length == HTTP_VERSION_STANDARD_LENGTH) {
            if (matchesAscii(segment, start, "HTTP/1.1")) {
                return HttpVersion.HTTP_1_1;
            }
            if (matchesAscii(segment, start, "HTTP/1.0")) {
                return HttpVersion.HTTP_1_0;
            }
        }
        throw parseException(
                MSG_PROTOCOL_VERSION,
                length
        );
    }

    private static boolean matchesAsciiIgnoreCase(MemorySegment segment, long start, String target) {
        return CommunityHttpBufferOps.matchesAsciiIgnoreCase(segment, start, start + target.length(), target);
    }

    private static boolean matchesAscii(MemorySegment segment, long start, String target) {
        int length = target.length();
        for (int i = 0; i < length; i++) {
            if (segment.get(ValueLayout.JAVA_BYTE, start + i) != (byte) target.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Determines whether the underlying connection is eligible to be returned to the pool for reuse.
     *
     * <p>Requires an open connection, HTTP/1.1 (or HTTP/1.0 with keep-alive), framed response body
     * (explicit {@code Content-Length} or {@code HEAD} request), and absence of {@code Connection: close}.
     *
     * @param request    the outbound request
     * @param response   the decoded response
     * @param connection the underlying transport connection
     * @return true if the connection can be kept alive and pooled
     */
    /* default */ static boolean isKeepAlive(HttpRequest request,
                                            HttpResponse response,
                                            TransportConnection connection) {
        if (connection == null || !connection.isOpen()) {
            return false;
        }
        int statusCode = response.status().code();
        // RFC 9110 §15.2: 1xx informational responses (including 101 Switching Protocols)
        // are never kept alive in an HTTP/1.1 client request pool.
        if (response.status().isInformational()) {
            return false;
        }
        if (containsConnectionToken(request.headers(), CONNECTION_CLOSE)
                || containsConnectionToken(response.headers(), CONNECTION_CLOSE)) {
            return false;
        }
        // RFC 9112 §6.3: Upgrade requests switch connection protocol away from HTTP/1.1
        if (containsConnectionToken(request.headers(), CONNECTION_UPGRADE)
                || containsConnectionToken(response.headers(), CONNECTION_UPGRADE)
                || hasHeader(request.headers(), HEADER_UPGRADE)
                || hasHeader(response.headers(), HEADER_UPGRADE)) {
            return false;
        }
        // RFC 9112 §6.1: Transfer-Encoding (e.g. chunked) is not pooled without chunk decoder
        if (hasHeader(request.headers(), HEADER_TRANSFER_ENCODING)
                || hasHeader(response.headers(), HEADER_TRANSFER_ENCODING)) {
            return false;
        }
        boolean noBody = isBodyless(request.method() == HttpMethod.HEAD, statusCode);
        if (!noBody && !hasHeader(response.headers(), HEADER_CONTENT_LENGTH)) {
            return false;
        }
        // RFC 9112 §6.3: HTTP/1.0 requires explicit keep-alive on both request and response
        if (request.version() == HttpVersion.HTTP_1_0
                && !containsConnectionToken(request.headers(), CONNECTION_KEEP_ALIVE)) {
            return false;
        }
        if (response.version() == HttpVersion.HTTP_1_0) {
            return containsConnectionToken(response.headers(), CONNECTION_KEEP_ALIVE);
        }
        return response.version() == HttpVersion.HTTP_1_1;
    }

    private static boolean isBodyless(boolean head, int statusCode) {
        return head
                || statusCode == 204
                || statusCode == 304
                || (statusCode >= 100 && statusCode < 200);
    }

    private static boolean hasHeader(List<HttpHeader> headers, String name) {
        for (HttpHeader h : headers) {
            if (h.nameEqualsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /* default */ static boolean containsConnectionToken(List<HttpHeader> headers, String token) {
        for (HttpHeader header : headers) {
            if (!header.nameEqualsIgnoreCase(HEADER_CONNECTION) || header.value() == null) {
                continue;
            }
            if (tokenMatches(header.value(), token)) {
                return true;
            }
        }
        return false;
    }

    /* default */ static boolean tokenMatches(CharSequence headerValue, String token) {
        if (headerValue == null || token == null) {
            return false;
        }
        int length = headerValue.length();
        int tokenLength = token.length();
        int start = 0;
        while (start < length) {
            start = skipDelimiters(headerValue, start, length);
            if (start >= length) {
                break;
            }
            int delimiterIndex = findTokenDelimiter(headerValue, start, length);
            int tokenEnd = trimTrailingWhitespace(headerValue, start, delimiterIndex);
            if (tokenEnd - start == tokenLength
                    && asciiEqualsIgnoreCase(headerValue, start, token, tokenLength)) {
                return true;
            }
            start = delimiterIndex + 1;
        }
        return false;
    }

    private static int skipDelimiters(CharSequence headerValue, int start, int length) {
        int index = start;
        while (index < length
                && (headerValue.charAt(index) == ' '
                || headerValue.charAt(index) == '\t'
                || headerValue.charAt(index) == ',')) {
            index++;
        }
        return index;
    }

    private static int findTokenDelimiter(CharSequence headerValue, int start, int length) {
        int index = start;
        while (index < length && headerValue.charAt(index) != ',') {
            index++;
        }
        return index;
    }

    private static int trimTrailingWhitespace(CharSequence headerValue, int start, int end) {
        int index = end;
        while (index > start
                && (headerValue.charAt(index - 1) == ' '
                || headerValue.charAt(index - 1) == '\t')) {
            index--;
        }
        return index;
    }

    private static boolean asciiEqualsIgnoreCase(CharSequence sequence,
                                                 int start,
                                                 String target,
                                                 int length) {
        for (int index = 0; index < length; index++) {
            char actual = sequence.charAt(start + index);
            char expected = target.charAt(index);
            if (actual != expected) {
                char lowerActual = (actual >= 'A' && actual <= 'Z') ? (char) (actual + 32) : actual;
                char lowerExpected = (expected >= 'A' && expected <= 'Z') ? (char) (expected + 32) : expected;
                if (lowerActual != lowerExpected) {
                    return false;
                }
            }
        }
        return true;
    }

    /** A parsed HTTP/1.x status line: the resolved protocol version and status. */
    /* default */ record StatusLine(HttpVersion version, HttpStatus status) {
    }
}
