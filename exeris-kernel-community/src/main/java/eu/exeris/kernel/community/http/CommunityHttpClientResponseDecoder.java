/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Package-private static decoder for inbound HTTP/1.x responses received by
 * {@link CommunityHttpClientEngine}.
 *
 * <p>Extracted from {@link CommunityHttpClientEngine} in v0.8 Sprint 3 (QA-015)
 * as the second seam of the engine's God-class decomposition (request encoding
 * lives in {@link CommunityHttpClientRequestEncoder}). Owns status-line parsing,
 * header parsing, body length resolution, and the incremental completeness
 * helpers that the engine's read loop consults to know when to stop reading.
 */
@SuppressWarnings("PMD.CyclomaticComplexity") // parseStatusLine + parseHeaders + completeness checks have flat CC.
final class CommunityHttpClientResponseDecoder {

    private static final String HEADER_CONTENT_LENGTH = "Content-Length";
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
        return findHeaderTerminator(segment, 0, totalBytes);
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
        if (bodyless) {
            // RFC 9110 §6.4.1: the response to HEAD carries the Content-Length the object would
            // have, and no body. Waiting for those bytes would block until the peer closed.
            return headerTerminator + 4;
        }
        long statusLineEnd = CommunityHttpBufferOps.findCrLf(segment, 0, totalBytes);
        if (statusLineEnd < 0) {
            return -1;
        }
        List<HttpHeader> headers = parseHeaders(segment, statusLineEnd + 2, headerTerminator);
        long contentLength = resolveContentLengthFromHeaders(headers);
        if (contentLength < 0) {
            return -1;
        }
        return headerTerminator + 4 + contentLength;
    }

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

        String statusLine = asciiString(aggregate.segment(), 0, statusLineEnd);
        StatusLine parsedStatus = parseStatusLine(statusLine, requestVersion);

        long headerStart = statusLineEnd + 2;
        long headerEnd = findHeaderTerminator(aggregate.segment(), headerStart, total);
        if (headerEnd < 0) {
            throw new IllegalStateException("Invalid HTTP response: missing header terminator");
        }

        List<HttpHeader> headers = parseHeaders(aggregate.segment(), headerStart, headerEnd);
        long bodyStart = headerEnd + 4;
        long availableBodyBytes = total - bodyStart;
        if (availableBodyBytes < 0) {
            throw new IllegalStateException("Invalid HTTP response: body start exceeds received bytes");
        }
        long bodyLength = bodyless ? 0L : resolveBodyLength(headers, availableBodyBytes);
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

    private static List<HttpHeader> parseHeaders(MemorySegment segment, long start, long endExclusive) {
        List<HttpHeader> headers = new ArrayList<>();
        long cursor = start;
        while (cursor < endExclusive) {
            long lineEnd = CommunityHttpBufferOps.findCrLf(segment, cursor, endExclusive + 2);
            if (lineEnd < 0 || lineEnd == cursor) {
                break;
            }
            String line = asciiString(segment, cursor, lineEnd);
            int separator = line.indexOf(':');
            if (separator > 0) {
                String name = line.substring(0, separator).trim();
                String value = line.substring(separator + 1).trim();
                headers.add(new HttpHeader(name, value));
            }
            cursor = lineEnd + 2;
        }
        return List.copyOf(headers);
    }

    private static long resolveBodyLength(List<HttpHeader> headers, long fallbackBytes) {
        Optional<String> contentLength = headers.stream()
                .filter(header -> header.nameEqualsIgnoreCase(HEADER_CONTENT_LENGTH))
                .map(HttpHeader::value)
                .findFirst();
        if (contentLength.isPresent()) {
            try {
                return Long.parseLong(contentLength.get());
            } catch (NumberFormatException _) {
                return Math.max(fallbackBytes, 0L);
            }
        }
        return Math.max(fallbackBytes, 0L);
    }

    private static long resolveContentLengthFromHeaders(List<HttpHeader> headers) {
        for (HttpHeader h : headers) {
            if (h.nameEqualsIgnoreCase(HEADER_CONTENT_LENGTH)) {
                try {
                    return Long.parseLong(h.value().trim());
                } catch (NumberFormatException _) {
                    return -1;
                }
            }
        }
        return -1;
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

    private static long findHeaderTerminator(MemorySegment segment, long start, long endExclusive) {
        for (long index = start; index + 3 < endExclusive; index++) {
            if (segment.get(ValueLayout.JAVA_BYTE, index) == '\r'
                    && segment.get(ValueLayout.JAVA_BYTE, index + 1) == '\n'
                    && segment.get(ValueLayout.JAVA_BYTE, index + 2) == '\r'
                    && segment.get(ValueLayout.JAVA_BYTE, index + 3) == '\n') {
                return index;
            }
        }
        return -1;
    }

    private static String asciiString(MemorySegment segment, long startInclusive, long endExclusive) {
        int length = Math.toIntExact(endExclusive - startInclusive);
        byte[] bytes = segment.asSlice(startInclusive, length).toArray(ValueLayout.JAVA_BYTE);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    /* default */ record StatusLine(HttpVersion version, HttpStatus status) {
    }
}
