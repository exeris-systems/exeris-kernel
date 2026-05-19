/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.http.client;

import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpClientRequestEnricher;
import eu.exeris.kernel.spi.http.HttpEncodedBody;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpRequestBodyEncoder;
import eu.exeris.kernel.spi.http.HttpRequestBodyEncoderRegistry;
import eu.exeris.kernel.spi.http.HttpRequestEncodingContext;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpResponseBodyDecoder;
import eu.exeris.kernel.spi.http.HttpResponseBodyDecoderRegistry;
import eu.exeris.kernel.spi.http.HttpResponseDecodingContext;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Tier-neutral typed HTTP client façade over the {@link HttpClientEngine} SPI.
 *
 * <p>Composes:
 * <ul>
 *   <li>an {@link HttpClientEngine} driver (Community HTTP/1.1+2 today,
 *       Enterprise HTTP/3 in the future) — never directly referenced by
 *       generated client code,</li>
 *   <li>an {@link HttpRequestBodyEncoderRegistry} for outbound payloads
 *       (Jackson JSON by default in Community; alternative format drivers
 *       slot in behind the same SPI),</li>
 *   <li>an {@link HttpResponseBodyDecoderRegistry} for inbound payloads,</li>
 *   <li>an {@link HttpClientRequestEnricher} (ADR-032) for implicit context
 *       propagation (tenant identity, principal identity, future W3C
 *       {@code traceparent}).</li>
 * </ul>
 *
 * <h2>Threading model</h2>
 * <p>Every verb call blocks the calling virtual thread through
 * {@link HttpClientEngine#send(HttpRequest)}. No implicit async; concurrency is
 * the caller's responsibility via additional virtual threads.
 *
 * <h2>Memory ownership</h2>
 * <p>Request body: allocated by the resolved encoder via the supplied
 * {@link MemoryAllocator}; ownership transfers to the engine on {@code send}.
 * Response body: returned by the engine, decoded, closed in a {@code finally};
 * callers never see the response buffer directly.
 *
 * <h2>Error mapping</h2>
 * <p>Non-2xx responses raise {@link WebClientException} carrying status code +
 * raw body. {@link WebClientException#isNotFound()} is the canonical 404
 * predicate consumed by generator-emitted entity clients.
 *
 * <p>Replaces the (removed) {@code CommunityWebClient} per ADR-034 —
 * {@code KernelClientGenerator}-emitted code references this tier-neutral
 * symbol so no {@code Community*} identity leaks into application source.
 *
 * @since 0.8.0
 */
// CyclomaticComplexity: execute()/buildRequest() codify the documented status-mapping +
// codec-resolution + buffer-ownership-transfer paths from ADR-034 §4; the branches are
// contract surface, not accidental complexity.
// CouplingBetweenObjects: facade composes the four SPI seams listed in ADR-034 §4
// (HttpClientEngine, request-body codec, response-body codec, request enricher) plus the
// shared HTTP carrier types — the coupling is the façade contract.
@SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.CouplingBetweenObjects"})
public final class KernelWebClient {

    private static final HttpVersion DEFAULT_VERSION = HttpVersion.HTTP_1_1;
    private static final String CONTENT_LENGTH = "content-length";
    private static final String CONTENT_TYPE = "content-type";
    private static final String ACCEPT = "accept";
    private static final String APPLICATION_JSON = "application/json";

    private static final int HTTP_2XX_LOWER = 200;
    private static final int HTTP_2XX_UPPER = 300;
    private static final int HTTP_NOT_FOUND = 404;

    private static final List<HttpHeader> ACCEPT_JSON_HEADERS =
            List.of(new HttpHeader(ACCEPT, APPLICATION_JSON));

    private static final byte[] EMPTY_BYTES = new byte[0];
    private static final long EMPTY_SIZE = 0L;

    private final HttpClientEngine engine;
    private final MemoryAllocator allocator;
    private final HttpRequestBodyEncoderRegistry requestEncoders;
    private final HttpResponseBodyDecoderRegistry responseDecoders;
    private final HttpClientRequestEnricher enricher;

    /**
     * Creates a client with explicit enricher composition (ADR-032).
     *
     * @param engine           a started client engine targeting a single host
     * @param allocator        the kernel memory allocator (used by the resolved encoder)
     * @param requestEncoders  registry of outbound body encoders
     * @param responseDecoders registry of inbound body decoders
     * @param enricher         outbound request enricher (use {@link HttpClientRequestEnricher#noop()} for none)
     */
    public KernelWebClient(
            HttpClientEngine engine,
            MemoryAllocator allocator,
            HttpRequestBodyEncoderRegistry requestEncoders,
            HttpResponseBodyDecoderRegistry responseDecoders,
            HttpClientRequestEnricher enricher) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.allocator = Objects.requireNonNull(allocator, "allocator must not be null");
        this.requestEncoders = Objects.requireNonNull(requestEncoders, "requestEncoders must not be null");
        this.responseDecoders = Objects.requireNonNull(responseDecoders, "responseDecoders must not be null");
        this.enricher = Objects.requireNonNull(enricher, "enricher must not be null");
    }

    /**
     * Convenience constructor — defaults the enricher to
     * {@link HttpClientRequestEnricher#noop()}.
     *
     * @param engine           a started client engine targeting a single host
     * @param allocator        the kernel memory allocator
     * @param requestEncoders  registry of outbound body encoders
     * @param responseDecoders registry of inbound body decoders
     */
    public KernelWebClient(
            HttpClientEngine engine,
            MemoryAllocator allocator,
            HttpRequestBodyEncoderRegistry requestEncoders,
            HttpResponseBodyDecoderRegistry responseDecoders) {
        this(engine, allocator, requestEncoders, responseDecoders, HttpClientRequestEnricher.noop());
    }

    /**
     * Issues an HTTP {@code GET} against {@code path} and deserialises the
     * response body as {@code responseType}.
     *
     * @param <T>          response payload type
     * @param path         request-target path (relative to the engine's target host)
     * @param responseType target type, or {@code Void.class} to discard
     * @return the deserialised payload, or {@code null} when {@code responseType == Void.class}
     * @throws WebClientException on any non-2xx response
     */
    public <T> T get(String path, Class<T> responseType) {
        return execute(HttpMethod.GET, path, null, responseType);
    }

    /**
     * Issues an HTTP {@code POST} with a typed body and deserialises the
     * response body as {@code responseType}.
     *
     * @param <T>          response payload type
     * @param path         request-target path
     * @param body         request payload (encoded via the request encoder registry); must not be null
     * @param responseType target type, or {@code Void.class} to discard
     * @return the deserialised payload, or {@code null} when {@code responseType == Void.class}
     * @throws WebClientException on any non-2xx response or codec failure
     */
    public <T> T post(String path, Object body, Class<T> responseType) {
        Objects.requireNonNull(body, "body must not be null for POST");
        return execute(HttpMethod.POST, path, body, responseType);
    }

    /**
     * Issues an HTTP {@code PATCH} with a typed body. Same contract as
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

    // PMD.UseTryWithResources: response body ownership transfers from engine.send() return,
    // so the buffer lifecycle is finalised in an explicit finally rather than try-with-resources.
    @SuppressWarnings("PMD.UseTryWithResources")
    private <T> T execute(HttpMethod method, String path, Object requestBody, Class<T> responseType) {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(responseType, "responseType must not be null");

        HttpRequest request = buildRequest(method, path, requestBody);
        request = enricher.enrich(request);

        HttpResponse response = engine.send(request);

        int status = response.status().code();
        LoanedBuffer body = response.body();
        try {
            byte[] responseBytes = (body == null) ? EMPTY_BYTES : readAll(body);
            boolean success = status >= HTTP_2XX_LOWER && status < HTTP_2XX_UPPER;
            if (!success) {
                String responseBodyText = new String(responseBytes, StandardCharsets.UTF_8);
                throw new WebClientException(status, responseBodyText,
                        "HTTP " + status + " " + response.status().reasonPhrase(), null);
            }
            if (responseType == Void.class) {
                return null;
            }
            if (responseBytes.length == 0) {
                throw new WebClientException(status, "",
                        "Empty response body cannot deserialize to " + responseType.getName(), null);
            }
            return decodeSuccessBody(response, body, responseBytes, responseType, status);
        } finally {
            if (body != null) {
                body.close();
            }
        }
    }

    // PMD.AvoidCatchingGenericException: decoder drivers wrap their binding exceptions in
    // RuntimeException (typically IllegalStateException) per ADR-034 §3; we re-wrap with
    // status + raw body context for caller diagnostics.
    @SuppressWarnings({"unchecked", "PMD.AvoidCatchingGenericException"})
    private <T> T decodeSuccessBody(HttpResponse response,
                                    LoanedBuffer body,
                                    byte[] responseBytes,
                                    Class<T> responseType,
                                    int status) {
        String contentType = response.headers().stream()
                .filter(h -> h.nameEqualsIgnoreCase(CONTENT_TYPE))
                .map(HttpHeader::value)
                .findFirst()
                .orElse(null);
        HttpResponseBodyDecoder decoder = responseDecoders.resolve(responseType, contentType);
        if (decoder == null) {
            throw new WebClientException(status,
                    new String(responseBytes, StandardCharsets.UTF_8),
                    "No response body decoder for type " + responseType.getName()
                            + " (content-type=" + contentType + ")", null);
        }
        // SPI decoder is intentionally generics-free; cast confined to this single site (ADR-034 §3).
        try {
            Object decoded = decoder.decode(body, responseType,
                    new HttpResponseDecodingContext(status, response.headers(), allocator));
            return (T) decoded;
        } catch (WebClientException wce) {
            throw wce;
        } catch (RuntimeException ex) {
            throw new WebClientException(status,
                    new String(responseBytes, StandardCharsets.UTF_8),
                    "Failed to deserialize response of type " + responseType.getName(), ex);
        }
    }

    // PMD.AvoidCatchingGenericException: outbound buffer must be released if anything throws
    // between allocate() and engine ownership transfer.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private HttpRequest buildRequest(HttpMethod method, String path, Object body) {
        if (body == null) {
            return HttpRequest.noBody(method, path, DEFAULT_VERSION, ACCEPT_JSON_HEADERS);
        }
        HttpRequestBodyEncoder encoder = requestEncoders.resolve(body.getClass());
        if (encoder == null) {
            throw new WebClientException(0, "",
                    "No request body encoder for type " + body.getClass().getName(), null);
        }
        HttpRequestEncodingContext ctx = new HttpRequestEncodingContext(method, path, allocator);
        HttpEncodedBody encoded;
        try {
            encoded = encoder.encode(body, ctx);
        } catch (RuntimeException ex) {
            throw new WebClientException(0, "",
                    "Failed to serialize request body of type " + body.getClass().getName(), ex);
        }
        LoanedBuffer buf = encoded.body();
        try {
            long bodySize = buf == null ? EMPTY_SIZE : buf.size();
            List<HttpHeader> merged = new ArrayList<>(encoded.headers().size() + ACCEPT_JSON_HEADERS.size() + 1);
            merged.addAll(encoded.headers());
            merged.addAll(ACCEPT_JSON_HEADERS);
            merged.add(new HttpHeader(CONTENT_LENGTH, Long.toString(bodySize)));
            return new HttpRequest(method, path, DEFAULT_VERSION, List.copyOf(merged), buf);
        } catch (RuntimeException ex) {
            if (buf != null) {
                buf.close();   // engine never received ownership; release the loan locally.
            }
            throw ex;
        }
    }

    private static byte[] readAll(LoanedBuffer body) {
        long size = body.size();
        if (size == EMPTY_SIZE) {
            return EMPTY_BYTES;
        }
        byte[] out = new byte[Math.toIntExact(size)];
        MemorySegment.copy(body.segment(), 0L, MemorySegment.ofArray(out), 0L, size);
        return out;
    }

    /**
     * Thrown by {@link KernelWebClient} when the response status is non-2xx or
     * when body codec resolution / encoding / decoding fails. Carries the wire
     * status and the raw response body for caller diagnostics.
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
