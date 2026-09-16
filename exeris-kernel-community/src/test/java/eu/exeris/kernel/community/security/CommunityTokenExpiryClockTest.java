/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.security;

import eu.exeris.kernel.community.testkit.security.TestJwt;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.time.TimeSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Token expiry is decided on the bound {@link TimeSource}, not on the wall clock (ADR-082 A1).
 *
 * <p><b>Why this exists separately from {@code CommunityOidcIdentityProviderTckTest}.</b> That suite
 * already has an expired-token case and it passes either way: it never binds a {@code TimeSource}, so
 * it exercises the unbound fallback, which is behaviourally identical to the {@code Clock.systemUTC()}
 * this change replaced. It would report green whether or not the wiring works.
 *
 * <p>Both directions, and the second is the one that cannot be faked. A token the wall clock calls
 * valid must be <em>rejected</em> when the bound clock has moved past its expiry — that can only pass
 * if the validator really read the bound source. A suite asserting only the first direction would
 * pass against a validator that rejects everything.
 */
@DisplayName("Community OIDC — expiry follows the bound TimeSource")
class CommunityTokenExpiryClockTest {

    private static final long FIVE_MINUTES = 300L;

    @Test
    @DisplayName("a token the wall clock accepts is refused once the bound clock passes its expiry")
    void boundClockExpiresAnOtherwiseValidToken() {
        // Wall-clock valid for five minutes. On a source parked an hour ahead it is long gone.
        ScopedValue.where(KernelProviders.TIME_SOURCE, parkedAt(Instant.now().plusSeconds(3_600)))
                .run(() -> {
                    CommunityOidcTokenValidator validator = boundValidator();
                    try (LoanedBuffer token = TestJwt.builder()
                            .expiresInSeconds(FIVE_MINUTES).toBuffer()) {
                        assertThatThrownBy(() -> validator.validate(token))
                                .as("the bound clock is an hour past this token's expiry, so a "
                                        + "validator reading it MUST refuse — reading the wall clock "
                                        + "instead accepts, which is the whole defect")
                                .isInstanceOfSatisfying(SecurityAuthenticationException.class,
                                        // rawArgs[1], not the message: this repo keeps failure-path
                                        // messages constant and puts the reason in the primitive
                                        // layout. An earlier draft asserted getMessage() and read
                                        // "Token validation failed".
                                        refused -> assertThat(refused.rawArgs()[1])
                                                .as("refused for expiry specifically, not for some "
                                                        + "other validation fault that would make "
                                                        + "this pass for the wrong reason")
                                                .isEqualTo("expired"));
                    }
                });
    }

    @Test
    @DisplayName("a token the wall clock calls expired is accepted when the bound clock predates it")
    void boundClockRevivesAWallClockExpiredToken() {
        // The other direction. Rejecting everything would pass the case above and fail this one.
        ScopedValue.where(KernelProviders.TIME_SOURCE, parkedAt(Instant.now().minusSeconds(3_600)))
                .run(() -> {
                    CommunityOidcTokenValidator validator = boundValidator();
                    try (LoanedBuffer token = TestJwt.builder().expired().toBuffer()) {
                        assertThatCode(() -> validator.validate(token))
                                .as("this token is expired on the wall clock and not yet issued-past "
                                        + "on the bound one; accepting it is only possible if expiry "
                                        + "read the bound source")
                                .doesNotThrowAnyException();
                    }
                });
    }

    @Test
    @DisplayName("unbound still means the platform clock, so an ordinary deployment is unchanged")
    void unboundFallsBackToTheSystemClock() {
        CommunityOidcTokenValidator validator = boundValidator();

        try (LoanedBuffer expired = TestJwt.builder().expired().toBuffer()) {
            assertThatThrownBy(() -> validator.validate(expired))
                    .isInstanceOf(SecurityAuthenticationException.class);
        }
        try (LoanedBuffer valid = TestJwt.builder().expiresInSeconds(FIVE_MINUTES).toBuffer()) {
            assertThatCode(() -> validator.validate(valid)).doesNotThrowAnyException();
        }
    }

    /**
     * A validator built through the NO-CLOCK constructor — the one this change touched, and the one
     * {@code CommunityOidcIdentityProvider}'s simple factories use. Constructed inside the caller's
     * scope, because the source is resolved at construction and not at validation.
     */
    private static CommunityOidcTokenValidator boundValidator() {
        return new CommunityOidcTokenValidator(
                TestJwt.keySet(), TestJwt.EXPECTED_ISSUER, TestJwt.EXPECTED_AUDIENCE);
    }

    private static TimeSource parkedAt(Instant instant) {
        return new TimeSource() {

            @Override
            public long nanoTime() {
                return 0L;
            }

            @Override
            public Instant wallTime() {
                return instant;
            }
        };
    }
}
