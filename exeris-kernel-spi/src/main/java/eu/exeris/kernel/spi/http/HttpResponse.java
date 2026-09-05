/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.util.List;
import java.util.Objects;

/**
 * SPI: An immutable HTTP response carrier (RFC 9110 §15).
 *
 * <h2>Zero-Copy Body</h2>
 * <p>The response body, when present, is carried as a {@link LoanedBuffer} backed by
 * a Panama {@link java.lang.foreign.MemorySegment}. The {@link HttpServerEngine}
 * implementation takes ownership of the buffer on
 * {@link HttpExchange#respond(HttpResponse)} and releases it after the wire write
 * completes. Callers MUST NOT close the buffer after passing it to
 * {@link HttpExchange#respond(HttpResponse)}.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Standard {@code record}. No identity operations ({@code ==},
 * {@code System.identityHashCode()}, {@code synchronized}) on instances.
 * Will migrate to {@code value record} (JEP 401) once mainline GA is reached.
 *
 * @param status  response status; non-null
 * @param version protocol version of the response; non-null
 * @param headers immutable list of response header fields; non-null, may be empty
 * @param body    response body buffer, or {@code null} if the response has no body
 * @since 0.5
 */
public record HttpResponse(
        HttpStatus status,
        HttpVersion version,
        List<HttpHeader> headers,
        LoanedBuffer body
) {

    public HttpResponse {
        Objects.requireNonNull(status,  "status must not be null");
        Objects.requireNonNull(version, "version must not be null");
        Objects.requireNonNull(headers, "headers must not be null");
        // body is intentionally nullable — null signals no response body
    }

    /**
     * Returns {@code true} if this response carries a non-null body buffer.
     *
     * @return {@code true} if {@link #body()} is non-null
     */
    public boolean hasBody() {
        return body != null;
    }

    /**
     * Creates a bodyless response with an empty header list.
     *
     * @param status  response status
     * @param version protocol version
     * @return a new bodyless {@code HttpResponse} with no headers
     */
    public static HttpResponse noBody(HttpStatus status, HttpVersion version) {
        return new HttpResponse(status, version, List.of(), null);
    }

    /**
     * Creates a bodyless response with the given headers.
     *
     * @param status  response status
     * @param version protocol version
     * @param headers header list
     * @return a new bodyless {@code HttpResponse}
     */
    public static HttpResponse noBody(HttpStatus status, HttpVersion version, List<HttpHeader> headers) {
        return new HttpResponse(status, version, headers, null);
    }
}
