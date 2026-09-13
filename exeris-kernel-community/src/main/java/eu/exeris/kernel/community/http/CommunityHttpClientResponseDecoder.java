/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

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
@SuppressWarnings("PMD.CyclomaticComplexity") // parseStatusLine + parseHeaders + completeness checks have flat CC.
final class CommunityHttpClientResponseDecoder {

    private static final String HEADER_CONTENT_LENGTH = "Content-Length";
    private static final String HEADER_CONNECTION = "Connection";
    private static final String CONNECTION_CLOSE = "close";
    private static final String CONNECTION_KEEP_ALIVE = "keep-alive";
    private static final int STATUS_LINE_MIN_PARTS = 2;
    private static final int STATUS_LINE_PARTS_WITH_REASON = 3;

    private CommunityHttpClientResponseDecoder() {
        // package-private static utility — never instantiated.
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
     * Caches the expected total response size (headers + body) once both the
     * header terminator AND a parseable Content-Length are known.
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
        // Only one field is wanted here. Building the whole header list to read it — which is what
        // this did until v0.12 — materialised every name and value of a response the caller is still
        // waiting for, and then threw the list away.
        long contentLength = CommunityHttpHeaderBlock.findContentLength(
                segment, statusLineEnd + 2, headerTerminator);
        if (contentLength < 0) {
            return -1;
        }
        return headerTerminator + 4 + contentLength;
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
     * Allocates a dedicated body buffer via {@code allocator} if the response
     * carries a non-empty body; ownership transfers to the returned response.
     */
    /* default */ static HttpResponse decodeResponse(MemoryAllocator allocator,
                                                     LoanedBuffer aggregate,
                                                     long total,
                                                     HttpVersion requestVersion,
                                                     boolean bodyless) {
        long statusLineEnd = CommunityHttpBufferOps.findCrLf(aggregate.segment(), 0, total);
        if (statusLineEnd < 0) {
            throw new IllegalStateException("Invalid HTTP response: missing status line terminator");
        }

        String statusLine = CommunityHttpBufferOps.asciiString(aggregate.segment(), 0, statusLineEnd);
        StatusLine parsedStatus = parseStatusLine(statusLine, requestVersion);

        long headerStart = statusLineEnd + 2;
        long headerEnd = CommunityHttpBufferOps.findHeaderTerminator(aggregate.segment(), headerStart, total);
        if (headerEnd < 0) {
            throw new IllegalStateException("Invalid HTTP response: missing header terminator");
        }

        List<HttpHeader> headers = CommunityHttpHeaderBlock.parse(
                aggregate.segment(), headerStart, headerEnd);
        long bodyStart = headerEnd + 4;
        long availableBodyBytes = total - bodyStart;
        if (availableBodyBytes < 0) {
            throw new IllegalStateException("Invalid HTTP response: body start exceeds received bytes");
        }
        boolean noBodyAllowed = isBodyless(bodyless, parsedStatus.status().code());
        long bodyLength = noBodyAllowed ? 0L : resolveBodyLength(headers, availableBodyBytes);
        if (bodyLength > availableBodyBytes) {
            throw new IllegalStateException(
                    "Truncated HTTP response body: expected " + bodyLength
                    + " bytes but received " + availableBodyBytes + " bytes");
        }

        LoanedBuffer bodyBuffer = null;
        if (bodyLength > 0) {
            bodyBuffer = allocator.allocateNetwork((int) bodyLength);
            MemorySegment.copy(aggregate.segment(), bodyStart, bodyBuffer.segment(), 0, bodyLength);
            bodyBuffer.setSize(bodyLength);
        }
        return new HttpResponse(parsedStatus.status(), parsedStatus.version(), headers, bodyBuffer);
    }

    private static long resolveBodyLength(List<HttpHeader> headers, long fallbackBytes) {
        for (HttpHeader header : headers) {
            if (header.nameEqualsIgnoreCase(HEADER_CONTENT_LENGTH)) {
                try {
                    return Long.parseLong(header.value());
                } catch (NumberFormatException _) {
                    return Math.max(fallbackBytes, 0L);
                }
            }
        }
        return Math.max(fallbackBytes, 0L);
    }

    private static StatusLine parseStatusLine(String statusLine, HttpVersion requestVersion) {
        String[] parts = statusLine.split(" ", STATUS_LINE_PARTS_WITH_REASON);
        if (parts.length < STATUS_LINE_MIN_PARTS) {
            throw new IllegalStateException("Invalid HTTP response status line: " + statusLine);
        }
        HttpVersion version = switch (parts[0]) {
            case "HTTP/1.0" -> HttpVersion.HTTP_1_0;
            case "HTTP/1.1" -> HttpVersion.HTTP_1_1;
            case "HTTP/2", "HTTP/2.0" -> HttpVersion.HTTP_2;
            default -> requestVersion;
        };
        int code;
        try {
            code = Integer.parseInt(parts[1]);
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("Invalid HTTP status code in status line: " + statusLine, ex);
        }
        String reason = parts.length == STATUS_LINE_PARTS_WITH_REASON ? parts[2] : "";
        return new StatusLine(version, new HttpStatus(code, reason));
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
        if (containsConnectionToken(request.headers(), CONNECTION_CLOSE)
                || containsConnectionToken(response.headers(), CONNECTION_CLOSE)) {
            return false;
        }
        boolean noBody = isBodyless(request.method() == HttpMethod.HEAD, response.status().code());
        if (!noBody && !hasHeader(response.headers(), HEADER_CONTENT_LENGTH)) {
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

    private static boolean containsConnectionToken(List<HttpHeader> headers, String token) {
        for (HttpHeader header : headers) {
            if (!header.nameEqualsIgnoreCase(HEADER_CONNECTION) || header.value() == null) {
                continue;
            }
            for (String part : header.value().split(",")) {
                if (token.equalsIgnoreCase(part.trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A parsed HTTP/1.x status line: the resolved protocol version and status. */
    /* default */ record StatusLine(HttpVersion version, HttpStatus status) {
    }
}
