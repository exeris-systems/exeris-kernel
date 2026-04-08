/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings({
    "PMD.CommentDefaultAccessModifier",
    "PMD.NullAssignment"
})
final class InMemoryHttp2Exchange implements HttpExchange {
    private final HttpRequest request;
    private final MemoryAllocator allocator;
    private final HttpResponseBodyEncoderRegistry encoderRegistry;
    private final AtomicBoolean responded = new AtomicBoolean(false);
    private HttpResponse capturedResponse;

    InMemoryHttp2Exchange(HttpRequest request,
                          MemoryAllocator allocator,
                          HttpResponseBodyEncoderRegistry encoderRegistry) {
        this.request = request;
        this.allocator = allocator;
        this.encoderRegistry = encoderRegistry;
        this.capturedResponse = null;
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
        List<HttpHeader> mergedHeaders = mergeHeaders(typedResponse.headers(), encodedBody.headers());
        try {
            respond(new HttpResponse(typedResponse.status(), request.version(), mergedHeaders, encodedBody.body()));
        } catch (RuntimeException | Error ex) {
            if (encodedBody.body() != null) {
                encodedBody.body().close();
            }
            throw ex;
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

    boolean isResponded() {
        return responded.get();
    }

    HttpResponse capturedResponse() {
        return capturedResponse;
    }
}
