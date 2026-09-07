/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.security;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.security.AuthenticationResult;
import eu.exeris.kernel.spi.security.PrincipalContext;
import eu.exeris.kernel.spi.security.StorageContext;
import eu.exeris.kernel.spi.security.identity.IdentityProvider;
import eu.exeris.kernel.spi.security.identity.IdentityStorageMapping;
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
 * @since 0.10
 */
public abstract class AbstractIdentityProviderTck {

    /**
     * Creates the contract; subclasses supply the provider binding via {@link #createProvider()}
     * and the token fixtures the contract drives it with.
     */
    public AbstractIdentityProviderTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    // =========================================================================
    // Template methods — binding supplies the SUT + token buffers
    // =========================================================================

    /**
     * Creates the {@link IdentityProvider} under test.
     *
     * @return a configured provider instance
     */
    protected abstract IdentityProvider createProvider();

    /**
     * A token this provider should both claim ({@code canAttempt}) and successfully validate.
     *
     * @return a loaned buffer holding a token this provider owns and can verify
     */
    protected abstract LoanedBuffer validTokenBuffer();

    /**
     * A well-formed token belonging to a <em>different</em> issuer — this provider must NOT
     * claim it.
     *
     * @return a loaned buffer holding a well-formed token this provider does not own
     */
    protected abstract LoanedBuffer foreignIssuerTokenBuffer();

    /**
     * A token this provider claims but cannot verify (e.g. unknown signing key) — must deny.
     *
     * @return a loaned buffer holding a claimed but unverifiable token
     */
    protected abstract LoanedBuffer unverifiableTokenBuffer();

    /**
     * A token whose signature verifies but whose expiry is in the past — must deny.
     *
     * @return a loaned buffer holding a signature-valid, time-expired token
     */
    protected abstract LoanedBuffer expiredTokenBuffer();

    /**
     * A verifiable token declaring
     * {@link eu.exeris.kernel.spi.security.KernelIsolationClaims#SHARED_SCOPE_KEY} — the shared-scope
     * partition the subject asks to participate in.
     *
     * <p>The same buffer drives both sides of the seam: {@link #createProvider()} must deny it, and
     * {@link #createSharedScopeEnforcingProvider()} must honour it (ADR-012 §4b.5 / §4b.7).
     *
     * @return a loaned buffer holding a verifiable token declaring a shared-scope key
     */
    protected abstract LoanedBuffer sharedScopeTokenBuffer();

    /**
     * The same provider constructed for a deployment that asserts it enforces the shared-scope policy
     * contract — a read predicate widening on the published scope, a write predicate still pinned to the
     * owner (ADR-012 §4b.4, {@link IdentityStorageMapping#SHARED_SCOPE_ENFORCED_KEY}).
     *
     * <p>Abstract rather than a hook defaulting to "skip": ADR-012 §4a routes every provider through the
     * one kernel-owned mapping, so no binding is exempt from the enforced path, and a skipping default
     * would make this contract vacuous for precisely the bindings that never looked at it.
     *
     * @return a provider instance configured to enforce the shared-scope policy contract
     */
    protected abstract IdentityProvider createSharedScopeEnforcingProvider();

    /**
     * A scope expected on the principal produced from {@link #validTokenBuffer()}.
     *
     * @return the scope string {@link #validTokenBuffer()}'s principal must carry
     */
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
                assertThat(storage.sharedScopeKey())
                        .as("a token declaring no shared scope MUST stay tenant-private "
                                + "(ADR-012 §4b.5)")
                        .isEmpty();
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

        @Test
        @DisplayName("a declared shared scope is a terminal EX-SEC-2002 deny while unenforceable")
        void declaredSharedScopeDenies() {
            IdentityProvider provider = createProvider();
            try (LoanedBuffer token = sharedScopeTokenBuffer()) {
                assertThatThrownBy(() -> provider.authenticate(token))
                        .as("ADR-012 §4b.5 / §4b.7 — this provider was not constructed for a "
                                + "deployment asserting it enforces the policy contract, so neither "
                                + "narrowing the request to tenant-private nor honouring it unenforced "
                                + "is permitted. Asserted from this suite as well as "
                                + "AbstractSecurityProviderTck because ADR-012 §4a routes every "
                                + "provider through one mapping site — the contract must hold from "
                                + "both entry surfaces")
                        .isInstanceOf(SecurityAuthenticationException.class)
                        .extracting(e -> ((SecurityAuthenticationException) e).errorCode())
                        .isEqualTo(KernelErrorCodes.EX_SEC_2002);
            }
        }
    }

    // =========================================================================
    // authenticate — the enforcing side of the shared-scope seam (ADR-012 §4b.7)
    // =========================================================================

    @Nested
    @DisplayName("authenticate — deployment asserting shared-scope enforcement")
    class SharedScopeEnforcement {

        @Test
        @DisplayName("a declared shared scope reaches the resolved StorageContext")
        void declaredSharedScopeIsCarried() {
            IdentityProvider provider = createSharedScopeEnforcingProvider();
            try (LoanedBuffer token = sharedScopeTokenBuffer()) {
                assertThat(provider.authenticate(token).storage().sharedScopeKey())
                        .as("the deny above is conditional on the deployment, not absolute — where "
                                + "enforcement is asserted the declaration must be honoured, or the "
                                + "tier is unreachable and §4b.7 buys nothing. This is also the only "
                                + "executable assertion that a binding actually threads its "
                                + "enforcement flag into IdentityStorageMapping")
                        .isNotEmpty();
            }
        }

        @Test
        @DisplayName("enforcement invents no scope — a token declaring none stays tenant-private")
        void noDeclarationStaysTenantPrivate() {
            IdentityProvider provider = createSharedScopeEnforcingProvider();
            try (LoanedBuffer token = validTokenBuffer()) {
                assertThat(provider.authenticate(token).storage().sharedScopeKey())
                        .as("asserting the schema CAN enforce a shared scope says nothing about any "
                                + "particular subject asking for one — enforcement must never become a "
                                + "blanket widening (S-P0-07)")
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("enforcement weakens no validation gate — an unverifiable token still denies")
        void enforcementDoesNotWeakenValidation() {
            IdentityProvider provider = createSharedScopeEnforcingProvider();
            try (LoanedBuffer token = unverifiableTokenBuffer()) {
                assertThatThrownBy(() -> provider.authenticate(token))
                        .as("the enforcing instance must carry the same validation pipeline — a "
                                + "binding that rebuilt its validator while opting in would relax the "
                                + "gates guarding the very tier it just widened")
                        .isInstanceOf(SecurityAuthenticationException.class)
                        .extracting(e -> ((SecurityAuthenticationException) e).errorCode())
                        .isEqualTo(KernelErrorCodes.EX_SEC_2002);
            }
        }
    }
}
