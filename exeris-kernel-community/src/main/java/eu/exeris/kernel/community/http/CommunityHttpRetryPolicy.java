/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.http.HttpAttemptOutcome;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpRetryPolicy;
import eu.exeris.kernel.spi.http.RetryDecision;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Community default {@link HttpRetryPolicy} (ADR-045). Retries idempotent requests on transient
 * conditions with capped exponential backoff and full jitter, and honours {@code Retry-After} on a
 * {@code 503} (ADR-010 {@code SHED_LOAD}) so a client fleet cannot amplify a server-side admission
 * shed into a retry storm.
 *
 * <p>Decision order — give up unless all hold:
 * <ol>
 *   <li>another attempt remains under the cap ({@code attemptIndex + 1 < maxAttempts});</li>
 *   <li>the request is retry-eligible: an idempotent method (RFC 9110 — {@code GET}/{@code HEAD}/
 *       {@code PUT}/{@code DELETE}/{@code OPTIONS}/{@code TRACE}) <em>or</em> any request carrying an
 *       {@code Idempotency-Key} header (caller's explicit opt-in for {@code POST}/{@code PATCH});</li>
 *   <li>the outcome is retryable: a transport failure, or status {@code 502}/{@code 503}/{@code 504}.</li>
 * </ol>
 *
 * <p>Delay: a {@code Retry-After} delta-seconds header on a {@code 503} is honoured verbatim;
 * otherwise {@code random(0, min(maxDelay, base · 2^attemptIndex))} (full jitter, decorrelated
 * across clients).
 *
 * <p>Stateless and thread-safe. Cross-call circuit-breaking is intentionally out of scope (the
 * per-attempt contract carries no cross-call state) — see ADR-045.
 *
 * @since 0.10
 */
// PMD.CyclomaticComplexity: a retry policy is a decision machine — idempotency gate, retryable-status
// set, backoff ceiling, and Retry-After parsing each add a small branch. No single method exceeds the
// per-method limit (highest is 9); the class total is the sum of cohesive one-purpose helpers.
@SuppressWarnings("PMD.CyclomaticComplexity")
public final class CommunityHttpRetryPolicy implements HttpRetryPolicy {

    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final long DEFAULT_BASE_DELAY_MILLIS = 100L;
    private static final long DEFAULT_MAX_DELAY_MILLIS = 2_000L;
    private static final long MILLIS_PER_SECOND = 1_000L;
    private static final int MIN_ATTEMPTS = 1;
    private static final long ZERO_MILLIS = 0L;
    private static final long NO_RETRY_AFTER = -1L;

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String RETRY_AFTER = "Retry-After";
    private static final int STATUS_BAD_GATEWAY = 502;
    private static final int STATUS_SERVICE_UNAVAILABLE = 503;
    private static final int STATUS_GATEWAY_TIMEOUT = 504;
    private static final Set<Integer> RETRYABLE_STATUS =
            Set.of(STATUS_BAD_GATEWAY, STATUS_SERVICE_UNAVAILABLE, STATUS_GATEWAY_TIMEOUT);
    private static final Set<HttpMethod> IDEMPOTENT_METHODS = EnumSet.of(
            HttpMethod.GET, HttpMethod.HEAD, HttpMethod.PUT,
            HttpMethod.DELETE, HttpMethod.OPTIONS, HttpMethod.TRACE);

    private final int maxAttempts;
    private final long baseDelayMillis;
    private final long maxDelayMillis;
    private final DoubleSupplier jitter;

    /**
     * Creates a policy with the default tuning: 3 total attempts, 100&nbsp;ms base, 2000&nbsp;ms cap,
     * full jitter from {@link ThreadLocalRandom}.
     */
    public CommunityHttpRetryPolicy() {
        this(DEFAULT_MAX_ATTEMPTS, DEFAULT_BASE_DELAY_MILLIS, DEFAULT_MAX_DELAY_MILLIS,
                () -> ThreadLocalRandom.current().nextDouble());
    }

    /**
     * Creates a policy with explicit tuning. The {@code jitter} supplier returns a value in
     * {@code [0, 1)} and is injectable for deterministic testing.
     *
     * @param maxAttempts     total attempts including the first; MUST be {@code >= 1}
     * @param baseDelayMillis exponential-backoff base in milliseconds; MUST be {@code >= 0}
     * @param maxDelayMillis  backoff cap in milliseconds; MUST be {@code >= baseDelayMillis}
     * @param jitter          supplier of a jitter fraction in {@code [0, 1)}; never {@code null}
     */
    public CommunityHttpRetryPolicy(int maxAttempts, long baseDelayMillis, long maxDelayMillis, DoubleSupplier jitter) {
        if (maxAttempts < MIN_ATTEMPTS) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, was " + maxAttempts);
        }
        if (baseDelayMillis < ZERO_MILLIS) {
            throw new IllegalArgumentException("baseDelayMillis must be >= 0, was " + baseDelayMillis);
        }
        if (maxDelayMillis < baseDelayMillis) {
            throw new IllegalArgumentException("maxDelayMillis must be >= baseDelayMillis");
        }
        this.maxAttempts = maxAttempts;
        this.baseDelayMillis = baseDelayMillis;
        this.maxDelayMillis = maxDelayMillis;
        if (jitter == null) {
            throw new IllegalArgumentException("jitter must not be null");
        }
        this.jitter = jitter;
    }

    @Override
    public RetryDecision decide(HttpRequest request, HttpAttemptOutcome outcome, int attemptIndex) {
        if (attemptIndex + 1 >= maxAttempts) {
            return RetryDecision.giveUp();
        }
        if (!isRetryEligible(request)) {
            return RetryDecision.giveUp();
        }
        if (!isRetryable(outcome)) {
            return RetryDecision.giveUp();
        }
        return RetryDecision.retryAfter(delayMillis(outcome, attemptIndex));
    }

    private static boolean isRetryEligible(HttpRequest request) {
        return IDEMPOTENT_METHODS.contains(request.method())
                || hasHeader(request.headers(), IDEMPOTENCY_KEY);
    }

    private static boolean isRetryable(HttpAttemptOutcome outcome) {
        return outcome.isTransportFailure() || RETRYABLE_STATUS.contains(outcome.statusCode());
    }

    private long delayMillis(HttpAttemptOutcome outcome, int attemptIndex) {
        if (outcome.hasResponse() && outcome.statusCode() == STATUS_SERVICE_UNAVAILABLE) {
            long retryAfter = retryAfterMillis(outcome.responseHeaders());
            if (retryAfter != NO_RETRY_AFTER) {
                return retryAfter;
            }
        }
        return (long) (jitter.getAsDouble() * backoffCeiling(attemptIndex));
    }

    /**
     * Exponential backoff ceiling {@code min(maxDelay, base · 2^attemptIndex)}, computed with a clamped
     * exponent so the left-shift can never overflow into a negative value for large attempt indices or
     * custom caps (the shift stays within the positive range of {@code base}).
     */
    private long backoffCeiling(int attemptIndex) {
        if (baseDelayMillis == ZERO_MILLIS) {
            return ZERO_MILLIS;
        }
        int maxExponent = Long.numberOfLeadingZeros(baseDelayMillis) - 1;
        int exponent = Math.min(attemptIndex, maxExponent);
        return Math.min(maxDelayMillis, baseDelayMillis << exponent);
    }

    /**
     * Parses a {@code Retry-After} delta-seconds header to milliseconds, or {@code -1} when absent or
     * not a non-negative integer (HTTP-date form is not honoured here; backoff applies instead).
     */
    private static long retryAfterMillis(List<HttpHeader> headers) {
        for (HttpHeader header : headers) {
            if (header.nameEqualsIgnoreCase(RETRY_AFTER)) {
                try {
                    long seconds = Long.parseLong(header.value().trim());
                    return seconds < ZERO_MILLIS ? NO_RETRY_AFTER : seconds * MILLIS_PER_SECOND;
                } catch (NumberFormatException ignored) {
                    return NO_RETRY_AFTER;
                }
            }
        }
        return NO_RETRY_AFTER;
    }

    private static boolean hasHeader(List<HttpHeader> headers, String name) {
        for (HttpHeader header : headers) {
            if (header.nameEqualsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }
}
