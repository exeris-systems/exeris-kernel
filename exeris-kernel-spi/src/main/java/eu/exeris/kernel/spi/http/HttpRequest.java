/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.http;

import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * SPI: An immutable HTTP request carrier (RFC 9110 §7).
 *
 * <h2>Zero-Copy Body</h2>
 * <p>The request body, when present, is carried as a {@link LoanedBuffer} backed by
 * a Panama {@link java.lang.foreign.MemorySegment}. No heap copy is made — the
 * buffer slice originates from the transport's slab pool and is reference-counted.
 * The caller (transport or codec) retains ownership; the {@link HttpHandler} that
 * consumes this request MUST call {@link LoanedBuffer#retain()} if it outlives
 * the current {@link HttpExchange} scope.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Standard {@code record}. No identity operations ({@code ==},
 * {@code System.identityHashCode()}, {@code synchronized}) on instances.
 * Scalarises via C2 JIT Escape Analysis when used transiently within a handler scope.
 * Will migrate to {@code value record} (JEP 401) once mainline GA is reached.
 *
 * <h2>Thread Safety</h2>
 * <p>This record is immutable. The {@link LoanedBuffer} body must only be accessed
 * from the virtual thread that owns the corresponding {@link HttpExchange}.
 *
 * @param method  HTTP method; non-null
 * @param path    request-target path component, e.g. {@code "/api/v1/users?page=1"}; non-null
 * @param version protocol version; non-null
 * @param headers immutable list of header fields; non-null, may be empty
 * @param body    request body buffer, or {@code null} if the request has no body
 * @since 0.5.0
 */
public record HttpRequest(
        HttpMethod method,
        String path,
        HttpVersion version,
        List<HttpHeader> headers,
        LoanedBuffer body
) {

    public HttpRequest {
        Objects.requireNonNull(method,  "method must not be null");
        Objects.requireNonNull(path,    "path must not be null");
        Objects.requireNonNull(version, "version must not be null");
        Objects.requireNonNull(headers, "headers must not be null");
        // body is intentionally nullable — null signals no request body
    }

    /**
     * Returns the value of the first header whose name matches {@code name}
     * case-insensitively, or {@link Optional#empty()} if no such header exists.
     *
     * <p>Iterates the header list linearly — acceptable for the typical small
     * header count ({@code ≤ 100}) encountered in HTTP requests.
     *
     * @param name header name to find; must not be {@code null}
     * @return optional header value
     */
    public Optional<String> firstHeader(String name) {
        for (HttpHeader h : headers) {
            if (h.nameEqualsIgnoreCase(name)) {
                return Optional.of(h.value());
            }
        }
        return Optional.empty();
    }

    /**
     * Returns {@code true} if this request carries a non-null body buffer.
     *
     * @return {@code true} if {@link #body()} is non-null
     */
    public boolean hasBody() {
        return body != null;
    }

    /**
     * Creates a bodyless request.
     *
     * @param method  HTTP method
     * @param path    request path
     * @param version protocol version
     * @param headers header list
     * @return a new {@code HttpRequest} with no body
     */
    public static HttpRequest noBody(HttpMethod method, String path, HttpVersion version, List<HttpHeader> headers) {
        return new HttpRequest(method, path, version, headers, null);
    }
}
