/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

/**
 * Construction-time validation for {@link HttpConfig}.
 *
 * <p>Extracted from the record itself, and the trigger is worth recording because it is a
 * measurement rather than a preference. {@code HttpConfig} sat just under the PMD class-complexity
 * ceiling of 30; ADR-074 added one record component and — unavoidably — a ten-argument
 * backward-compatibility bridge constructor, which together took it to 36. The increment is
 * dominated by the bridge, which the compatibility guarantee requires and which therefore cannot be
 * reduced, so shrinking the validators would not have helped and suppressing the rule would have
 * hidden a ceiling the class had already reached.
 *
 * <p>Package-private and non-instantiable: this is {@code HttpConfig}'s own validation, moved rather
 * than published.
 *
 * <p>The class carries a {@code CyclomaticComplexity} suppression, and it is the one place it
 * belongs. Extracting these validators did not reduce the metric, it MOVED it: {@code HttpConfig}
 * measured 36 with them and {@code HttpConfigValidation} measures 35 without anything else, so the
 * ~35 branches were always the validation itself. Splitting them further to get under a threshold
 * of 30 would be complexity theatre — validating eleven configuration fields, each with its own
 * refusal message, is legitimately that many decisions. Suppressing it HERE keeps the record itself
 * gated, so {@code HttpConfig} growing new logic still trips the rule.
 *
 * @since 0.12.0
 */
@SuppressWarnings("PMD.CyclomaticComplexity") // see the class javadoc: measured, not waved through
final class HttpConfigValidation {

    private static final int MIN_SERVER_PORT = 0;
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65_535;
    private static final int MIN_CONNECTIONS = 1;

    private HttpConfigValidation() {
        throw new AssertionError("no instances");
    }

    /**
     * Rejects an authority that carries a scheme or a path.
     *
     * <p>{@code "https://payments.internal/api"} is what an operator writes when the key is named
     * after a URL, and it would otherwise be passed to the transport as a host name and fail at
     * connect time with a DNS error naming the whole string. Refusing it here names the actual
     * mistake. {@code null} stays legal — it means no default peer is configured.
     */
    /* default */ static void validateDefaultAuthority(String defaultAuthority) {
        if (defaultAuthority == null) {
            return;
        }
        if (defaultAuthority.isBlank()) {
            throw new IllegalArgumentException(
                    "http.client.defaultAuthority must not be blank; omit the key instead");
        }
        if (defaultAuthority.indexOf('/') >= 0) {
            throw new IllegalArgumentException(
                    "http.client.defaultAuthority is an authority (host or host:port), not a URL: "
                            + defaultAuthority);
        }
        // The port is checked HERE and not only when a request is sent. This validator exists to
        // catch operator mistakes at construction, where the message can name the key; deferring a
        // missing port to the first request would report it as a per-request failure instead, which
        // is the same defect ADR-071 fixed for the header limits.
        int close = defaultAuthority.startsWith("[") ? defaultAuthority.indexOf(']') : -1;
        int separator = close >= 0 ? defaultAuthority.indexOf(':', close) : defaultAuthority.lastIndexOf(':');
        if (separator <= 0 || separator == defaultAuthority.length() - 1) {
            throw new IllegalArgumentException(
                    "http.client.defaultAuthority must carry an explicit port (host:port): "
                            + defaultAuthority);
        }
        if (close < 0 && defaultAuthority.lastIndexOf(':', separator - 1) >= 0) {
            throw new IllegalArgumentException(
                    "http.client.defaultAuthority with an IPv6 address must be bracketed as "
                            + "[address]:port — unbracketed is ambiguous: " + defaultAuthority);
        }
    }

    /**
     * Refuses a non-positive HTTP/2 header-block bound.
     *
     * <p>Protective rather than capacity, so ADR-071's ruling applies: {@code 0} is not "unlimited",
     * it is a bound that refuses every header block, and a protection must not be switchable off by
     * a value that looks like an empty template slot.
     */
    /* default */ static void validateHeaderBlockSize(int maxHeaderBlockSize) {
        if (maxHeaderBlockSize <= 0) {
            throw new IllegalArgumentException(
                    "http.maxHeaderBlockSize must be > 0 (0 refuses every HTTP/2 header block, it is "
                            + "not unlimited), got: " + maxHeaderBlockSize);
        }
    }

    /* default */ static void validatePort(HttpMode mode, int port, String bindHost) {
        if (mode == HttpMode.SERVER || mode == HttpMode.DUAL) {
            validateServerDualBinding(bindHost, port);
        } else {
            validateClientPort(port);
        }
    }

    /* default */ static void validateServerDualBinding(String bindHost, int port) {
        if (bindHost == null || bindHost.isBlank()) {
            throw new IllegalArgumentException(
                    "bindHost must not be null or blank for SERVER/DUAL mode");
        }
        if (port < MIN_SERVER_PORT || port > MAX_PORT) {
            throw new IllegalArgumentException(
                    "port must be in range [0, 65535] for SERVER/DUAL mode (0 = ephemeral), got: " + port);
        }
    }

    /* default */ static void validateClientPort(int port) {
        if (port != -1 && (port < MIN_PORT || port > MAX_PORT)) {
            throw new IllegalArgumentException(
                    "port must be -1 (sentinel) or 1-65535 for CLIENT mode, got: " + port);
        }
    }

    /* default */ static void validateConnectionLimits(int maxConnections, long idleTimeoutMillis) {
        if (maxConnections < MIN_CONNECTIONS) {
            throw new IllegalArgumentException(
                    "maxConnections must be >= 1, got: " + maxConnections);
        }
        if (idleTimeoutMillis < 0) {
            throw new IllegalArgumentException(
                    "idleTimeoutMillis must be >= 0 (0 = no timeout), got: " + idleTimeoutMillis);
        }
    }

    /* default */ static void validateRequestLimits(int maxRequestHeaderCount, int maxRequestHeaderSize,
                                               long maxRequestBodyBytes) {
        // > 0, not >= 0 (ADR-071). Zero is not "unlimited" for either bound -- the parser refuses
        // the first header at maxHeaders 0 and any non-empty field at maxHeaderSize 0, so a config
        // carrying it serves nothing but 400s. Refused at construction, where the message names the
        // key, rather than per request, where it looks like a client problem.
        if (maxRequestHeaderCount <= 0) {
            throw new IllegalArgumentException(
                    "maxRequestHeaderCount must be > 0 (0 refuses every request, it is not "
                            + "unlimited), got: " + maxRequestHeaderCount);
        }
        if (maxRequestHeaderSize <= 0) {
            throw new IllegalArgumentException(
                    "maxRequestHeaderSize must be > 0 (0 refuses every request, it is not "
                            + "unlimited), got: " + maxRequestHeaderSize);
        }
        if (maxRequestBodyBytes < -1) {
            throw new IllegalArgumentException(
                    "maxRequestBodyBytes must be >= -1 (-1 = unlimited), got: " + maxRequestBodyBytes);
        }
    }
}
