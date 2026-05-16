/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.transport.http3.client;

import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Kernel-side typed HTTP client façade over {@link HttpClientEngine} SPI.
 *
 * <p>Layered API: callers issue verb-level CRUD against a relative path; the client
 * marshals request bodies through Jackson 3, copies them into an off-heap
 * {@link LoanedBuffer}, hands the buffer to the engine, deserialises the response
 * body back through Jackson, and releases the response buffer. Non-2xx responses
 * map to {@link WebClientException} carrying status code + response body —
 * {@link WebClientException#isNotFound()} is the canonical 404 predicate
 * (used by {@code exeris-tooling} {@code KernelClientGenerator}-emitted client code).
 *
 * <h2>Threading model</h2>
 * <p>Every method call blocks the calling virtual thread through
 * {@link HttpClientEngine#send(HttpRequest)}. No implicit async wrapping;
 * concurrency is the caller's responsibility via additional virtual threads.
 *
 * <h2>Memory ownership</h2>
 * <p>Request body: allocated via the supplied {@link MemoryAllocator}, populated
 * by Jackson serialisation, and ownership transferred to the engine on
 * {@code send}. Response body: returned by the engine, deserialised by Jackson,
 * then released by {@link LoanedBuffer#close()} in a {@code finally} clause —
 * callers never see the response buffer directly.
 *
 * <h2>Decision rationale</h2>
 * <p>See ADR-026 for the package-name (historical {@code http3} retained for
 * generator stability), tier-placement (Community module — Jackson already on
 * classpath), and rejected alternatives.
 *
 * @since 0.8.0
 */
public final class ExerisWebClient {

    private static final HttpVersion DEFAULT_VERSION = HttpVersion.HTTP_1_1;
    private static final String CONTENT_TYPE = "content-type";
    private static final String ACCEPT = "accept";
    private static final String CONTENT_LENGTH = "content-length";
    private static final String APPLICATION_JSON = "application/json";

    private static final int HTTP_2XX_LOWER = 200;
    private static final int HTTP_2XX_UPPER = 300;
    private static final int HTTP_NOT_FOUND = 404;

    private static final List<HttpHeader> ACCEPT_JSON_HEADERS =
            List.of(new HttpHeader(ACCEPT, APPLICATION_JSON));

    private static final byte[] EMPTY_BYTES = new byte[0];

    private final HttpClientEngine engine;
    private final MemoryAllocator allocator;
    private final ObjectMapper mapper;

    /**
     * Creates a client backed by the given engine, allocator, and JSON binder.
     *
     * @param engine    a pre-configured client engine targeting a single host
     * @param allocator the kernel memory allocator (request body materialisation)
     * @param mapper    Jackson {@link ObjectMapper} — application-owned (modules,
     *                  naming strategies, date handlers are caller's choice)
     */
    public ExerisWebClient(HttpClientEngine engine, MemoryAllocator allocator, ObjectMapper mapper) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.allocator = Objects.requireNonNull(allocator, "allocator must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    /**
     * Issues an HTTP {@code GET} against {@code path} and deserialises the
     * response body as {@code responseType}.
     *
     * @param <T>          response payload type
     * @param path         request-target path (relative to the engine's target host)
     * @param responseType target Jackson type, or {@code Void.class} to discard
     * @return the deserialised payload, or {@code null} when {@code responseType == Void.class}
     * @throws WebClientException on any non-2xx response
     */
    public <T> T get(String path, Class<T> responseType) {
        return execute(HttpMethod.GET, path, null, responseType);
    }

    /**
     * Issues an HTTP {@code POST} with a JSON-serialised body and deserialises the
     * response body as {@code responseType}.
     *
     * @param <T>          response payload type
     * @param path         request-target path
     * @param body         request payload (Jackson-serialised); must not be null
     * @param responseType target Jackson type, or {@code Void.class} to discard
     * @return the deserialised payload, or {@code null} when {@code responseType == Void.class}
     * @throws WebClientException on any non-2xx response or serialisation failure
     */
    public <T> T post(String path, Object body, Class<T> responseType) {
        Objects.requireNonNull(body, "body must not be null for POST");
        return execute(HttpMethod.POST, path, body, responseType);
    }

    /**
     * Issues an HTTP {@code PATCH} with a JSON-serialised body. Same contract as
     * {@link #post(String, Object, Class)} otherwise.
     */
    public <T> T patch(String path, Object body, Class<T> responseType) {
        Objects.requireNonNull(body, "body must not be null for PATCH");
        return execute(HttpMethod.PATCH, path, body, responseType);
    }

    /**
     * Issues an HTTP {@code DELETE} against {@code path}. Most callers pass
     * {@code Void.class} as {@code responseType} to discard any response body.
     *
     * @return the deserialised payload, or {@code null} when {@code responseType == Void.class}
     * @throws WebClientException on any non-2xx response
     */
    public <T> T delete(String path, Class<T> responseType) {
        return execute(HttpMethod.DELETE, path, null, responseType);
    }

    @SuppressWarnings("PMD.UseTryWithResources") // response body ownership transfers from engine.send() return.
    private <T> T execute(HttpMethod method, String path, Object requestBody, Class<T> responseType) {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(responseType, "responseType must not be null");

        HttpRequest request = buildRequest(method, path, requestBody);
        HttpResponse response = engine.send(request);

        int status = response.status().code();
        LoanedBuffer body = response.body();
        try {
            byte[] responseBytes = (body == null) ? new byte[0] : readAll(body);
            if (status >= HTTP_2XX_LOWER && status < HTTP_2XX_UPPER) {
                if (responseType == Void.class) {
                    return null;
                }
                return parseBody(responseBytes, responseType, status);
            }
            String responseBody = new String(responseBytes, StandardCharsets.UTF_8);
            throw new WebClientException(status, responseBody,
                    "HTTP " + status + " " + response.status().reasonPhrase(), null);
        } finally {
            if (body != null) {
                body.close();
            }
        }
    }

    // PMD.AvoidCatchingGenericException: outbound buffer must be released if anything throws
    // between allocate() and engine ownership transfer.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private HttpRequest buildRequest(HttpMethod method, String path, Object body) {
        if (body == null) {
            return HttpRequest.noBody(method, path, DEFAULT_VERSION, ACCEPT_JSON_HEADERS);
        }
        byte[] payload;
        try {
            payload = mapper.writeValueAsBytes(body);
        } catch (JacksonException ex) {
            throw new WebClientException(0, "",
                    "Failed to serialize request body of type " + body.getClass().getName(), ex);
        }
        LoanedBuffer buf = allocator.allocateNetwork(payload.length);
        try {
            MemorySegment.copy(MemorySegment.ofArray(payload), 0, buf.segment(), 0, payload.length);
            buf.setSize(payload.length);
            List<HttpHeader> headers = List.of(
                    new HttpHeader(CONTENT_TYPE, APPLICATION_JSON),
                    new HttpHeader(ACCEPT, APPLICATION_JSON),
                    new HttpHeader(CONTENT_LENGTH, Integer.toString(payload.length))
            );
            return new HttpRequest(method, path, DEFAULT_VERSION, headers, buf);
        } catch (RuntimeException ex) {
            buf.close();   // engine never received ownership; release the loan locally.
            throw ex;
        }
    }

    private <T> T parseBody(byte[] bytes, Class<T> responseType, int status) {
        if (bytes.length == 0) {
            throw new WebClientException(status, "",
                    "Empty response body cannot deserialize to " + responseType.getName(), null);
        }
        try {
            return mapper.readValue(bytes, responseType);
        } catch (JacksonException ex) {
            throw new WebClientException(status, new String(bytes, StandardCharsets.UTF_8),
                    "Failed to deserialize response of type " + responseType.getName(), ex);
        }
    }

    private static byte[] readAll(LoanedBuffer body) {
        long size = body.size();
        if (size == EMPTY_BYTES.length) {
            return EMPTY_BYTES;
        }
        byte[] out = new byte[Math.toIntExact(size)];
        MemorySegment.copy(body.segment(), 0L, MemorySegment.ofArray(out), 0L, size);
        return out;
    }

    /**
     * Thrown by {@link ExerisWebClient} when the response status is non-2xx or
     * when JSON serialisation / deserialisation fails. Carries the wire status
     * and the raw response body for caller diagnostics.
     *
     * <p>Generated client code (e.g., {@code KernelClientGenerator} output)
     * inspects {@link #isNotFound()} to map 404 responses to
     * {@link java.util.Optional#empty()} at the entity layer.
     */
    public static final class WebClientException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final int status;
        private final String responseBody;

        /* default */ WebClientException(int status, String responseBody, String message, Throwable cause) {
            super(message, cause);
            this.status = status;
            this.responseBody = responseBody == null ? "" : responseBody;
        }

        /** Wire status code at the time of the failure; {@code 0} if no response was received. */
        public int status() {
            return status;
        }

        /** Raw response body decoded as UTF-8, or empty string when unavailable. */
        public String responseBody() {
            return responseBody;
        }

        /** {@code true} when {@link #status()} equals 404 — the canonical "not found" predicate. */
        public boolean isNotFound() {
            return status == HTTP_NOT_FOUND;
        }
    }
}
