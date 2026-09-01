/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.core.http.http1.Http1Codec;
import eu.exeris.kernel.core.http.http1.Http1RequestParser;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/* default */ final class CommunityHttp1RequestReader {

    private CommunityHttp1RequestReader() {
    }

    /* default */ static long retainUnreadBytes(LoanedBuffer aggregate, long consumedBytes) {
        return CommunityHttpBufferOps.retainUnreadBytes(aggregate, consumedBytes);
    }

    /* default */ static ReadResult tryParseRequest(Http1Codec codec,
                                                    LoanedBuffer aggregate,
                                                    long total) {
        long requestLineEnd = CommunityHttpBufferOps.findCrLf(aggregate.segment(), 0, total);
        if (requestLineEnd < 0) {
            return null;
        }

        Http1RequestParser.RequestLine requestLine =
                codec.parseRequestLine(aggregate.segment(), 0, total);
        if (requestLine == null) {
            return null;
        }

        long headerStart = requestLineEnd + 2;
        // One pass yields both: the codec's connection state and this list. It used to be two, and
        // the bound was the reason the second one was dangerous rather than merely wasteful — the
        // enforced limit depended on which pass reached it first unless both were handed identical
        // bounds (ADR-071). A single pass cannot express that mistake, and does not re-materialise
        // every field to make it.
        List<HttpHeader> headers = new ArrayList<>();
        long headersEnd = codec.parseHeaders(
                aggregate.segment(), headerStart, total - headerStart,
                (name, value) -> headers.add(new HttpHeader(name, value)));
        if (headersEnd < 0) {
            return null;
        }

        int bodyLength = (int) Math.max(codec.pendingContentLength(), 0);
        if (total < headersEnd + bodyLength) {
            return null;
        }

        HttpMethod method = parseMethod(requestLine.method());
        if (method == null) {
            return null;
        }

        // A view, not a copy. HttpRequest documents its header list as immutable, and this one is:
        // the ArrayList is created here, the visitor that fills it is not retained past the parse
        // call (Http1Codec#parseHeaders says so), and the only reference left is the view itself.
        // Should this list ever escape by another route, copy it again.
        return new ReadResult(
            method,
                requestLine.target(),
                parseVersion(requestLine.version()),
                Collections.unmodifiableList(headers),
                headersEnd,
                bodyLength,
                headersEnd + bodyLength,
                codec.isKeepAlive());
    }

    private static HttpMethod parseMethod(String token) {
        try {
            return HttpMethod.valueOf(token);
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    private static HttpVersion parseVersion(String token) {
        return switch (token) {
            case "HTTP/1.0" -> HttpVersion.HTTP_1_0;
            case "HTTP/1.1" -> HttpVersion.HTTP_1_1;
            default -> HttpVersion.HTTP_1_1;
        };
    }
}
