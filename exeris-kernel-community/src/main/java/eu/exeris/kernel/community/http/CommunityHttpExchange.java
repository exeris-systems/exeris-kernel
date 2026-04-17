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
import eu.exeris.kernel.spi.http.HttpEncodedBody;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpResponseBodyEncoder;
import eu.exeris.kernel.spi.http.HttpResponseBodyEncoderRegistry;
import eu.exeris.kernel.spi.http.HttpResponseEncodingContext;
import eu.exeris.kernel.spi.http.HttpTypedResponse;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.transport.TransportStream;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

final class CommunityHttpExchange implements HttpExchange {

    private static final String HEADER_CONTENT_LENGTH = "Content-Length";
    private static final String HEADER_CONNECTION = "Connection";
    private static final String HEADER_CLOSE = "close";
    private static final int RESPONSE_HEADROOM_BYTES = 2048;

    private final HttpRequest request;
    private final TransportStream stream;
    private final MemoryAllocator allocator;
    private final boolean keepAlive;
    private final HttpResponseBodyEncoderRegistry encoderRegistry;
    private final AtomicBoolean responded = new AtomicBoolean(false);

    /* default */ CommunityHttpExchange(HttpRequest request,
                          TransportStream stream,
                          MemoryAllocator allocator,
                          boolean keepAlive,
                          HttpResponseBodyEncoderRegistry encoderRegistry) {
        this.request = Objects.requireNonNull(request, "request must not be null");
        this.stream = Objects.requireNonNull(stream, "stream must not be null");
        this.allocator = Objects.requireNonNull(allocator, "allocator must not be null");
        this.keepAlive = keepAlive;
        this.encoderRegistry = Objects.requireNonNull(encoderRegistry, "encoderRegistry must not be null");
    }

    @Override
    public HttpRequest request() {
        return request;
    }

    @Override
    public void respond(HttpResponse response) {
        Objects.requireNonNull(response, "response must not be null");
        if (!responded.compareAndSet(false, true)) {
            throw new IllegalStateException("respond() already called for this exchange");
        }

        LoanedBuffer responseBody = response.body();
        int bodyBytes = responseBody == null ? 0 : (int) responseBody.size();
        int bufferSize = RESPONSE_HEADROOM_BYTES + bodyBytes;

        try (LoanedBuffer bodyOwned = responseBody;
             LoanedBuffer outbound = allocator.allocateNetwork(bufferSize)) {
            long position = 0L;
            position = Http1ResponseEncoder.writeStatusLine(
                    outbound.segment(),
                    position,
                    response.status().code(),
                    response.status().reasonPhrase());
            position = writeHeaders(outbound.segment(), response.headers(), bodyBytes, position);

            if (bodyOwned != null && bodyBytes > 0) {
                MemorySegment.copy(bodyOwned.segment(), 0, outbound.segment(), position, bodyBytes);
                position += bodyBytes;
            }

            outbound.setSize(position);
            stream.write(outbound.segment(), (int) position);
        }
        if (!keepAlive) {
            stream.close();
        }
    }

    /* default */ boolean isResponded() {
        return responded.get();
    }

    /* default */ boolean keepAlive() {
        return keepAlive;
    }

    @Override
    public void respond(HttpTypedResponse typedResponse) {
        Objects.requireNonNull(typedResponse, "typedResponse must not be null");
        HttpResponseBodyEncoder encoder = encoderRegistry.resolve(
                typedResponse.payload() == null ? Void.class : typedResponse.payload().getClass());
        if (encoder == null) {
            throw new UnsupportedOperationException(
                    "No encoder registered for payload type: " +
                    (typedResponse.payload() == null ? "null" : typedResponse.payload().getClass().getName()));
        }
        HttpResponseEncodingContext ctx = new HttpResponseEncodingContext(request, allocator);
        HttpEncodedBody encoded = encoder.encode(typedResponse.payload(), ctx);
        List<HttpHeader> mergedHeaders = mergeHeaders(typedResponse.headers(), encoded.headers());
        try (LoanedBuffer encodedBody = encoded.body()) {
            respond(new HttpResponse(typedResponse.status(), request.version(), mergedHeaders, encodedBody));
        }
    }

    private static List<HttpHeader> mergeHeaders(List<HttpHeader> typedHeaders, List<HttpHeader> encodedHeaders) {
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

    private long writeHeaders(MemorySegment target,
                              List<HttpHeader> headers,
                              int bodyBytes,
                              long position) {
        long pos = position;
        boolean hasContentLength = false;
        boolean hasConnection = false;
        for (HttpHeader header : headers) {
            if (header.nameEqualsIgnoreCase(HEADER_CONTENT_LENGTH)) {
                hasContentLength = true;
            }
            if (header.nameEqualsIgnoreCase(HEADER_CONNECTION)) {
                hasConnection = true;
            }
            pos = Http1ResponseEncoder.writeHeader(target, pos, header.name(), header.value());
        }

        if (!hasContentLength) {
            pos = Http1ResponseEncoder.writeHeader(
                    target,
                    pos,
                    HEADER_CONTENT_LENGTH,
                    Integer.toString(bodyBytes));
        }
        if (!hasConnection) {
            pos = Http1ResponseEncoder.writeHeader(
                    target,
                    pos,
                    HEADER_CONNECTION,
                    keepAlive ? "keep-alive" : HEADER_CLOSE);
        }
        return Http1ResponseEncoder.writeHeaderEnd(target, pos);
    }
}