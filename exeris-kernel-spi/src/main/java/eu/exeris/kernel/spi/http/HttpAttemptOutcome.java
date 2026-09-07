/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

import java.util.List;
import java.util.Objects;

/**
 * SPI value carrier: the result of a single HTTP send attempt as seen by an
 * {@link HttpRetryPolicy} — either a received HTTP status (with its response
 * headers), or a transport-level failure with no HTTP response.
 *
 * <p>Carries the response status and headers but <strong>no body</strong>:
 * response-body ownership stays inside the client façade ({@code KernelWebClient}).
 * The headers are exposed so a policy can honour {@code Retry-After} on a
 * {@code 503} (ADR-010 {@code SHED_LOAD}) without touching the body. Immutable,
 * identity-free, Valhalla-ready (per ADR-045). Exactly one of the two states
 * holds: {@code failure == null} (a response with {@code statusCode > 0}) or
 * {@code failure != null} (a transport failure with {@code statusCode == 0} and
 * empty headers).
 *
 * @param statusCode      the received HTTP status, or {@code 0} for a transport failure
 * @param responseHeaders the response headers (empty on a transport failure); never {@code null}
 * @param failure         the transport-level failure, or {@code null} when a response arrived
 * @since 0.10
 */
public record HttpAttemptOutcome(int statusCode, List<HttpHeader> responseHeaders, Throwable failure) {

    /**
     * Copies the header list defensively and enforces the two-state invariant, so that no caller can
     * construct an outcome that is neither a response nor a failure — or claims to be both.
     *
     * @throws NullPointerException     if {@code responseHeaders} is {@code null}
     * @throws IllegalArgumentException if a status and a failure are both present, or neither is
     */
    public HttpAttemptOutcome {
        Objects.requireNonNull(responseHeaders, "responseHeaders must not be null");
        responseHeaders = List.copyOf(responseHeaders);
        if ((failure == null) == (statusCode <= 0)) {
            throw new IllegalArgumentException(
                    "exactly one of statusCode (> 0) or failure must be present");
        }
    }

    /**
     * Returns an outcome for an attempt that received an HTTP response.
     *
     * @param statusCode      the received status; MUST be {@code > 0}
     * @param responseHeaders the response headers; never {@code null}, may be empty
     * @return a response outcome
     * @throws IllegalArgumentException if {@code statusCode} is not {@code > 0} — a response
     *                                  outcome without a status would be indistinguishable from a
     *                                  transport failure
     */
    public static HttpAttemptOutcome ofStatus(int statusCode, List<HttpHeader> responseHeaders) {
        if (statusCode <= 0) {
            throw new IllegalArgumentException("statusCode must be > 0 for a response outcome, was " + statusCode);
        }
        return new HttpAttemptOutcome(statusCode, responseHeaders, null);
    }

    /**
     * Returns an outcome for an attempt that failed at the transport level with
     * no HTTP response.
     *
     * <p>The parameter is {@link Throwable} rather than {@link RuntimeException} so a policy may
     * carry a wrapped cause; it is not a signal that checked transport exceptions reach this seam.
     *
     * @param failure the transport failure; never {@code null}
     * @return a transport-failure outcome
     * @throws NullPointerException if {@code failure} is {@code null}
     * @implNote The client façade ({@code KernelWebClient}) only produces
     *           {@link RuntimeException} failures here, because {@code HttpClientEngine.send}
     *           is an unchecked-throwing contract.
     */
    public static HttpAttemptOutcome ofFailure(Throwable failure) {
        Objects.requireNonNull(failure, "failure must not be null");
        return new HttpAttemptOutcome(0, List.of(), failure);
    }

    /**
     * Returns {@code true} when an HTTP response arrived (no transport failure).
     *
     * @return {@code true} if a status is present
     */
    public boolean hasResponse() {
        return failure == null;
    }

    /**
     * Returns {@code true} when the attempt failed at the transport level.
     *
     * @return {@code true} if a transport failure is present
     */
    public boolean isTransportFailure() {
        return failure != null;
    }
}
