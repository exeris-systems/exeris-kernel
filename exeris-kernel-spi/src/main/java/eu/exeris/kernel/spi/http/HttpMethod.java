/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.http;

/**
 * SPI: HTTP request method as defined by RFC 9110 §9.
 *
 * <h2>The Wall</h2>
 * <p>This enum is implementation-blind: it carries no HTTP/2 pseudo-header logic,
 * no wire encoding, and no transport coupling. Community and Enterprise tiers map
 * their wire representations to this enum independently.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Enum constants are JVM singletons — no identity operations ({@code ==} comparisons
 * are valid because enums guarantee identity equality by specification).
 *
 * @since 0.5.0
 */
public enum HttpMethod {

    /** RFC 9110 §9.3.1 — safe, idempotent, cacheable. */
    GET,
    /** RFC 9110 §9.3.3 — may have request body, not idempotent, not safe. */
    POST,
    /** RFC 9110 §9.3.4 — idempotent, replaces target resource entirely. */
    PUT,
    /** RFC 9110 §9.3.5 — idempotent, removes target resource. */
    DELETE,
    /** RFC 5789 — partial update; NOT idempotent by default. */
    PATCH,
    /** RFC 9110 §9.3.2 — same as GET but response body omitted. */
    HEAD,
    /** RFC 9110 §9.3.7 — requests permitted communication options. */
    OPTIONS,
    /** RFC 9110 §9.3.8 — remote, application-level loop-back request. */
    TRACE,
    /** RFC 9110 §9.3.6 — establishes a tunnel (e.g. HTTPS-over-HTTP-proxy). */
    CONNECT;

    /**
     * Returns {@code true} if this method is defined as safe (RFC 9110 §9.2.1).
     *
     * <p>Safe methods do not modify resource state: GET, HEAD, OPTIONS, TRACE.
     *
     * @return {@code true} if this is a safe method
     */
    public boolean isSafe() {
        return this == GET || this == HEAD || this == OPTIONS || this == TRACE;
    }

    /**
     * Returns {@code true} if this method is defined as idempotent (RFC 9110 §9.2.2).
     *
     * <p>Idempotent methods produce the same result when applied multiple times.
     * All safe methods are idempotent; additionally PUT and DELETE are idempotent.
     *
     * @return {@code true} if this is an idempotent method
     */
    public boolean isIdempotent() {
        return isSafe() || this == PUT || this == DELETE;
    }

    /**
     * Returns {@code true} if a request body is semantically meaningful for this method.
     *
     * <p>POST, PUT, and PATCH carry a request body in normal use. For other methods,
     * request bodies are unusual (GET, DELETE) or forbidden (HEAD, TRACE) per RFC 9110.
     *
     * @return {@code true} if a request body is expected
     */
    public boolean hasTypicalRequestBody() {
        return this == POST || this == PUT || this == PATCH;
    }
}

