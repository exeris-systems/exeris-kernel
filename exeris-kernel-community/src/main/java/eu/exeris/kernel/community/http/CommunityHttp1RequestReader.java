/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.core.http.http1.Http1Codec;
import eu.exeris.kernel.core.http.http1.Http1RequestParser;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.util.ArrayList;
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
        long headersEnd = codec.parseHeaders(
                aggregate.segment(), headerStart, total - headerStart);
        if (headersEnd < 0) {
            return null;
        }

        int bodyLength = (int) Math.max(codec.pendingContentLength(), 0);
        if (total < headersEnd + bodyLength) {
            return null;
        }

        List<HttpHeader> headers = new ArrayList<>();
        Http1RequestParser.parseHeaders(
                aggregate.segment(),
                headerStart,
                total - headerStart,
                (name, value) -> headers.add(new HttpHeader(name, value)));

        return new ReadResult(
                parseMethod(requestLine.method()),
                requestLine.target(),
                parseVersion(requestLine.version()),
                List.copyOf(headers),
                headersEnd,
                bodyLength,
                headersEnd + bodyLength,
                codec.isKeepAlive());
    }

    private static HttpMethod parseMethod(String token) {
        try {
            return HttpMethod.valueOf(token);
        } catch (IllegalArgumentException _) {
            return HttpMethod.GET;
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
