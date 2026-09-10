/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.http;

import eu.exeris.kernel.spi.http.HttpAttemptOutcome;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpRetryPolicy;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.http.RetryDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK: Abstract base for {@link HttpRetryPolicy} contract verification (ADR-045).
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link HttpRetryPolicy#none()} returns {@link RetryDecision#giveUp()} for every input —
 *       the ADR-026 no-implicit-retry default.</li>
 *   <li>{@link RetryDecision#retryAfter(long)} rejects a negative delay; {@link RetryDecision#giveUp()}
 *       is a stable, non-retry verdict.</li>
 *   <li>{@link HttpAttemptOutcome} distinguishes a response ({@code statusCode > 0}, headers present,
 *       no failure) from a transport failure ({@code statusCode == 0}, empty headers, failure present),
 *       and carries headers (never a body). The TCK asserts {@link HttpAttemptOutcome#responseHeaders()}
 *       is unmodifiable; the fixture supplies an already-immutable input list, so this does not exercise
 *       aliasing against a caller-mutable list.</li>
 *   <li>A conforming default policy ({@link #createPolicy()}) retries idempotent requests on transient
 *       conditions ({@code 502/503/504} or transport failure), gives up on non-idempotent requests
 *       without an {@code Idempotency-Key}, gives up on non-retryable statuses, honours {@code Retry-After}
 *       on a {@code 503}, and gives up once the attempt cap is reached.</li>
 * </ul>
 *
 * <h2>Subclass binding model</h2>
 * <p>Driver subclasses override {@link #createPolicy()} to supply the default policy under test, built
 * with <em>deterministic</em> tuning (fixed jitter), and {@link #maxAttempts()} to declare the total-attempt
 * cap that policy was built with. The static SPI clauses ({@code none}, {@code RetryDecision},
 * {@code HttpAttemptOutcome}) are exercised against the SPI itself and need no driver.
 *
 * <h2>How to use</h2>
 * {@snippet lang="java" :
 * class CommunityHttpRetryPolicyTckTest extends AbstractHttpRetryPolicyTck {
 *     @Override protected HttpRetryPolicy createPolicy() {
 *         return new CommunityHttpRetryPolicy(3, 100L, 2000L, () -> 0.5);
 *     }
 *     @Override protected int maxAttempts() { return 3; }
 * }
 * }
 */
public abstract class AbstractHttpRetryPolicyTck {

    /**
     * Creates the contract; subclasses supply the policy under test via {@link #createPolicy()} and its
     * attempt cap via {@link #maxAttempts()}.
     */
    public AbstractHttpRetryPolicyTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Supplies the default policy under test, built with deterministic tuning (fixed jitter) and the
     * cap returned by {@link #maxAttempts()}.
     *
     * @return the policy under test
     */
    protected abstract HttpRetryPolicy createPolicy();

    /**
     * Declares the total-attempt cap the {@link #createPolicy()} policy was built with; MUST be {@code >= 2}
     * so the cap test has a retry-eligible attempt to give up after.
     *
     * @return the attempt cap
     */
    protected abstract int maxAttempts();

    private static HttpRequest request(HttpMethod method, List<HttpHeader> headers) {
        return new HttpRequest(method, "/widgets/42", HttpVersion.HTTP_1_1, headers, null);
    }

    private static HttpAttemptOutcome status(int code, HttpHeader... headers) {
        return HttpAttemptOutcome.ofStatus(code, List.of(headers));
    }

    @Nested
    @DisplayName("HttpRetryPolicy.none()")
    class StaticSpiNone {

        @Test
        @DisplayName("never retries, for any status or failure")
        void neverRetries() {
            HttpRetryPolicy none = HttpRetryPolicy.none();
            assertThat(none.decide(request(HttpMethod.GET, List.of()), status(503), 0).retry()).isFalse();
            assertThat(none.decide(request(HttpMethod.GET, List.of()),
                    HttpAttemptOutcome.ofFailure(new IOException("reset")), 0).retry()).isFalse();
            assertThat(none.decide(request(HttpMethod.GET, List.of()), status(200), 0))
                    .isEqualTo(RetryDecision.giveUp());
        }
    }

    @Nested
    @DisplayName("RetryDecision")
    class RetryDecisionContract {

        @Test
        @DisplayName("retryAfter rejects a negative delay")
        void rejectsNegativeDelay() {
            assertThatThrownBy(() -> RetryDecision.retryAfter(-1L))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("retryAfter(0) is a zero-delay retry; giveUp is non-retry")
        void factories() {
            assertThat(RetryDecision.retryAfter(0L).retry()).isTrue();
            assertThat(RetryDecision.retryAfter(0L).delayMillis()).isZero();
            assertThat(RetryDecision.giveUp().retry()).isFalse();
        }
    }

    @Nested
    @DisplayName("HttpAttemptOutcome")
    class AttemptOutcomeContract {

        @Test
        @DisplayName("ofStatus carries status + headers, no failure")
        void ofStatus() {
            HttpAttemptOutcome outcome = HttpAttemptOutcome.ofStatus(503, List.of(new HttpHeader("Retry-After", "2")));
            assertThat(outcome.hasResponse()).isTrue();
            assertThat(outcome.isTransportFailure()).isFalse();
            assertThat(outcome.statusCode()).isEqualTo(503);
            assertThat(outcome.responseHeaders()).hasSize(1);
            assertThat(outcome.failure()).isNull();
        }

        @Test
        @DisplayName("ofFailure carries the throwable, status 0, empty headers")
        void ofFailure() {
            IOException cause = new IOException("connection reset");
            HttpAttemptOutcome outcome = HttpAttemptOutcome.ofFailure(cause);
            assertThat(outcome.isTransportFailure()).isTrue();
            assertThat(outcome.hasResponse()).isFalse();
            assertThat(outcome.statusCode()).isZero();
            assertThat(outcome.responseHeaders()).isEmpty();
            assertThat(outcome.failure()).isSameAs(cause);
        }

        @Test
        @DisplayName("rejects a non-positive status and a null failure")
        void rejectsInvalid() {
            assertThatThrownBy(() -> HttpAttemptOutcome.ofStatus(0, List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> HttpAttemptOutcome.ofFailure(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("defensively copies the header list (no aliasing into the policy)")
        void defensiveCopy() {
            assertThatThrownBy(() -> HttpAttemptOutcome.ofStatus(200, List.of()).responseHeaders().add(
                    new HttpHeader("X", "y"))).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("default policy decision matrix")
    class DefaultPolicyMatrix {

        @Test
        @DisplayName("503 on an idempotent GET → retry")
        void retriesIdempotentOn503() {
            assertThat(createPolicy().decide(request(HttpMethod.GET, List.of()), status(503), 0).retry())
                    .isTrue();
        }

        @Test
        @DisplayName("502 on an idempotent GET → retry")
        void retriesIdempotentOn502() {
            assertThat(createPolicy().decide(request(HttpMethod.GET, List.of()), status(502), 0).retry())
                    .isTrue();
        }

        @Test
        @DisplayName("504 on an idempotent GET → retry")
        void retriesIdempotentOn504() {
            assertThat(createPolicy().decide(request(HttpMethod.GET, List.of()), status(504), 0).retry())
                    .isTrue();
        }

        @Test
        @DisplayName("transport failure on a GET → retry")
        void retriesIdempotentOnTransportFailure() {
            // Representative of what the engine actually throws — an unchecked transport failure.
            assertThat(createPolicy().decide(request(HttpMethod.GET, List.of()),
                    HttpAttemptOutcome.ofFailure(new RuntimeException("connection reset")), 0).retry()).isTrue();
        }

        @Test
        @DisplayName("503 on a non-idempotent POST without Idempotency-Key → give up")
        void givesUpNonIdempotentWithoutKey() {
            assertThat(createPolicy().decide(request(HttpMethod.POST, List.of()), status(503), 0).retry())
                    .isFalse();
        }

        @Test
        @DisplayName("503 on a POST WITH Idempotency-Key → retry")
        void retriesNonIdempotentWithKey() {
            List<HttpHeader> headers = List.of(new HttpHeader("Idempotency-Key", "abc-123"));
            assertThat(createPolicy().decide(request(HttpMethod.POST, headers), status(503), 0).retry())
                    .isTrue();
        }

        @Test
        @DisplayName("404 on a GET → give up (not a retryable status)")
        void givesUpOnNonRetryableStatus() {
            assertThat(createPolicy().decide(request(HttpMethod.GET, List.of()), status(404), 0).retry())
                    .isFalse();
        }

        @Test
        @DisplayName("503 + Retry-After: 2 → retry honouring the 2s delay")
        void honoursRetryAfter() {
            RetryDecision decision = createPolicy().decide(request(HttpMethod.GET, List.of()),
                    status(503, new HttpHeader("Retry-After", "2")), 0);
            assertThat(decision.retry()).isTrue();
            assertThat(decision.delayMillis()).isEqualTo(2_000L);
        }

        @Test
        @DisplayName("gives up once the attempt cap is reached")
        void givesUpAtCap() {
            int lastAttempt = maxAttempts() - 1;
            assertThat(createPolicy().decide(request(HttpMethod.GET, List.of()), status(503), lastAttempt).retry())
                    .isFalse();
        }
    }
}
