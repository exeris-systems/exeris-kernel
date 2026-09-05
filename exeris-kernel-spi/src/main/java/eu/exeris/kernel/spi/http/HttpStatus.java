/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

import java.util.Objects;

/**
 * SPI: HTTP response status — a numeric code and its reason phrase (RFC 9110 §15).
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Standard {@code record}. No {@code synchronized}, no identity {@code ==},
 * no {@code System.identityHashCode()}. Scalarises cleanly via C2 JIT Escape Analysis
 * on hot-path response encoder paths. Will migrate to {@code value record} (JEP 401)
 * once mainline GA is reached.
 *
 * <h2>Pre-allocated Sentinels</h2>
 * <p>Common status codes are exposed as {@code static final} constants. The JVM
 * constant-folds these into call sites via C2 escape analysis — zero allocation
 * per response encode.
 *
 * @param code         numeric status code (100–599); values outside this range are rejected
 * @param reasonPhrase human-readable phrase; non-null (may be blank for HTTP/2 where it is
 *                     not transmitted on the wire, but must be non-null for API consistency)
 * @since 0.5
 */
public record HttpStatus(int code, String reasonPhrase) {

    // =========================================================================
    // 1xx Informational
    // =========================================================================
    /** RFC 9110 §15.2.1 — request received, continue processing. */
    public static final HttpStatus CONTINUE = new HttpStatus(100, "Continue");
    /** RFC 9110 §15.2.2 — server switching protocols per Upgrade header. */
    public static final HttpStatus SWITCHING_PROTOCOLS = new HttpStatus(101, "Switching Protocols");
    /** RFC 8297 — early hints. */
    public static final HttpStatus EARLY_HINTS = new HttpStatus(103, "Early Hints");

    // =========================================================================
    // 2xx Successful
    // =========================================================================
    /** RFC 9110 §15.3.1 — request succeeded. */
    @SuppressWarnings("PMD.ShortVariable") // "OK" is the canonical HTTP status name (RFC 9110 §15.3.1)
    public static final HttpStatus OK = new HttpStatus(200, "OK");
    /** RFC 9110 §15.3.2 — resource created. */
    public static final HttpStatus CREATED = new HttpStatus(201, "Created");
    /** RFC 9110 §15.3.3 — accepted but not yet processed. */
    public static final HttpStatus ACCEPTED = new HttpStatus(202, "Accepted");
    /** RFC 9110 §15.3.5 — success with no response body. */
    public static final HttpStatus NO_CONTENT = new HttpStatus(204, "No Content");
    /** RFC 9110 §15.3.6 — partial content (range request). */
    public static final HttpStatus PARTIAL_CONTENT = new HttpStatus(206, "Partial Content");

    // =========================================================================
    // 3xx Redirection
    // =========================================================================
    /** RFC 9110 §15.4.2 — permanently moved; update links. */
    public static final HttpStatus MOVED_PERMANENTLY = new HttpStatus(301, "Moved Permanently");
    /** RFC 9110 §15.4.3 — temporarily found at different URI. */
    public static final HttpStatus FOUND = new HttpStatus(302, "Found");
    /** RFC 9110 §15.4.5 — not modified; use cached representation. */
    public static final HttpStatus NOT_MODIFIED = new HttpStatus(304, "Not Modified");
    /** RFC 9110 §15.4.8 — temporary redirect; method preserved. */
    public static final HttpStatus TEMPORARY_REDIRECT = new HttpStatus(307, "Temporary Redirect");
    /** RFC 9110 §15.4.9 — permanent redirect; method preserved. */
    public static final HttpStatus PERMANENT_REDIRECT = new HttpStatus(308, "Permanent Redirect");

    // =========================================================================
    // 4xx Client Errors
    // =========================================================================
    /** RFC 9110 §15.5.1 — malformed request syntax. */
    public static final HttpStatus BAD_REQUEST = new HttpStatus(400, "Bad Request");
    /** RFC 9110 §15.5.2 — authentication required. */
    public static final HttpStatus UNAUTHORIZED = new HttpStatus(401, "Unauthorized");
    /** RFC 9110 §15.5.4 — server refuses to fulfil request. */
    public static final HttpStatus FORBIDDEN = new HttpStatus(403, "Forbidden");
    /** RFC 9110 §15.5.5 — target resource not found. */
    public static final HttpStatus NOT_FOUND = new HttpStatus(404, "Not Found");
    /** RFC 9110 §15.5.6 — method not allowed for target resource. */
    public static final HttpStatus METHOD_NOT_ALLOWED = new HttpStatus(405, "Method Not Allowed");
    /** RFC 9110 §15.5.9 — request timed out. */
    public static final HttpStatus REQUEST_TIMEOUT = new HttpStatus(408, "Request Timeout");
    /** RFC 9110 §15.5.10 — state conflict (e.g. concurrent edit). */
    public static final HttpStatus CONFLICT = new HttpStatus(409, "Conflict");
    /** RFC 9110 §15.5.12 — resource permanently gone (no forwarding). */
    public static final HttpStatus GONE = new HttpStatus(410, "Gone");
    /** RFC 9110 §15.5.14 — payload exceeds server limit. */
    public static final HttpStatus CONTENT_TOO_LARGE = new HttpStatus(413, "Content Too Large");
    /** RFC 9110 §15.5.16 — URI too long. */
    public static final HttpStatus URI_TOO_LONG = new HttpStatus(414, "URI Too Long");
    /** RFC 9110 §15.5.20 — request rejected due to rate-limiting policy. */
    public static final HttpStatus TOO_MANY_REQUESTS = new HttpStatus(429, "Too Many Requests");
    /** RFC 9110 §15.5.22 — header section too large. */
    public static final HttpStatus REQUEST_HEADER_FIELDS_TOO_LARGE =
            new HttpStatus(431, "Request Header Fields Too Large");

    // =========================================================================
    // 5xx Server Errors
    // =========================================================================
    /** RFC 9110 §15.6.1 — unexpected condition; generic server error. */
    public static final HttpStatus INTERNAL_SERVER_ERROR =
            new HttpStatus(500, "Internal Server Error");
    /** RFC 9110 §15.6.2 — server does not support the request method. */
    public static final HttpStatus NOT_IMPLEMENTED = new HttpStatus(501, "Not Implemented");
    /** RFC 9110 §15.6.3 — bad response from upstream server. */
    public static final HttpStatus BAD_GATEWAY = new HttpStatus(502, "Bad Gateway");
    /** RFC 9110 §15.6.4 — server temporarily unable to handle requests. */
    public static final HttpStatus SERVICE_UNAVAILABLE =
            new HttpStatus(503, "Service Unavailable");
    /** RFC 9110 §15.6.5 — upstream server timed out. */
    public static final HttpStatus GATEWAY_TIMEOUT = new HttpStatus(504, "Gateway Timeout");
    /** RFC 9110 §15.6.6 — HTTP version not supported. */
    public static final HttpStatus HTTP_VERSION_NOT_SUPPORTED =
            new HttpStatus(505, "HTTP Version Not Supported");

    // =========================================================================
    // Compact constructor — validation only, placed AFTER static fields per
    // Checkstyle DeclarationOrder (static variables → constructors → methods)
    // =========================================================================

    public HttpStatus {
        if (code < 100 || code > 599) {
            throw new IllegalArgumentException(
                    "HTTP status code must be in range [100, 599], got: " + code);
        }
        Objects.requireNonNull(reasonPhrase, "reasonPhrase must not be null");
    }

    // =========================================================================
    // Classification helpers — hot-path friendly, zero-alloc
    // =========================================================================

    /**
     * Returns {@code true} if the status is in the 1xx Informational class.
     *
     * @return {@code true} for codes 100–199
     */
    public boolean isInformational() {
        return code >= 100 && code < 200;
    }

    /**
     * Returns {@code true} if the status is in the 2xx Successful class.
     *
     * @return {@code true} for codes 200–299
     */
    public boolean isSuccessful() {
        return code >= 200 && code < 300;
    }

    /**
     * Returns {@code true} if the status is in the 3xx Redirection class.
     *
     * @return {@code true} for codes 300–399
     */
    public boolean isRedirection() {
        return code >= 300 && code < 400;
    }

    /**
     * Returns {@code true} if the status is in the 4xx Client Error class.
     *
     * @return {@code true} for codes 400–499
     */
    public boolean isClientError() {
        return code >= 400 && code < 500;
    }

    /**
     * Returns {@code true} if the status is in the 5xx Server Error class.
     *
     * @return {@code true} for codes 500–599
     */
    public boolean isServerError() {
        return code >= 500 && code < 600;
    }

    /**
     * Returns {@code true} if the status indicates any error (4xx or 5xx).
     *
     * @return {@code true} for codes 400–599
     */
    public boolean isError() {
        return code >= 400;
    }
}


