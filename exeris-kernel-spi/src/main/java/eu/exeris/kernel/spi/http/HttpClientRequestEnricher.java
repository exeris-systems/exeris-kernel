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
 * <p><b>Allocation:</b> allocates (one derived {@link HttpRequest} and its header list per call;
 * the body is carried over by reference and never copied)
 * <p><b>Thread confinement:</b> owner thread — an enricher runs synchronously on the virtual thread
 * issuing the request, and must not hand work to another thread
 * <p><b>Ownership:</b> the enricher owns nothing — the request body buffer belongs to the calling
 * site; this interface does not establish who releases it after {@code send}
 *
 * @implSpec Per ADR-032, an implementation:
 *           <ul>
 *             <li>MUST return a new immutable {@link HttpRequest} — no in-place mutation seam
 *                 exists;</li>
 *             <li>MUST NOT read, retain, or close the request body {@code LoanedBuffer};</li>
 *             <li>MUST throw {@link IllegalArgumentException} before returning if a header value it
 *                 adds contains CR ({@code 0x0D}), LF ({@code 0x0A}) or NUL ({@code 0x00}) — the
 *                 CWE-93 outbound guard, symmetric with the inbound parser's;</li>
 *             <li>MUST run synchronously on the caller's virtual thread: no thread-spawning, no
 *                 blocking I/O.</li>
 *           </ul>
 * @implNote The Community reference client engine does not close the request body buffer after
 *           {@code send} returns.
 * @since 0.8
 */
@FunctionalInterface
public interface HttpClientRequestEnricher {

    /**
     * Returns a derived {@link HttpRequest} — typically the input request with
     * additional headers appended.
     *
     * @param request the request constructed by the façade; never {@code null}
     * @return a new request to send (may be the same instance when nothing was added)
     * @throws IllegalArgumentException if a header value the enricher adds carries CR, LF or NUL
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
     * @throws NullPointerException if {@code enrichers} or any element is {@code null}
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
