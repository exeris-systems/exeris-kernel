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
 * completes.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Standard {@code record}. No identity operations ({@code ==},
 * {@code System.identityHashCode()}, {@code synchronized}) on instances.
 * Will migrate to {@code value record} (JEP 401) once mainline GA is reached.
 *
 * <p><b>Allocation:</b> allocates (the carrier and its header list); the body is carried by
 * reference
 * <p><b>Ownership:</b> the caller owns {@link #body()} until
 * {@link HttpExchange#respond(HttpResponse)}, from which point the engine owns it and releases it
 * once the write completes
 *
 * @param status  response status; non-null
 * @param version protocol version of the response; non-null
 * @param headers immutable list of response header fields; non-null, may be empty
 * @param body    response body buffer, or {@code null} if the response has no body
 * @apiNote Do not close or retain the body buffer once the response has been passed to
 *          {@link HttpExchange#respond(HttpResponse)} — a second release corrupts the pool's
 *          accounting, and a retained reference reads another response's bytes.
 * @since 0.5
 */
public record HttpResponse(
        HttpStatus status,
        HttpVersion version,
        List<HttpHeader> headers,
        LoanedBuffer body
) {

    /**
     * Rejects the three components a response head cannot be written without; {@code body} stays
     * nullable, because that is how a bodyless response is expressed.
     *
     * @throws NullPointerException if {@code status}, {@code version} or {@code headers} is
     *                              {@code null}
     */
    public HttpResponse {
        Objects.requireNonNull(status,  "status must not be null");
        Objects.requireNonNull(version, "version must not be null");
        Objects.requireNonNull(headers, "headers must not be null");
        // body is intentionally nullable — null signals no response body
    }

    /**
     * Returns whether a body buffer travels with this response — {@code false} means the engine has
     * nothing to write after the head and nothing to release.
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
