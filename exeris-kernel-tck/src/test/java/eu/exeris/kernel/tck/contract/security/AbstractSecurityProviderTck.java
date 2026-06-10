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
import eu.exeris.kernel.spi.security.SecurityProvider;
import eu.exeris.kernel.spi.security.StorageContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Comparator;
import java.util.ServiceLoader;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TCK: Abstract base for {@link SecurityProvider} contract verification.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@code providerId()} returns a non-null, non-blank stable identifier</li>
 *   <li>{@code providerName()} returns a non-null, non-blank display name</li>
 *   <li>{@code priority()} follows the Open-Core convention (0 = Community, 100 = Enterprise)</li>
 *   <li>{@code authenticate(LoanedBuffer)} returns a fully-populated {@link AuthenticationResult}</li>
 *   <li>{@code authenticate()} with invalid token throws {@link SecurityAuthenticationException}</li>
 *   <li>{@code systemStorageContext()} returns a valid, non-null context</li>
 *   <li>ServiceLoader discovery selects the highest-priority provider</li>
 * </ul>
 *
 * <h2>How to use</h2>
 * <pre>{@code
 * class CommunitySecurityProviderTckTest extends AbstractSecurityProviderTck {
 *     @Override protected SecurityProvider createProvider() {
 *         return new CommunitySecurityProvider(jwtKeySupplier);
 *     }
 *     @Override protected LoanedBuffer createValidTokenBuffer() {
 *         return TestTokens.validJwt(allocator);
 *     }
 *     @Override protected LoanedBuffer createInvalidTokenBuffer() {
 *         return TestTokens.corruptJwt(allocator);
 *     }
 * }
 * }</pre>
 *
 * @since 0.5.0
 */
public abstract class AbstractSecurityProviderTck {

    // =========================================================================
    // Template methods — subclass supplies the SUT
    // =========================================================================

    /** Creates the {@link SecurityProvider} under test. */
    protected abstract SecurityProvider createProvider();

    /**
     * Creates a {@link LoanedBuffer} containing a valid, parseable token.
     * The token MUST be decodable by the provider returned from {@link #createProvider()}.
     * Caller owns the buffer lifecycle.
     */
    protected abstract LoanedBuffer createValidTokenBuffer();

    /**
     * Creates a {@link LoanedBuffer} containing an invalid / corrupt token.
     * The provider MUST reject this token by throwing {@link SecurityAuthenticationException}.
     * Caller owns the buffer lifecycle.
     */
    protected abstract LoanedBuffer createInvalidTokenBuffer();

    /**
     * Creates a {@link LoanedBuffer} representing provider uncertainty / indeterminate token state.
     *
     * <p>Default behavior maps to {@link #createInvalidTokenBuffer()} to preserve backward compatibility
     * for existing subclasses.</p>
     *
     * @return token buffer for indeterminate path
     */
    protected LoanedBuffer createIndeterminateTokenBuffer() {
        return createInvalidTokenBuffer();
    }

    /**
     * Creates a {@link LoanedBuffer} containing a token with a known, valid {@code kid} that
     * matches a key in the provider's key set.
     * The provider MUST accept this token (per ADR-013 §6: known-kid → accept).
     * Caller owns the buffer lifecycle.
     * <p>Default: delegates to {@link #createValidTokenBuffer()} for providers that don't use kid.
     */
    protected LoanedBuffer createTokenWithKnownKid() {
        return createValidTokenBuffer();
    }

    /**
     * Creates a {@link LoanedBuffer} containing a token with a {@code kid} that is NOT present
     * in the provider's key set.
     * The provider MUST deny this token (ADR-013 §6: unknown-kid → deny).
     * Caller owns the buffer lifecycle.
     * <p>Default: delegates to {@link #createInvalidTokenBuffer()}.
     */
    protected LoanedBuffer createTokenWithUnknownKid() {
        return createInvalidTokenBuffer();
    }

    /**
     * Creates a {@link LoanedBuffer} containing a token without a {@code kid} header claim
     * when kid-based key selection is required.
     * The provider MUST deny this token (ADR-013 §6: ambiguous kid = deny).
     * Caller owns the buffer lifecycle.
     * <p>Default: delegates to {@link #createInvalidTokenBuffer()}.
     */
    protected LoanedBuffer createTokenWithMissingKid() {
        return createInvalidTokenBuffer();
    }

    /**
     * Creates a {@link LoanedBuffer} containing a token whose {@code exp} claim is in the past.
     * The provider MUST deny this token with {@link eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException}.
     * Caller owns the buffer lifecycle.
     * <p>Default: delegates to {@link #createInvalidTokenBuffer()}.
     */
    protected LoanedBuffer createExpiredTokenBuffer() {
        return createInvalidTokenBuffer();
    }

    /**
     * Creates a {@link LoanedBuffer} containing a token with a wrong {@code iss} claim.
     * The provider MUST deny this token (ADR-013 §4: issuer validation).
     * Caller owns the buffer lifecycle.
     * <p>Default: delegates to {@link #createInvalidTokenBuffer()}.
     */
    protected LoanedBuffer createWrongIssuerTokenBuffer() {
        return createInvalidTokenBuffer();
    }

    /**
     * Creates a {@link LoanedBuffer} containing a token with a wrong {@code aud} claim.
     * The provider MUST deny this token (ADR-013 §4: audience validation).
     * Caller owns the buffer lifecycle.
     * <p>Default: delegates to {@link #createInvalidTokenBuffer()}.
     */
    protected LoanedBuffer createWrongAudienceTokenBuffer() {
        return createInvalidTokenBuffer();
    }

    /**
     * Creates a {@link LoanedBuffer} containing a structurally valid token whose
     * cryptographic signature has been tampered with.
     * The provider MUST deny this token.
     * Caller owns the buffer lifecycle.
     * <p>Default: delegates to {@link #createInvalidTokenBuffer()}.
     */
    protected LoanedBuffer createTamperedSignatureBuffer() {
        return createInvalidTokenBuffer();
    }

    /**
     * Creates a {@link LoanedBuffer} containing an <b>unsecured</b> token whose header declares
     * {@code "alg":"none"} (the classic signature-stripping downgrade attack).
     * The provider MUST deny this token — an unsigned token is never trusted.
     * Caller owns the buffer lifecycle.
     * <p>Default: delegates to {@link #createInvalidTokenBuffer()} so the contract is meaningful
     * even for bindings that cannot mint an {@code alg=none} token; security bindings SHOULD
     * override with a real unsecured token to exercise the algorithm gate directly.
     */
    protected LoanedBuffer createAlgNoneTokenBuffer() {
        return createInvalidTokenBuffer();
    }

    /**
     * Creates a {@link LoanedBuffer} containing a token signed with a <i>symmetric</i> MAC
     * (e.g. HS256) keyed on the bytes of the provider's published <i>asymmetric</i> public key
     * (the RS256-&gt;HS256 algorithm-confusion attack). The {@code kid} resolves to a known key
     * so the token reaches the algorithm gate.
     * The provider MUST deny this token — a provider that pins an asymmetric algorithm never
     * verifies a symmetric MAC against its public key.
     * Caller owns the buffer lifecycle.
     * <p>Default: delegates to {@link #createInvalidTokenBuffer()}; security bindings SHOULD
     * override with a real confusion token.
     */
    protected LoanedBuffer createAlgConfusionTokenBuffer() {
        return createInvalidTokenBuffer();
    }

    /**
     * Returns a scope expected to be granted for the principal returned from
     * {@link #createValidTokenBuffer()} authentication.
     */
    protected String expectedGrantedScope() {
        return "security:read";
    }

    /**
     * Returns a scope expected to be denied for the principal returned from
     * {@link #createValidTokenBuffer()} authentication.
     */
    protected String expectedDeniedScope() {
        return "security:write";
    }

    /**
     * Creates a {@link LoanedBuffer} containing a valid token with NO isolation strategy claim.
     * The provider MUST return a {@link StorageContext} with
     * {@link StorageContext.IsolationStrategy#SHARED} strategy (fail-closed default).
     *
     * <p>Default: delegates to {@link #createValidTokenBuffer()}, since a standard valid token
     * carries no isolation claim.
     */
    protected LoanedBuffer createTokenWithNoIsolationClaim() {
        return createValidTokenBuffer();
    }

    /**
     * Creates a {@link LoanedBuffer} containing a token with the {@code SEPARATED_SCHEMA}
     * isolation strategy claim and a valid schema name claim.
     *
     * <p>The provider MUST return a {@link StorageContext} with
     * {@link StorageContext.IsolationStrategy#SEPARATED_SCHEMA} strategy and
     * {@link StorageContext#schemaName()} equal to {@link #expectedSeparatedSchemaName()}.
     * Caller owns the buffer lifecycle.
     */
    protected abstract LoanedBuffer createTokenWithSeparatedSchemaStrategy();

    /**
     * Returns the expected {@link StorageContext#schemaName()} for the context produced from
     * the token returned by {@link #createTokenWithSeparatedSchemaStrategy()}.
     */
    protected abstract String expectedSeparatedSchemaName();

    /**
     * Creates a {@link LoanedBuffer} containing a token with the {@code DEDICATED}
     * isolation strategy claim and a valid datasource key claim.
     *
     * <p>The provider MUST return a {@link StorageContext} with
     * {@link StorageContext.IsolationStrategy#DEDICATED} strategy and
     * {@link StorageContext#dataSourceKey()} equal to {@link #expectedDedicatedDataSourceKey()}.
     * Caller owns the buffer lifecycle.
     */
    protected abstract LoanedBuffer createTokenWithDedicatedStrategy();

    /**
     * Returns the expected {@link StorageContext#dataSourceKey()} for the context produced from
     * the token returned by {@link #createTokenWithDedicatedStrategy()}.
     */
    protected abstract String expectedDedicatedDataSourceKey();

    /**
     * Creates a {@link LoanedBuffer} containing a token with the {@code SEPARATED_SCHEMA}
     * isolation strategy claim but WITHOUT a schema name claim.
     *
     * <p>The provider MUST <b>deny</b> this token ({@link SecurityAuthenticationException},
     * {@code EX-SEC-2002}). A declared strong strategy with a missing required sub-claim must NOT
     * be downgraded to {@link StorageContext.IsolationStrategy#SHARED} — that is fail-OPEN
     * (silently weakening the provisioned isolation tier) per S-P0-07 / ADR-012 §4a (amended).
     * Caller owns the buffer lifecycle.
     */
    protected abstract LoanedBuffer createTokenWithSeparatedSchemaMissingSchemaName();

    /**
     * Creates a {@link LoanedBuffer} containing a token with the {@code DEDICATED}
     * isolation strategy claim but WITHOUT a datasource key claim.
     *
     * <p>The provider MUST <b>deny</b> this token ({@link SecurityAuthenticationException},
     * {@code EX-SEC-2002}) — not downgrade to {@link StorageContext.IsolationStrategy#SHARED}
     * (S-P0-07 / ADR-012 §4a amended). Caller owns the buffer lifecycle.
     */
    protected abstract LoanedBuffer createTokenWithDedicatedMissingDataSourceKey();

    /**
     * Creates a {@link LoanedBuffer} containing a token with an unrecognised, non-empty
     * isolation strategy claim value (e.g. {@code "SUPER_TENANT"} or {@code "BYPASS_RLS"}).
     *
     * <p>The provider MUST <b>deny</b> this token ({@link SecurityAuthenticationException},
     * {@code EX-SEC-2002}). A strategy the kernel cannot honour must be rejected, not downgraded to
     * {@link StorageContext.IsolationStrategy#SHARED} (S-P0-07 / ADR-012 §4a amended).
     * Caller owns the buffer lifecycle.
     */
    protected abstract LoanedBuffer createTokenWithUnrecognizedStrategy();

    // =========================================================================
    // Optional rotation harness — default-skip for non-rotating subclasses
    // =========================================================================

    /**
     * Drives the key-rotation contract for a provider that supports a rotating verification
     * key set with a controllable clock and a fail-injectable refresh source.
     *
     * <p>Implementations are entirely owned by the binding module (which has visibility of
     * the concrete community rotation seams). The TCK references only this interface and the
     * SPI {@link SecurityProvider}.
     */
    protected interface RotationHarness extends AutoCloseable {

        /** The provider wired with the rotating key set under test. */
        SecurityProvider provider();

        /** Advances the harness clock by the given amount (deterministic time travel). */
        void advanceClock(Duration amount);

        /**
         * Arms the refresh source to deliver a brand-new key set (a new {@code kid -> key}
         * generation) on the next stale-triggered refresh, causing a rotation.
         */
        void installNewKeySet();

        /**
         * Arms the refresh source so that the next stale-triggered refresh fails
         * (signals refresh failure rather than returning a snapshot).
         */
        void failNextRefresh();

        /**
         * Mints a freshly-signed, otherwise-valid token under the ORIGINAL (pre-rotation)
         * {@code kid}. The JWT {@code exp} is far enough in the future that wall-clock
         * expiry never interferes with the rotation assertions.
         */
        LoanedBuffer tokenUnderOriginalKid();

        @Override
        void close();
    }

    /**
     * Supplies a {@link RotationHarness} for the rotation contract cases.
     *
     * <p>Default returns {@code null} — non-rotating subclasses are skipped cleanly via
     * {@link org.junit.jupiter.api.Assumptions}. Rotating subclasses override this to wire
     * a provider with a controllable clock and fail-injectable source.
     *
     * @return a fresh rotation harness, or {@code null} if rotation is not supported
     */
    protected RotationHarness createRotationHarness() {
        return null;
    }

    private SecurityProvider provider;

    @BeforeEach
    final void setUpProvider() {
        provider = createProvider();
    }

    // =========================================================================
    // Provider Identity Contract
    // =========================================================================

    @Nested
    @DisplayName("Provider identity contract")
    class IdentityContract {

        @Test
        @DisplayName("providerId() is non-null and non-blank")
        void providerIdIsNonBlank() {
            assertThat(provider.providerId())
                    .as("providerId must be a stable, non-blank identifier")
                    .isNotNull()
                    .isNotBlank();
        }

        @Test
        @DisplayName("providerName() is non-null and non-blank")
        void providerNameIsNonBlank() {
            assertThat(provider.providerName())
                    .as("providerName must be a human-readable display name")
                    .isNotNull()
                    .isNotBlank();
        }

        @Test
        @DisplayName("providerId() is stable across calls (no per-call allocation)")
        void providerIdIsStable() {
            String first = provider.providerId();
            String second = provider.providerId();
            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("providerName() is stable across calls")
        void providerNameIsStable() {
            String first = provider.providerName();
            String second = provider.providerName();
            assertThat(first).isEqualTo(second);
        }
    }

    // =========================================================================
    // Open-Core Priority Convention
    // =========================================================================

    @Nested
    @DisplayName("Open-Core priority convention")
    class PriorityConvention {

        @Test
        @DisplayName("priority() is non-negative")
        void priorityIsNonNegative() {
            assertThat(provider.priority())
                    .as("priority must be >= 0")
                    .isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("Community returns 0, Enterprise returns 100")
        void priorityFollowsConvention() {
            assertThat(provider.priority())
                    .as("Open-Core convention: Community = 0, Enterprise = 100")
                    .isIn(0, 100);
        }
    }

    // =========================================================================
    // authenticate() — happy path
    // =========================================================================

    @Nested
    @DisplayName("authenticate() — valid token")
    class AuthenticateHappyPath {

        @Test
        @DisplayName("returns non-null AuthenticationResult")
        void returnsNonNullResult() {
            try (LoanedBuffer token = createValidTokenBuffer()) {
                AuthenticationResult result = provider.authenticate(token);
                assertThat(result).isNotNull();
            }
        }

        @Test
        @DisplayName("result contains non-null PrincipalContext")
        void resultHasPrincipal() {
            try (LoanedBuffer token = createValidTokenBuffer()) {
                AuthenticationResult result = provider.authenticate(token);
                PrincipalContext p = result.principal();
                assertThat(p).isNotNull();
                assertThat(p.principalId()).as("principalId must not be null").isNotNull();
            }
        }

        @Test
        @DisplayName("principal has immutable, non-null roles set")
        void principalRolesImmutable() {
            try (LoanedBuffer token = createValidTokenBuffer()) {
                PrincipalContext p = provider.authenticate(token).principal();
                var roles = p.roles();
                assertThat(roles).isNotNull();
                assertThatThrownBy(() -> roles.add("ROLE_ROGUE"))
                        .isInstanceOf(UnsupportedOperationException.class);
            }
        }

        @Test
        @DisplayName("principal has immutable, non-null scopes set")
        void principalScopesImmutable() {
            try (LoanedBuffer token = createValidTokenBuffer()) {
                PrincipalContext p = provider.authenticate(token).principal();
                var scopes = p.scopes();
                assertThat(scopes).isNotNull();
                assertThatThrownBy(() -> scopes.add("admin:nuke"))
                        .isInstanceOf(UnsupportedOperationException.class);
            }
        }

        @Test
        @DisplayName("result contains non-null StorageContext with valid strategy")
        void resultHasStorage() {
            try (LoanedBuffer token = createValidTokenBuffer()) {
                StorageContext s = provider.authenticate(token).storage();
                assertThat(s).isNotNull();
                assertThat(s.strategy()).isNotNull();
                assertThat(s.attributes()).isNotNull();
            }
        }

        @Test
        @DisplayName("StorageContext attributes are unmodifiable")
        void storageAttributesUnmodifiable() {
            try (LoanedBuffer token = createValidTokenBuffer()) {
                StorageContext s = provider.authenticate(token).storage();
                var attributes = s.attributes();
                assertThatThrownBy(() -> attributes.put("rogue", "val"))
                        .isInstanceOf(UnsupportedOperationException.class);
            }
        }

        @Test
        @DisplayName("principal authorization allows expected granted scope")
        void authorizationAllowsGrantedScope() {
            try (LoanedBuffer token = createValidTokenBuffer()) {
                PrincipalContext principal = provider.authenticate(token).principal();
                assertThat(principal.hasScope(expectedGrantedScope())).isTrue();
            }
        }

        @Test
        @DisplayName("principal authorization denies unexpected scope")
        void authorizationDeniesUnexpectedScope() {
            try (LoanedBuffer token = createValidTokenBuffer()) {
                PrincipalContext principal = provider.authenticate(token).principal();
                assertThat(principal.hasScope(expectedDeniedScope())).isFalse();
            }
        }
    }

    // =========================================================================
    // authenticate() — failure path
    // =========================================================================

    @Nested
    @DisplayName("authenticate() — invalid token")
    class AuthenticateFailurePath {

        @Test
        @DisplayName("throws SecurityAuthenticationException for invalid token")
        void throwsOnInvalidToken() {
            try (LoanedBuffer token = createInvalidTokenBuffer()) {
                assertThatThrownBy(() -> provider.authenticate(token))
                        .isInstanceOfSatisfying(SecurityAuthenticationException.class, ex -> {
                            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_SEC_2002);
                            Object[] raw = ex.rawArgs();
                            assertThat(raw).hasSizeGreaterThanOrEqualTo(2);
                            assertThat(raw[0]).isInstanceOf(String.class);
                            assertThat(raw[1]).isInstanceOf(String.class);
                            assertThat((String) raw[0]).isNotBlank();
                            assertThat((String) raw[1]).isNotBlank();
                        });
            }
        }

        @Test
        @DisplayName("indeterminate token is denied with EX-SEC-2002")
        void deniesIndeterminateToken() {
            try (LoanedBuffer token = createIndeterminateTokenBuffer()) {
                assertThatThrownBy(() -> provider.authenticate(token))
                        .isInstanceOfSatisfying(SecurityAuthenticationException.class, ex ->
                                assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_SEC_2002));
            }
        }

        @Test
        @DisplayName("repeated indeterminate token attempts are deterministically denied")
        void repeatedIndeterminateAttemptsAreDeterministicallyDenied() {
            for (int i = 0; i < 5; i++) {
                try (LoanedBuffer token = createIndeterminateTokenBuffer()) {
                    assertThatThrownBy(() -> provider.authenticate(token))
                            .isInstanceOfSatisfying(SecurityAuthenticationException.class, ex ->
                                    assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_SEC_2002));
                }
            }
        }
    }

    // =========================================================================
    // Algorithm-confusion / downgrade adversarial contract
    // =========================================================================

    @Nested
    @DisplayName("authenticate() — algorithm confusion / downgrade")
    class AlgorithmConfusionContract {

        @Test
        @DisplayName("rejects an unsecured alg=none token (signature-stripping downgrade)")
        void rejectsAlgNoneToken() {
            try (LoanedBuffer token = createAlgNoneTokenBuffer()) {
                assertThatThrownBy(() -> provider.authenticate(token))
                        .isInstanceOfSatisfying(SecurityAuthenticationException.class, ex ->
                                assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_SEC_2002));
            }
        }

        @Test
        @DisplayName("rejects an HS256-over-public-key token (RS256->HS256 confusion)")
        void rejectsAlgConfusionToken() {
            try (LoanedBuffer token = createAlgConfusionTokenBuffer()) {
                assertThatThrownBy(() -> provider.authenticate(token))
                        .isInstanceOfSatisfying(SecurityAuthenticationException.class, ex ->
                                assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_SEC_2002));
            }
        }

        @Test
        @DisplayName("algorithm-confusion denial is deterministic across repeated attempts")
        void confusionDenialIsDeterministic() {
            for (int i = 0; i < 5; i++) {
                try (LoanedBuffer token = createAlgConfusionTokenBuffer()) {
                    assertThatThrownBy(() -> provider.authenticate(token))
                            .isInstanceOfSatisfying(SecurityAuthenticationException.class, ex ->
                                    assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_SEC_2002));
                }
            }
        }
    }

    // =========================================================================
    // systemStorageContext()
    // =========================================================================

    @Nested
    @DisplayName("systemStorageContext() contract")
    class SystemStorageContract {

        @Test
        @DisplayName("returns non-null context")
        void systemContextNonNull() {
            StorageContext ctx = provider.systemStorageContext();
            assertThat(ctx).isNotNull();
        }

        @Test
        @DisplayName("has non-null strategy")
        void systemContextHasStrategy() {
            StorageContext ctx = provider.systemStorageContext();
            assertThat(ctx.strategy()).isNotNull();
        }

        @Test
        @DisplayName("attributes are non-null and unmodifiable")
        void systemContextAttributesSafe() {
            StorageContext ctx = provider.systemStorageContext();
            var attributes = ctx.attributes();
            assertThat(attributes).isNotNull();
            assertThatThrownBy(() -> attributes.put("x", "y"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("is stable — returns equivalent context on repeated calls")
        void systemContextIsStable() {
            StorageContext a = provider.systemStorageContext();
            StorageContext b = provider.systemStorageContext();
            assertThat(a.strategy()).isEqualTo(b.strategy());
            assertThat(a.isolationKey()).isEqualTo(b.isolationKey());
        }
    }

    // =========================================================================
    // ServiceLoader integration
    // =========================================================================

    @Nested
    @DisplayName("ServiceLoader integration")
    class ServiceLoaderIntegration {

        @Test
        @DisplayName("SecurityProvider is discoverable via ServiceLoader")
        void discoverableViaServiceLoader() {
            ServiceLoader<SecurityProvider> loader = ServiceLoader.load(SecurityProvider.class);
            assertThat(loader.stream().count())
                    .as("At least one SecurityProvider must be on the classpath")
                    .isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("Highest-priority provider wins selection")
        void highestPriorityWins() {
            SecurityProvider selected = ServiceLoader.load(SecurityProvider.class)
                    .stream()
                    .map(ServiceLoader.Provider::get)
                    .max(Comparator.comparingInt(SecurityProvider::priority))
                    .orElseThrow();
            // The selected provider must be the same tier as our test provider
            assertThat(selected.priority())
                    .isGreaterThanOrEqualTo(provider.priority());
        }
    }

    // =========================================================================
    // Thread safety — Virtual Thread concurrent authenticate()
    // =========================================================================

    @Nested
    @DisplayName("Thread safety — concurrent authenticate()")
    class ThreadSafety {

        @Test
        @DisplayName("100 concurrent VT authenticate() calls — no crashes, no data corruption")
        void concurrentAuthenticate() throws InterruptedException {
            AtomicReference<Throwable> failure = new AtomicReference<>();

            try (var scope = StructuredTaskScope.open()) {
                for (int i = 0; i < 100; i++) {
                    scope.fork(() -> {
                        try (LoanedBuffer token = createValidTokenBuffer()) {
                            AuthenticationResult result = provider.authenticate(token);
                            if (result.principal().principalId() == null) {
                                failure.set(new AssertionError("principalId was null"));
                            }
                        } catch (Exception e) {
                            failure.compareAndSet(null, e);
                        }
                        return null;
                    });
                }
                scope.join();
            }

            assertThat(failure.get())
                    .as("Concurrent authenticate() must be thread-safe")
                    .isNull();
        }
    }

    // =========================================================================
    // JWKS key selection and claims validation — ADR-013 §4, §6
    // =========================================================================

    @Nested
    @DisplayName("JWKS key selection contract (ADR-013 §6)")
    class JwksKeySelectionContract {

        @Test
        @DisplayName("token with known kid is accepted")
        void knownKidIsAccepted() {
            try (LoanedBuffer token = createTokenWithKnownKid()) {
                AuthenticationResult result = provider.authenticate(token);
                assertThat(result).isNotNull();
                assertThat(result.principal()).isNotNull();
            }
        }

        @Test
        @DisplayName("token with unknown kid is denied with EX-SEC-2002")
        void unknownKidIsDenied() {
            try (LoanedBuffer token = createTokenWithUnknownKid()) {
                assertThatThrownBy(() -> provider.authenticate(token))
                        .isInstanceOfSatisfying(SecurityAuthenticationException.class, ex ->
                                assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_SEC_2002));
            }
        }

        @Test
        @DisplayName("token without kid is denied when kid selection is required")
        void missingKidIsDenied() {
            try (LoanedBuffer token = createTokenWithMissingKid()) {
                assertThatThrownBy(() -> provider.authenticate(token))
                        .isInstanceOfSatisfying(SecurityAuthenticationException.class, ex ->
                                assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_SEC_2002));
            }
        }
    }

    @Nested
    @DisplayName("JWT claims validation contract (ADR-013 §4)")
    class JwtClaimsValidationContract {

        @Test
        @DisplayName("expired token is denied with EX-SEC-2002")
        void expiredTokenIsDenied() {
            try (LoanedBuffer token = createExpiredTokenBuffer()) {
                assertThatThrownBy(() -> provider.authenticate(token))
                        .isInstanceOfSatisfying(SecurityAuthenticationException.class, ex ->
                                assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_SEC_2002));
            }
        }

        @Test
        @DisplayName("wrong issuer is denied with EX-SEC-2002")
        void wrongIssuerIsDenied() {
            try (LoanedBuffer token = createWrongIssuerTokenBuffer()) {
                assertThatThrownBy(() -> provider.authenticate(token))
                        .isInstanceOfSatisfying(SecurityAuthenticationException.class, ex ->
                                assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_SEC_2002));
            }
        }

        @Test
        @DisplayName("wrong audience is denied with EX-SEC-2002")
        void wrongAudienceIsDenied() {
            try (LoanedBuffer token = createWrongAudienceTokenBuffer()) {
                assertThatThrownBy(() -> provider.authenticate(token))
                        .isInstanceOfSatisfying(SecurityAuthenticationException.class, ex ->
                                assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_SEC_2002));
            }
        }

        @Test
        @DisplayName("tampered signature is denied with EX-SEC-2002")
        void tamperedSignatureIsDenied() {
            try (LoanedBuffer token = createTamperedSignatureBuffer()) {
                assertThatThrownBy(() -> provider.authenticate(token))
                        .isInstanceOfSatisfying(SecurityAuthenticationException.class, ex ->
                                assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_SEC_2002));
            }
        }

        @Test
        @DisplayName("deny on uncertainty is deterministic across repeated calls")
        void denyOnUncertaintyIsDeterministic() {
            for (int i = 0; i < 5; i++) {
                try (LoanedBuffer token = createExpiredTokenBuffer()) {
                    assertThatThrownBy(() -> provider.authenticate(token))
                            .isInstanceOfSatisfying(SecurityAuthenticationException.class, ex ->
                                    assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_SEC_2002));
                }
            }
        }
    }

    // =========================================================================
    // StorageContext isolation strategy contract (KernelIsolationClaims)
    // =========================================================================

    @Nested
    @DisplayName("StorageContext isolation strategy contract")
    class IsolationStrategyContract {

        @Test
        @DisplayName("absent isolation claim \u2192 SHARED strategy (fail-closed default)")
        void assert_authenticate_returns_shared_when_no_isolation_claim() {
            try (LoanedBuffer token = createTokenWithNoIsolationClaim()) {
                StorageContext storage = provider.authenticate(token).storage();
                assertThat(storage.strategy())
                        .as("absent isolation claim MUST produce SHARED strategy (fail-closed)")
                        .isEqualTo(StorageContext.IsolationStrategy.SHARED);
            }
        }

        @Test
        @DisplayName("SEPARATED_SCHEMA claim \u2192 SEPARATED_SCHEMA strategy with correct schemaName")
        void assert_authenticate_returns_separated_schema_from_jwt_claim() {
            try (LoanedBuffer token = createTokenWithSeparatedSchemaStrategy()) {
                StorageContext storage = provider.authenticate(token).storage();
                assertThat(storage.strategy())
                        .as("SEPARATED_SCHEMA claim must produce SEPARATED_SCHEMA strategy")
                        .isEqualTo(StorageContext.IsolationStrategy.SEPARATED_SCHEMA);
                assertThat(storage.schemaName())
                        .as("schemaName must be present and equal expectedSeparatedSchemaName()")
                        .isPresent()
                        .contains(expectedSeparatedSchemaName());
            }
        }

        @Test
        @DisplayName("DEDICATED claim \u2192 DEDICATED strategy with correct dataSourceKey")
        void assert_authenticate_returns_dedicated_from_jwt_claim() {
            try (LoanedBuffer token = createTokenWithDedicatedStrategy()) {
                StorageContext storage = provider.authenticate(token).storage();
                assertThat(storage.strategy())
                        .as("DEDICATED claim must produce DEDICATED strategy")
                        .isEqualTo(StorageContext.IsolationStrategy.DEDICATED);
                assertThat(storage.dataSourceKey())
                        .as("dataSourceKey must be present and equal expectedDedicatedDataSourceKey()")
                        .isPresent()
                        .contains(expectedDedicatedDataSourceKey());
            }
        }

        @Test
        @DisplayName("SEPARATED_SCHEMA claim without schema name \u2192 terminal deny (EX-SEC-2002)")
        void assert_authenticate_denies_on_missing_schema_claim() {
            try (LoanedBuffer token = createTokenWithSeparatedSchemaMissingSchemaName()) {
                assertThatThrownBy(() -> provider.authenticate(token))
                        .as("a declared SEPARATED_SCHEMA strategy with a missing/blank schema name "
                                + "MUST be denied, NOT silently downgraded to SHARED \u2014 downgrade is "
                                + "fail-OPEN (weakens the provisioned isolation tier) per S-P0-07 / "
                                + "ADR-012 \u00a74a (amended)")
                        .isInstanceOfSatisfying(SecurityAuthenticationException.class, ex ->
                                assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_SEC_2002));
            }
        }

        @Test
        @DisplayName("DEDICATED claim without datasource key \u2192 terminal deny (EX-SEC-2002)")
        void assert_authenticate_denies_on_missing_datasource_key_claim() {
            try (LoanedBuffer token = createTokenWithDedicatedMissingDataSourceKey()) {
                assertThatThrownBy(() -> provider.authenticate(token))
                        .as("a declared DEDICATED strategy with a missing/blank datasource key MUST "
                                + "be denied, NOT downgraded to SHARED (fail-OPEN) \u2014 S-P0-07")
                        .isInstanceOfSatisfying(SecurityAuthenticationException.class, ex ->
                                assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_SEC_2002));
            }
        }

        @Test
        @DisplayName("unrecognized strategy value \u2192 terminal deny (EX-SEC-2002)")
        void assert_authenticate_denies_on_unrecognized_strategy() {
            try (LoanedBuffer token = createTokenWithUnrecognizedStrategy()) {
                assertThatThrownBy(() -> provider.authenticate(token))
                        .as("a declared-but-unrecognised strategy value cannot be honoured and MUST "
                                + "be denied \u2014 downgrading to SHARED grants a session on an injection "
                                + "probe instead of rejecting it (S-P0-07 / ADR-012 \u00a74a amended)")
                        .isInstanceOfSatisfying(SecurityAuthenticationException.class, ex ->
                                assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_SEC_2002));
            }
        }
    }

    // =========================================================================
    // JWKS key rotation contract (overlap window + stale-fetch budget) — ADR-012
    // =========================================================================

    @Nested
    @DisplayName("JWKS key rotation contract (overlap + stale-fetch, fail-closed)")
    class JwksKeyRotationContract {

        @Test
        @DisplayName("overlap-fresh: old-kid token still authenticates within overlap window")
        void overlapFreshStillAuthenticates() {
            try (RotationHarness harness = createRotationHarness()) {
                assumeTrue(harness != null, "Provider does not support key rotation");

                harness.installNewKeySet();
                // Cross the stale-fetch budget to trigger a rotation, but stay inside overlap.
                harness.advanceClock(Duration.ofMinutes(90L));

                try (LoanedBuffer token = harness.tokenUnderOriginalKid()) {
                    AuthenticationResult result = harness.provider().authenticate(token);
                    assertThat(result)
                            .as("old-kid token must still verify while overlap window is open")
                            .isNotNull();
                    assertThat(result.principal()).isNotNull();
                }
            }
        }

        @Test
        @DisplayName("cutover-deny: old-kid token rejected (EX-SEC-2002) once overlap expires")
        void cutoverDeniesAfterOverlap() {
            try (RotationHarness harness = createRotationHarness()) {
                assumeTrue(harness != null, "Provider does not support key rotation");

                harness.installNewKeySet();
                // Trigger rotation, then advance well past the overlap window.
                harness.advanceClock(Duration.ofMinutes(90L));
                try (LoanedBuffer warmup = harness.tokenUnderOriginalKid()) {
                    harness.provider().authenticate(warmup);
                }
                harness.advanceClock(Duration.ofMinutes(90L));

                try (LoanedBuffer token = harness.tokenUnderOriginalKid()) {
                    assertThatThrownBy(() -> harness.provider().authenticate(token))
                            .as("post-cutover old-kid token MUST be denied, never fail-open")
                            .isInstanceOfSatisfying(SecurityAuthenticationException.class, ex -> {
                                assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_SEC_2002);
                                assertReasonIs(ex, "kid-rotated-out");
                            });
                }
            }
        }

        @Test
        @DisplayName("stale-fetch-deny: failed refresh past stale budget → deterministic deny (EX-SEC-2002)")
        void staleFetchDeniesDeterministically() {
            try (RotationHarness harness = createRotationHarness()) {
                assumeTrue(harness != null, "Provider does not support key rotation");

                harness.failNextRefresh();
                harness.advanceClock(Duration.ofMinutes(90L));

                try (LoanedBuffer token = harness.tokenUnderOriginalKid()) {
                    assertThatThrownBy(() -> harness.provider().authenticate(token))
                            .as("stale key set with failed refresh MUST deny deterministically, never fail-open")
                            .isInstanceOfSatisfying(SecurityAuthenticationException.class, ex -> {
                                assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_SEC_2002);
                                assertReasonIs(ex, "jwks-stale");
                            });
                }
            }
        }

        /**
         * Asserts the deny carries the expected secret-safe reason in the
         * {@code rawArgs[1]} telemetry slot (see {@link SecurityAuthenticationException}),
         * discriminating cutover deny from stale-fetch deny at the contract level.
         */
        private void assertReasonIs(SecurityAuthenticationException ex, String expectedReason) {
            Object[] raw = ex.rawArgs();
            assertThat(raw)
                    .as("deny must carry secret-safe telemetry args [tokenType, reason]")
                    .hasSizeGreaterThanOrEqualTo(2);
            assertThat((String) raw[1])
                    .as("deny reason must be the expected secret-safe label")
                    .isEqualTo(expectedReason);
        }
    }
}



