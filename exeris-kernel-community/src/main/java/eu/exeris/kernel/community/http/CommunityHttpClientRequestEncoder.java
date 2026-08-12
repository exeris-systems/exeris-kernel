/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.core.http.http1.Http1ResponseEncoder;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.transport.TransportConnection;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Package-private static encoder for outbound HTTP/1.x requests issued by
 * {@link CommunityHttpClientEngine}.
 *
 * <p>Extracted from {@link CommunityHttpClientEngine} in v0.8 Sprint 3 (QA-015)
 * to close the engine's {@code PMD.GodClass} suppression block. Owns the
 * request-line + headers + body byte-layout into a {@link MemorySegment}.
 *
 * <p>Layout (RFC 9112):
 * <pre>
 *   METHOD SP REQUEST-TARGET SP HTTP/VERSION CRLF
 *   header-name: header-value CRLF
 *   ...
 *   CRLF
 *   [body bytes]
 * </pre>
 *
 * <p>Default headers added when absent from {@link HttpRequest#headers()}:
 * {@code Host: host:port} (from {@link TransportConnection}), {@code Content-Length: N},
 * {@code Connection: close}.
 */
final class CommunityHttpClientRequestEncoder {

    private static final String HEADER_HOST = "Host";
    private static final String HEADER_CONTENT_LENGTH = "Content-Length";
    private static final String HEADER_CONNECTION = "Connection";
    private static final String HEADER_CLOSE = "close";

    private CommunityHttpClientRequestEncoder() {
        // package-private static utility — never instantiated.
    }

    /**
     * Writes the full outbound HTTP/1.x request into {@code seg} starting at
     * offset 0. Returns the position one past the last written byte.
     */
    /* default */ static long writeRequest(MemorySegment seg,
                                           HttpRequest request,
                                           TransportConnection connection,
                                           int bodyBytes) {
        long pos = writeRequestLine(seg, request);
        HeaderWriteResult headerWriteResult = writeRequestHeaders(seg, pos, request);
        pos = writeDefaultRequestHeaders(
                seg,
                headerWriteResult.position(),
                headerWriteResult.presence(),
                connection,
                bodyBytes);
        return writeRequestBody(seg, pos, request, bodyBytes);
    }

    private static long writeRequestLine(MemorySegment seg, HttpRequest request) {
        long pos = 0L;
        pos = writeAscii(seg, pos, request.method().name());
        seg.set(ValueLayout.JAVA_BYTE, pos, (byte) ' ');
        pos += 1;
        pos = writeAscii(seg, pos, request.path());
        seg.set(ValueLayout.JAVA_BYTE, pos, (byte) ' ');
        pos += 1;
        pos = writeAscii(seg, pos, versionToken(request.version()));
        return Http1ResponseEncoder.writeHeaderEnd(seg, pos);
    }

    private static HeaderWriteResult writeRequestHeaders(MemorySegment seg, long startPos, HttpRequest request) {
        long pos = startPos;
        boolean hasHost = false;
        boolean hasContentLength = false;
        boolean hasConnection = false;
        for (HttpHeader header : request.headers()) {
            if (header.nameEqualsIgnoreCase(HEADER_HOST)) {
                hasHost = true;
            }
            if (header.nameEqualsIgnoreCase(HEADER_CONTENT_LENGTH)) {
                hasContentLength = true;
            }
            if (header.nameEqualsIgnoreCase(HEADER_CONNECTION)) {
                hasConnection = true;
            }
            pos = Http1ResponseEncoder.writeHeader(seg, pos, header.name(), header.value());
        }
        return new HeaderWriteResult(pos, new HeaderPresence(hasHost, hasContentLength, hasConnection));
    }

    private static long writeDefaultRequestHeaders(MemorySegment seg,
                                                   long startPos,
                                                   HeaderPresence presence,
                                                   TransportConnection connection,
                                                   int bodyBytes) {
        long pos = startPos;
        if (!presence.hasHost()) {
            pos = Http1ResponseEncoder.writeHeader(seg, pos, HEADER_HOST,
                    connection.remoteAddress() + ":" + connection.remotePort());
        }
        if (!presence.hasContentLength()) {
            pos = Http1ResponseEncoder.writeHeader(seg, pos, HEADER_CONTENT_LENGTH,
                    Integer.toString(bodyBytes));
        }
        if (!presence.hasConnection()) {
            pos = Http1ResponseEncoder.writeHeader(seg, pos, HEADER_CONNECTION, HEADER_CLOSE);
        }
        return Http1ResponseEncoder.writeHeaderEnd(seg, pos);
    }

    private static long writeRequestBody(MemorySegment seg, long startPos, HttpRequest request, int bodyBytes) {
        long pos = startPos;
        if (bodyBytes > 0) {
            MemorySegment.copy(request.body().segment(), 0L, seg, pos, bodyBytes);
            pos += bodyBytes;
        }
        return pos;
    }

    private static long writeAscii(MemorySegment seg, long pos, String value) {
        for (int i = 0; i < value.length(); i++) {
            seg.set(ValueLayout.JAVA_BYTE, pos + i, (byte) value.charAt(i));
        }
        return pos + value.length();
    }

    private static String versionToken(HttpVersion version) {
        return switch (version) {
            case HTTP_1_0 -> "HTTP/1.0";
            case HTTP_1_1 -> "HTTP/1.1";
            case HTTP_2 -> "HTTP/2";
            case HTTP_3 -> "HTTP/3";
        };
    }

    /* default */ value record HeaderPresence(boolean hasHost, boolean hasContentLength, boolean hasConnection) {
    }

    /* default */ value record HeaderWriteResult(long position, HeaderPresence presence) {
    }
}
