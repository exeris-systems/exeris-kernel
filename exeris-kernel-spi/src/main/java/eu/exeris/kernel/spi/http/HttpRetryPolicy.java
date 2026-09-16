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
 * <p>Which statuses retry, the attempt cap, the backoff curve, jitter, the idempotency gate and
 * {@code Retry-After} handling are all the policy's own; none of them is SPI surface.
 *
 * <p><b>Thread confinement:</b> owner thread — {@link #decide} runs synchronously on the virtual
 * thread issuing the request, between two attempts
 * <p><b>Ownership:</b> the policy owns nothing — the request body buffer belongs to the façade,
 * which re-encodes it for each attempt
 *
 * @implSpec {@link #decide(HttpRequest, HttpAttemptOutcome, int)} is invoked once per completed
 *           attempt that did not succeed, with the zero-based {@code attemptIndex} of that attempt.
 *           An implementation:
 *           <ul>
 *             <li>MUST NOT read, retain, or close the request body {@code LoanedBuffer} reachable
 *                 through {@link HttpRequest#body()} — it reads the method and the headers
 *                 ({@code Idempotency-Key}, say) only;</li>
 *             <li>MUST run synchronously on the caller's virtual thread: no thread-spawning, no
 *                 blocking I/O. Returning {@link RetryDecision#retryAfter(long)} is how it asks for
 *                 a delay, rather than sleeping itself;</li>
 *             <li>SHOULD bound total attempts, so that a fleet of clients cannot amplify a
 *                 server-side admission shed (ADR-010 {@code SHED_LOAD}) into a retry storm.</li>
 *           </ul>
 * @implNote The Community policy {@code CommunityHttpRetryPolicy} is where the shipped defaults
 *           for those behaviours live.
 * @since 0.10
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
