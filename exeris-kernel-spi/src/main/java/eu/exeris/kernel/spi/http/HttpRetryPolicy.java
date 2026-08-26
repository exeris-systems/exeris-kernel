/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

/**
 * SPI: Client-side HTTP retry policy — decides whether the client façade
 * ({@code KernelWebClient}) should re-issue a request after a non-success
 * outcome. Resolves the retry deferral that ADR-026 (&quot;no implicit
 * retry&quot;) and ADR-032 (Alternative D) both named (see ADR-045).
 *
 * <p>Contract:
 * <ul>
 *   <li>{@link #decide(HttpRequest, HttpAttemptOutcome, int)} is invoked once per
 *       completed attempt with the zero-based {@code attemptIndex} of that attempt.</li>
 *   <li>Implementations MUST NOT read, retain, or close the request body
 *       {@code LoanedBuffer} reachable via {@link HttpRequest#body()} — body
 *       ownership belongs to the façade, which re-encodes the body per attempt.
 *       The policy reads the method and headers (e.g. {@code Idempotency-Key}) only.</li>
 *   <li>Implementations run synchronously on the caller's virtual thread; no
 *       thread-spawning, no blocking I/O. Returning {@link RetryDecision#retryAfter(long)}
 *       instructs the façade to sleep {@code delayMillis} before the next attempt.</li>
 *   <li>Implementations SHOULD bound total attempts so a fleet of clients cannot
 *       amplify a server-side admission shed (ADR-010 {@code SHED_LOAD}) into a
 *       retry storm.</li>
 * </ul>
 *
 * <p>Behavioural defaults (which statuses retry, attempt cap, backoff curve,
 * jitter, idempotency gate, {@code Retry-After} handling) are an implementation
 * concern, not SPI surface — see {@code CommunityHttpRetryPolicy}.
 *
 * @since 0.10.0
 */
@FunctionalInterface
public interface HttpRetryPolicy {

    /**
     * Decides whether the attempt that produced {@code outcome} should be retried.
     *
     * @param request      the request as sent — for method / header inspection only;
     *                     the body MUST NOT be read, retained, or closed; never {@code null}
     * @param outcome      the HTTP status or transport failure of this attempt; never {@code null}
     * @param attemptIndex zero-based index of the attempt that just completed ({@code 0} = first try)
     * @return {@link RetryDecision#retryAfter(long)} to retry after a delay, or
     *         {@link RetryDecision#giveUp()} to surface the outcome to the caller
     */
    RetryDecision decide(HttpRequest request, HttpAttemptOutcome outcome, int attemptIndex);

    /**
     * Returns a policy that never retries — preserves the ADR-026 &quot;no
     * implicit retry&quot; default for callers that do not opt in.
     *
     * @return a never-retry policy
     */
    static HttpRetryPolicy none() {
        return (request, outcome, attemptIndex) -> RetryDecision.giveUp();
    }
}
