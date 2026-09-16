/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.http.HttpEncodedBody;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpResponseBodyEncoder;
import eu.exeris.kernel.spi.http.HttpResponseBodyEncoderRegistry;
import eu.exeris.kernel.spi.http.HttpResponseEncodingContext;
import eu.exeris.kernel.spi.http.HttpTypedResponse;
import eu.exeris.kernel.spi.memory.MemoryAllocator;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Community: the HTTP/2 {@link HttpExchange} — captures the handler's response in memory instead of
 * writing it to the wire, because {@link CommunityHttp2SessionProcessor} still has to frame it as
 * HEADERS(+CONTINUATION)/DATA and encode it through the connection's shared HPACK encoder before any
 * bytes reach the socket.
 *
 * <p>{@link #respond(HttpResponse)} may be called exactly once, enforced the same way as the HTTP/1.1
 * exchange: an atomic claim that {@link #isResponded()} exposes, with a second call throwing
 * {@link IllegalStateException}. {@link #capturedResponse()} returns the captured response, or
 * {@code null} until {@code respond} has been called.
 */
/* default */ final class InMemoryHttp2Exchange implements HttpExchange {
    private final HttpRequest request;
    private final MemoryAllocator allocator;
    private final HttpResponseBodyEncoderRegistry encoderRegistry;
    private final AtomicBoolean responded = new AtomicBoolean(false);
    private HttpResponse capturedResponse;

    /* default */ InMemoryHttp2Exchange(HttpRequest request,
                                        MemoryAllocator allocator,
                                        HttpResponseBodyEncoderRegistry encoderRegistry) {
        this.request = request;
        this.allocator = allocator;
        this.encoderRegistry = encoderRegistry;
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
        capturedResponse = response;
    }

    @Override
    public void respond(HttpTypedResponse typedResponse) {
        Objects.requireNonNull(typedResponse, "typedResponse must not be null");
        HttpResponseBodyEncoder encoder = encoderRegistry.resolve(
                typedResponse.payload() == null ? Void.class : typedResponse.payload().getClass());
        if (encoder == null) {
            throw new UnsupportedOperationException(
                    "No encoder registered for payload type: " +
                    (typedResponse.payload() == null
                            ? "null"
                            : typedResponse.payload().getClass().getName()));
        }
        HttpResponseEncodingContext context = new HttpResponseEncodingContext(request, allocator);
        HttpEncodedBody encodedBody = encoder.encode(typedResponse.payload(), context);
        List<HttpHeader> mergedHeaders =
                CommunityHttpResponseHeaders.merge(typedResponse.headers(), encodedBody.headers());
        boolean responseCaptured = false;
        try { //NOPMD UseTryWithResources — response body ownership transfers on success; close only on failure
            respond(new HttpResponse(typedResponse.status(), request.version(), mergedHeaders, encodedBody.body()));
            responseCaptured = true;
        } finally {
            if (!responseCaptured && encodedBody.body() != null) {
                encodedBody.body().close();
            }
        }
    }

    /* default */ boolean isResponded() {
        return responded.get();
    }

    /* default */ HttpResponse capturedResponse() {
        return capturedResponse;
    }
}
