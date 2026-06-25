/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.security;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.security.AuthenticationResult;
import eu.exeris.kernel.spi.security.PrincipalContext;
import eu.exeris.kernel.spi.security.StorageContext;
import eu.exeris.kernel.spi.security.identity.IdentityProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK: executable contract for {@link IdentityProvider} bindings (ADR-040).
 *
 * <p>A binding subclass supplies a configured provider plus token buffers covering the routing and
 * validation axes. The contract enforces the fail-closed semantics the kernel relies on: routing is
 * trust-free, every validation failure is a terminal {@code EX-SEC-2002} deny (never {@code null},
 * never fail-open), and a verified token yields a fully populated {@link AuthenticationResult}.
 *
 * @since 0.10.0
 */
public abstract class AbstractIdentityProviderTck {

    // =========================================================================
    // Template methods — binding supplies the SUT + token buffers
    // =========================================================================

    /** Creates the {@link IdentityProvider} under test. */
    protected abstract IdentityProvider createProvider();

    /** A token this provider should both claim ({@code canAttempt}) and successfully validate. */
    protected abstract LoanedBuffer validTokenBuffer();

    /** A well-formed token belonging to a <em>different</em> issuer — this provider must NOT claim it. */
    protected abstract LoanedBuffer foreignIssuerTokenBuffer();

    /** A token this provider claims but cannot verify (e.g. unknown signing key) — must deny. */
    protected abstract LoanedBuffer unverifiableTokenBuffer();

    /** A token whose signature verifies but whose expiry is in the past — must deny. */
    protected abstract LoanedBuffer expiredTokenBuffer();

    /** A scope expected on the principal produced from {@link #validTokenBuffer()}. */
    protected String expectedGrantedScope() {
        return "security:read";
    }

    // =========================================================================
    // Identity metadata
    // =========================================================================

    @Nested
    @DisplayName("Provider identity")
    class Identity {

        @Test
        @DisplayName("providerId / providerName are non-blank, priority is non-negative")
        void metadata() {
            IdentityProvider provider = createProvider();
            assertThat(provider.providerId()).isNotBlank();
            assertThat(provider.providerName()).isNotBlank();
            assertThat(provider.priority()).isGreaterThanOrEqualTo(0);
        }
    }

    // =========================================================================
    // Routing (canAttempt) — trust-free dispatch peek
    // =========================================================================

    @Nested
    @DisplayName("canAttempt — routing, not trust")
    class Routing {

        @Test
        @DisplayName("claims a token of its own issuer")
        void claimsOwnIssuer() {
            IdentityProvider provider = createProvider();
            try (LoanedBuffer token = validTokenBuffer()) {
                assertThat(provider.canAttempt(token)).isTrue();
            }
        }

        @Test
        @DisplayName("does not claim a foreign issuer's token")
        void rejectsForeignIssuer() {
            IdentityProvider provider = createProvider();
            try (LoanedBuffer token = foreignIssuerTokenBuffer()) {
                assertThat(provider.canAttempt(token)).isFalse();
            }
        }
    }

    // =========================================================================
    // authenticate — happy path
    // =========================================================================

    @Nested
    @DisplayName("authenticate — verified token")
    class HappyPath {

        @Test
        @DisplayName("yields a fully populated AuthenticationResult")
        void populatesResult() {
            IdentityProvider provider = createProvider();
            try (LoanedBuffer token = validTokenBuffer()) {
                AuthenticationResult result = provider.authenticate(token);

                assertThat(result).isNotNull();
                PrincipalContext principal = result.principal();
                assertThat(principal.principalId()).as("principalId").isNotNull();
                assertThat(principal.hasScope(expectedGrantedScope())).isTrue();

                StorageContext storage = result.storage();
                assertThat(storage).isNotNull();
                assertThat(storage.strategy()).as("isolation strategy").isNotNull();
            }
        }

        @Test
        @DisplayName("is deterministic — same token yields the same principalId")
        void deterministic() {
            IdentityProvider provider = createProvider();
            try (LoanedBuffer first = validTokenBuffer(); LoanedBuffer second = validTokenBuffer()) {
                assertThat(provider.authenticate(first).principal().principalId())
                        .isEqualTo(provider.authenticate(second).principal().principalId());
            }
        }
    }

    // =========================================================================
    // authenticate — fail-closed
    // =========================================================================

    @Nested
    @DisplayName("authenticate — fail-closed deny")
    class FailurePath {

        @Test
        @DisplayName("an unverifiable token is a terminal EX-SEC-2002 deny")
        void unverifiableDenies() {
            IdentityProvider provider = createProvider();
            try (LoanedBuffer token = unverifiableTokenBuffer()) {
                assertThatThrownBy(() -> provider.authenticate(token))
                        .isInstanceOf(SecurityAuthenticationException.class)
                        .extracting(e -> ((SecurityAuthenticationException) e).errorCode())
                        .isEqualTo(KernelErrorCodes.EX_SEC_2002);
            }
        }

        @Test
        @DisplayName("an expired token is a terminal EX-SEC-2002 deny")
        void expiredDenies() {
            IdentityProvider provider = createProvider();
            try (LoanedBuffer token = expiredTokenBuffer()) {
                assertThatThrownBy(() -> provider.authenticate(token))
                        .isInstanceOf(SecurityAuthenticationException.class)
                        .extracting(e -> ((SecurityAuthenticationException) e).errorCode())
                        .isEqualTo(KernelErrorCodes.EX_SEC_2002);
            }
        }
    }
}
