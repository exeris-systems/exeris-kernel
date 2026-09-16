/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.websocket;

import eu.exeris.kernel.core.http.http1.Http1RequestParser;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.transport.TransportStream;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the opening HTTP request off the wire. Parsing only — the admission decision is
 * {@link CommunityWebSocketUpgrade}'s.
 *
 * <p>Reuses the Core HTTP/1 parser rather than growing a second one: a handshake request is an
 * ordinary HTTP request, and two parsers would be two places for a smuggling bug to live.
 */
final class CommunityWebSocketUpgradeRequest {

    private static final int MAX_REQUEST_BYTES = 8 * 1024;
    private static final int READ_CHUNK = 1024;

    private CommunityWebSocketUpgradeRequest() {
    }

    /**
     * A completed request, or the reason there is none: still incomplete, or complete and unusable.
     *
     * <p>The constants are {@code STILL_INCOMPLETE} / {@code NOT_USABLE} rather than mirroring the
     * component name: a field named {@code MALFORMED} beside an accessor named {@code malformed()}
     * reads as the same thing twice and is one typo from meaning the opposite.
     */
    /* default */ record Parsed(HttpRequest request, boolean malformed) {
        /* default */ static final Parsed STILL_INCOMPLETE = new Parsed(null, false);
        /* default */ static final Parsed NOT_USABLE = new Parsed(null, true);
    }

    /* default */ static Parsed read(TransportStream stream, MemoryAllocator allocator) {
        // Off-heap because TransportStream.read documents its target as a LoanedBuffer's segment,
        // and the NIO fallback that would otherwise absorb the mistake is the slow path. One
        // allocation for the life of the handshake, released before the connection starts framing.
        try (LoanedBuffer buffer = allocator.allocateNetwork(MAX_REQUEST_BYTES)) {
            MemorySegment segment = buffer.segment();
            int total = 0;
            while (total < MAX_REQUEST_BYTES) {
                int chunk = Math.min(READ_CHUNK, MAX_REQUEST_BYTES - total);
                int read = stream.read(segment.asSlice(total, chunk), chunk);
                if (read < 0) {
                    return Parsed.STILL_INCOMPLETE;
                }
                total += read;
                Parsed parsed = tryParse(segment, total);
                if (parsed != Parsed.STILL_INCOMPLETE) {
                    return parsed;
                }
            }
            // The cap was reached with no terminal CRLF CRLF. Complete enough to answer: a handshake
            // request that does not fit 8 KiB is not one this engine will ever accept.
            return Parsed.NOT_USABLE;
        }
    }

    private static Parsed tryParse(MemorySegment segment, int length) {
        Http1RequestParser.RequestLine line =
                Http1RequestParser.parseRequestLine(segment, 0, length);
        if (line == null) {
            return Parsed.STILL_INCOMPLETE;
        }
        long headerStart = findHeaderStart(segment, length);
        if (headerStart < 0) {
            return Parsed.STILL_INCOMPLETE;
        }
        List<HttpHeader> headers = new ArrayList<>();
        long end = Http1RequestParser.parseHeaders(segment, headerStart, length - headerStart,
                (name, value) -> headers.add(new HttpHeader(name, value)));
        if (end < 0) {
            return Parsed.STILL_INCOMPLETE;
        }
        // Only GET can ever open a WebSocket (RFC 6455 §4.1), so any other method is resolved here
        // rather than carried into an HttpRequest a callback would then have to reject. That also
        // avoids inventing an HttpMethod constant for a token the enum does not name.
        if (!"GET".equals(line.method())) {
            return Parsed.NOT_USABLE;
        }
        // No body: a handshake request carries none, and reading one would mean trusting a
        // Content-Length on a request the server has not yet agreed to speak anything with.
        return new Parsed(new HttpRequest(HttpMethod.GET, line.target(),
                HttpVersion.HTTP_1_1, List.copyOf(headers), null), false);
    }

    private static long findHeaderStart(MemorySegment segment, int length) {
        // Long throughout: the method returns a long offset and MemorySegment indexes in longs, so
        // computing in int and widening at the return was the wrong way round even where the values
        // cannot overflow. Doing the arithmetic in the type the result is used at removes the
        // question instead of answering it in a comment.
        for (long i = 0; i + 1 < length; i++) {
            if (segment.get(ValueLayout.JAVA_BYTE, i) == '\r'
                    && segment.get(ValueLayout.JAVA_BYTE, i + 1) == '\n') {
                return i + 2;
            }
        }
        return -1;
    }
}
