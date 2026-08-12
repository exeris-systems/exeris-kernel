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
import eu.exeris.kernel.core.transport.TransportScopes;
import eu.exeris.kernel.core.transport.scheduler.DrainCoordinator;
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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

final class CommunityHttpExchange implements HttpExchange {

    private static final int RESPONSE_HEADROOM_BYTES = 2048;

    private final HttpRequest request;
    private final TransportStream stream;
    private final MemoryAllocator allocator;
    private final boolean requestedKeepAlive;
    private final DrainCoordinator drainCoordinator;
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
        this.requestedKeepAlive = keepAlive;
        // Captured here, not read in respond(): this constructor runs on the stream's own Virtual
        // Thread where the scope is bound, while an application is free to complete its exchange
        // from elsewhere. An unbound slot (a carrier that drains without a coordinator, or a unit
        // test) leaves this null and the connection behaves exactly as the request asked.
        this.drainCoordinator = TransportScopes.DRAIN_COORDINATOR.isBound()
                ? TransportScopes.DRAIN_COORDINATOR.get()
                : null;
        this.encoderRegistry = Objects.requireNonNull(encoderRegistry, "encoderRegistry must not be null");
    }

    @Override
    public HttpRequest request() {
        return request;
    }

    @Override
    public void respond(HttpResponse response) {
        Objects.requireNonNull(response, "response must not be null");
        claimResponse();
        respondInternalClaimed(response);
    }

    @Override
    public void respond(HttpTypedResponse typedResponse) {
        Objects.requireNonNull(typedResponse, "typedResponse must not be null");
        Object payload = typedResponse.payload();
        Class<?> payloadType = payload == null ? Void.class : payload.getClass();
        HttpResponseBodyEncoder encoder = encoderRegistry.resolve(payloadType);
        if (encoder == null) {
            throw new UnsupportedOperationException("No encoder registered for payload type: " + payloadType.getName());
        }
        HttpResponseEncodingContext context = new HttpResponseEncodingContext(request, allocator);
        HttpEncodedBody encoded = encoder.encode(typedResponse.payload(), context);
        List<HttpHeader> mergedHeaders =
                CommunityHttpResponseHeaders.merge(typedResponse.headers(), encoded.headers());
        try {
            respond(new HttpResponse(typedResponse.status(), request.version(), mergedHeaders, encoded.body()));
        } catch (IllegalStateException ex) {
            if (encoded.body() != null) {
                encoded.body().close();
            }
            throw ex;
        }
    }

    /* default */ boolean isResponded() {
        return responded.get();
    }

    private void claimResponse() {
        if (!responded.compareAndSet(false, true)) {
            throw new IllegalStateException("respond() already called for this exchange");
        }
    }

    private void respondInternalClaimed(HttpResponse response) {
        LoanedBuffer responseBody = response.body();
        int bodyBytes = responseBody == null ? 0 : (int) responseBody.size();
        int bufferSize = RESPONSE_HEADROOM_BYTES + bodyBytes;
        // Resolved once, here: the header written below and the socket teardown after it must agree,
        // and the drain flag can flip between them.
        boolean keepAlive = resolveKeepAlive();

        try (LoanedBuffer bodyOwned = responseBody;
             LoanedBuffer outbound = allocator.allocateNetwork(bufferSize)) {
            long position = 0L;
            position = Http1ResponseEncoder.writeStatusLine(
                    outbound.segment(),
                    position,
                    response.status().code(),
                    response.status().reasonPhrase());
            position = CommunityHttpResponseHeaders.write(
                    outbound.segment(), response.headers(), bodyBytes, position, keepAlive);

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

    /**
     * Whether this response may keep the connection alive.
     *
     * <p>Asked <em>now</em>, not when the request was parsed. The request that most needs to tell its
     * peer to let go is the one already in flight when graceful shutdown began; answered at parse
     * time it always gets the pre-shutdown answer, so the peer keeps a pooled connection it will
     * never be asked about again and the next shutdown waits on it as idle.
     */
    private boolean resolveKeepAlive() {
        return requestedKeepAlive && (drainCoordinator == null || !drainCoordinator.isDraining());
    }
}
