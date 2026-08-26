/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

import java.util.List;
import java.util.Objects;

/**
 * SPI: Outbound HTTP request enricher — derives a new {@link HttpRequest} from
 * the request the façade produced, typically by appending headers
 * (tenant identity, principal identity, future W3C {@code traceparent}, etc.).
 *
 * <p>Contract: per ADR-032,
 * <ul>
 *   <li>{@link #enrich(HttpRequest)} MUST return a new immutable {@link HttpRequest}
 *       — no in-place mutation seam exists.</li>
 *   <li>Implementations MUST NOT read, retain, or close the request body
 *       {@code LoanedBuffer}. Body ownership belongs to the calling site and
 *       transfers to the engine on {@code send}.</li>
 *   <li>Any header value containing CR ({@code 0x0D}), LF ({@code 0x0A}), or NUL
 *       ({@code 0x00}) MUST cause the enricher to throw
 *       {@link IllegalArgumentException} before returning (CWE-93 outbound guard
 *       symmetric with {@code Http1RequestParser}).</li>
 *   <li>Implementations run synchronously on the caller's virtual thread; no
 *       thread-spawning, no blocking I/O.</li>
 * </ul>
 *
 * @since 0.8.0
 */
@FunctionalInterface
public interface HttpClientRequestEnricher {

    /**
     * Returns a derived {@link HttpRequest} — typically the input request with
     * additional headers appended.
     *
     * @param request the request constructed by the façade; never {@code null}
     * @return a new request to send (may be the same instance when nothing was added)
     */
    HttpRequest enrich(HttpRequest request);

    /**
     * Returns an enricher that returns the input request unchanged.
     *
     * @return no-op enricher
     */
    static HttpClientRequestEnricher noop() {
        return request -> request;
    }

    /**
     * Returns an enricher that applies each member in list order; each member
     * sees the output of the previous one. An empty list is equivalent to
     * {@link #noop()}.
     *
     * @param enrichers ordered list of enrichers; non-null, may be empty
     * @return composed enricher
     */
    static HttpClientRequestEnricher chain(List<HttpClientRequestEnricher> enrichers) {
        Objects.requireNonNull(enrichers, "enrichers must not be null");
        if (enrichers.isEmpty()) {
            return noop();
        }
        List<HttpClientRequestEnricher> snapshot = List.copyOf(enrichers);
        return request -> {
            HttpRequest current = request;
            for (HttpClientRequestEnricher enricher : snapshot) {
                current = enricher.enrich(current);
            }
            return current;
        };
    }
}
