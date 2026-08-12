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

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/**
 * Assembles the HTTP/1.1 response header block.
 *
 * <p>Split out of {@code CommunityHttpExchange} so that class is about one thing — respond-once
 * semantics and the connection's fate — while header assembly lives next to the encoder it drives.
 */
final class CommunityHttpResponseHeaders {

    private static final String HEADER_CONTENT_LENGTH = "Content-Length";
    private static final String HEADER_CONNECTION = "Connection";
    private static final String HEADER_CLOSE = "close";
    private static final String HEADER_KEEP_ALIVE = "keep-alive";

    private CommunityHttpResponseHeaders() {
    }

    /**
     * Writes the header block, supplying {@code Content-Length} and {@code Connection} only where the
     * response did not already state them — an explicit header from the application wins, with one
     * exception: a {@code Connection} header is dropped when {@code keepAlive} is false. Connection
     * lifetime is the kernel's to state, and a drain that has resolved to close the socket must not
     * announce keep-alive on it. See the loop below.
     *
     * @param keepAlive whether the connection survives this response; see
     *                  {@code CommunityHttpExchange#resolveKeepAlive()} for when that is decided
     * @return the position just past the terminating blank line
     */
    /* default */ static long write(MemorySegment target,
                                    List<HttpHeader> headers,
                                    int bodyBytes,
                                    long position,
                                    boolean keepAlive) {
        long pos = position;
        boolean hasContentLength = false;
        boolean hasConnection = false;
        for (HttpHeader header : headers) {
            hasContentLength |= header.nameEqualsIgnoreCase(HEADER_CONTENT_LENGTH);
            boolean isConnection = header.nameEqualsIgnoreCase(HEADER_CONNECTION);
            // An application Connection header is honoured only while the kernel is still willing to
            // keep the connection. When it is not — a graceful drain resolves keepAlive to false and
            // then closes the socket regardless — letting the application's value through announces
            // keep-alive on a connection about to be torn down, which is the one thing the drain's
            // Connection: close exists to prevent. Connection lifetime is the kernel's to state.
            if (isConnection && !keepAlive) {
                continue;
            }
            hasConnection |= isConnection;
            pos = Http1ResponseEncoder.writeHeader(target, pos, header.name(), header.value());
        }

        if (!hasContentLength) {
            pos = Http1ResponseEncoder.writeHeader(
                    target, pos, HEADER_CONTENT_LENGTH, Integer.toString(bodyBytes));
        }
        if (!hasConnection) {
            pos = Http1ResponseEncoder.writeHeader(
                    target, pos, HEADER_CONNECTION, keepAlive ? HEADER_KEEP_ALIVE : HEADER_CLOSE);
        }
        return Http1ResponseEncoder.writeHeaderEnd(target, pos);
    }

    /** Concatenates application headers with encoder-supplied ones, allocating only when both exist. */
    /* default */ static List<HttpHeader> merge(List<HttpHeader> typedHeaders,
                                                List<HttpHeader> encodedHeaders) {
        if (typedHeaders.isEmpty()) {
            return encodedHeaders;
        }
        if (encodedHeaders.isEmpty()) {
            return typedHeaders;
        }
        List<HttpHeader> merged = new ArrayList<>(typedHeaders.size() + encodedHeaders.size());
        merged.addAll(typedHeaders);
        merged.addAll(encodedHeaders);
        return merged;
    }
}
