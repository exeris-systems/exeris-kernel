/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.security;

import com.nimbusds.jwt.SignedJWT;
import eu.exeris.kernel.community.http.CommunityHttpProvider;
import eu.exeris.kernel.community.transport.CommunityOutboundTls;
import eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException;
import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.security.AuthenticationResult;
import eu.exeris.kernel.spi.security.PrincipalContext;
import eu.exeris.kernel.spi.security.StorageContext;
import eu.exeris.kernel.spi.security.identity.ClaimsMapper;
import eu.exeris.kernel.spi.security.identity.IdentityProvider;
import eu.exeris.kernel.spi.security.identity.IdentityStorageMapping;
import eu.exeris.kernel.spi.security.identity.KeyRotationPolicy;
import eu.exeris.kernel.spi.security.identity.TokenValidator;
import eu.exeris.kernel.spi.security.identity.VerifiedClaims;

import java.net.URI;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Community OIDC/JWKS {@link IdentityProvider} (ADR-040): composes the {@link CommunityOidcTokenValidator}
 * cryptographic pipeline, the default {@link CommunityClaimsMapper} identity mapping, and the
 * kernel-owned fail-closed {@link IdentityStorageMapping} into a single
 * {@link AuthenticationResult}.
 *
 * <p>The static {@code kid → RSAPublicKey} map path resolves keys directly from a fixed snapshot;
 * the rotating-key-set path composes the {@link CommunityRotatingKeySet} seam through the
 * {@link JwksKeyResolver} constructor.
 *
 * <p>A provider built by {@link #overJwksEndpoint} owns the client engine its key set is fetched
 * with, and {@link #close()} releases it. A provider over a fixed key map holds nothing to close.
 *
 * @since 0.10
 */
// TooManyMethods: the IdentityProvider SPI surface, the two withers, the JWKS assembly factory with
// its engine seam, and close — each a public or test-seam contract rather than a helper to inline.
@SuppressWarnings("PMD.TooManyMethods")
public final class CommunityOidcIdentityProvider implements IdentityProvider, AutoCloseable {

    private static final String PROVIDER_ID = "oidc-community";
    private static final String PROVIDER_NAME = "ExerisCommunity/OIDC-JWKS";
    private static final String JWT_TYPE = "JWT";

    private final TokenValidator tokenValidator;
    private final ClaimsMapper claimsMapper;
    private final String expectedIssuer;
    private final boolean sharedScopeEnforced;
    private final Runnable closeAction;

    /**
     * Creates a provider that verifies tokens against a fixed, non-rotating key-set map.
     *
     * @param keysByKid a snapshot of verification keys ({@code kid} to RSA public key); never
     *                  refreshed, so a rotated signing key requires constructing a new provider
     * @param expectedIssuer the {@code iss} claim value a token must present to be accepted
     * @param expectedAudience the {@code aud} claim value a token must present to be accepted
     */
    public CommunityOidcIdentityProvider(Map<String, RSAPublicKey> keysByKid,
                                         String expectedIssuer,
                                         String expectedAudience) {
        this(new CommunityOidcTokenValidator(keysByKid, expectedIssuer, expectedAudience), expectedIssuer);
    }

    /* default */ CommunityOidcIdentityProvider(JwksKeyResolver keyResolver,
                                                String expectedIssuer,
                                                String expectedAudience) {
        this(new CommunityOidcTokenValidator(keyResolver, expectedIssuer, expectedAudience), expectedIssuer);
    }

    private CommunityOidcIdentityProvider(TokenValidator tokenValidator, String expectedIssuer) {
        this(tokenValidator, expectedIssuer, () -> { });
    }

    private CommunityOidcIdentityProvider(TokenValidator tokenValidator, String expectedIssuer,
                                          Runnable closeAction) {
        this(tokenValidator, expectedIssuer, false, new CommunityClaimsMapper(), closeAction);
    }

    private CommunityOidcIdentityProvider(TokenValidator tokenValidator, String expectedIssuer,
                                          boolean sharedScopeEnforced, ClaimsMapper claimsMapper,
                                          Runnable closeAction) {
        this.tokenValidator = Objects.requireNonNull(tokenValidator, "tokenValidator must not be null");
        this.claimsMapper = Objects.requireNonNull(claimsMapper, "claimsMapper must not be null");
        this.expectedIssuer = Objects.requireNonNull(expectedIssuer, "expectedIssuer must not be null");
        this.sharedScopeEnforced = sharedScopeEnforced;
        this.closeAction = closeAction;
    }

    /**
     * Returns a provider that maps verified claims onto a principal with {@code claimsMapper}
     * instead of the Community default.
     *
     * <p>{@link ClaimsMapper} is documented as the only application-customisable point in the
     * identity pipeline: an application needing a different subject or scope shape supplies one
     * here instead of reimplementing {@link IdentityProvider} outright. It maps identity only:
     * tenant-isolation routing stays kernel-owned and fail-closed (ADR-012), so a custom mapper
     * cannot widen what a token may reach.
     *
     * <p>Returns a new provider rather than mutating, for the same reason as
     * {@link #enforcingSharedScope()} — the mapping takes part in a security decision on every
     * request and is fixed at construction, never swapped behind a live provider. The two withers
     * compose in either order. The new provider shares this one's JWKS engine, if it has one, so
     * closing either closes it for both.
     *
     * @param claimsMapper the mapping to use; must not be {@code null}
     * @return a provider identical to this one but mapping claims with {@code claimsMapper}
     * @since 0.11
     */
    public CommunityOidcIdentityProvider withClaimsMapper(ClaimsMapper claimsMapper) {
        return new CommunityOidcIdentityProvider(
                tokenValidator, expectedIssuer, sharedScopeEnforced, claimsMapper, closeAction);
    }

    /**
     * Declares that this deployment's storage schema implements the shared-scope policy contract, so a
     * token declaring a shared scope resolves to a scoped context instead of being denied
     * (see {@link IdentityStorageMapping#SHARED_SCOPE_ENFORCED_KEY}).
     *
     * <p>Returns a new provider rather than mutating. The flag takes part in a security decision on
     * every request, so it is fixed at construction and never becomes reconfigurable state behind a live
     * provider — and the default stays fail-closed for anyone who does not call this. The new
     * provider shares this one's JWKS engine, if it has one, so closing either closes it for both.
     *
     * @return a provider identical to this one but honouring declared shared scopes
     * @since 0.11
     */
    public CommunityOidcIdentityProvider enforcingSharedScope() {
        return new CommunityOidcIdentityProvider(tokenValidator, expectedIssuer, true, claimsMapper, closeAction);
    }

    /**
     * Assembles an OIDC provider that refreshes its verification keys from a live JWKS endpoint:
     * a {@link CommunityJwksHttpKeySetSource} (one {@code GET} of {@code jwksUri}) feeds the
     * {@link CommunityRotatingKeySet}, applying {@code policy} on the supplied {@code clock}.
     * {@code initialKeys} seeds the first generation (may be empty to force a fetch on first use).
     *
     * <p><strong>The scheme decides the transport.</strong> Every key the endpoint serves
     * authenticates the tokens signed under its {@code kid}, so the key set is fetched over a
     * connection that authenticated its server, or not at all (ADR-012 §4). The provider builds its
     * own client engine from the scheme of {@code jwksUri}, whatever is bound where it is assembled:
     * <ul>
     *   <li>{@code https}: TLS that verifies the server's chain against the configured trust and its
     *       name against the URI's host. With no crypto provider bound, under
     *       {@code -Dexeris.transport.tls=false}, or with a bound provider that cannot verify an
     *       outbound peer, no provider is built ({@code EX-NET-4004}).</li>
     *   <li>{@code http}: plaintext, which the application asks for by writing the scheme — for an
     *       identity provider on a trusted network, or in development. The carrier's
     *       {@code TransportTlsClientPosture} event records it as {@code PLAINTEXT_REQUIRED}.</li>
     * </ul>
     *
     * <p>The returned provider owns that engine; {@link #close()} releases it.
     *
     * @param jwksUri the absolute {@code http} or {@code https} URI the JWKS document is served from
     * @param initialKeys the key set installed before any refresh; may be empty to defer the
     *                    first fetch to the first {@link #authenticate} call
     * @param policy the overlap window and stale-fetch budget governing key rotation
     * @param clock the clock driving both rotation timing and token-expiry checks
     * @param expectedIssuer the {@code iss} claim value a token must present to be accepted
     * @param expectedAudience the {@code aud} claim value a token must present to be accepted
     * @return a provider that resolves verification keys through the rotating key set
     * @throws IllegalArgumentException if {@code jwksUri} is not an absolute {@code http} or
     *         {@code https} URI with a host, or carries user information or a fragment
     * @throws eu.exeris.kernel.spi.exceptions.transport.TransportException ({@code EX-NET-4004}) if
     *         {@code jwksUri} is {@code https} and no engine that verifies its server can be built
     * @since 0.12
     */
    public static CommunityOidcIdentityProvider overJwksEndpoint(
            URI jwksUri,
            Map<String, RSAPublicKey> initialKeys, KeyRotationPolicy policy, Clock clock,
            String expectedIssuer, String expectedAudience) {
        return overJwksEndpoint(jwksUri, initialKeys, policy, clock, expectedIssuer, expectedAudience,
                new CommunityHttpProvider()::createClientEngine);
    }

    /**
     * As {@link #overJwksEndpoint(URI, Map, KeyRotationPolicy, Clock, String, String)}, with the
     * fetch engine built by {@code engineFactory}.
     *
     * @param jwksUri          the absolute {@code http} or {@code https} URI of the JWKS document
     * @param initialKeys      the key set installed before any refresh
     * @param policy           the overlap window and stale-fetch budget governing key rotation
     * @param clock            the clock driving both rotation timing and token-expiry checks
     * @param expectedIssuer   the {@code iss} claim value a token must present to be accepted
     * @param expectedAudience the {@code aud} claim value a token must present to be accepted
     * @param engineFactory    builds the fetch engine from its configuration and outbound TLS
     * @return a provider that owns the engine {@code engineFactory} built
     */
    // AvoidCatchingGenericException: the engine is closed on any assembly failure, then the failure
    // rethrown; a failed close is attached to it, never allowed to replace it.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    /* default */ static CommunityOidcIdentityProvider overJwksEndpoint(
            URI jwksUri,
            Map<String, RSAPublicKey> initialKeys, KeyRotationPolicy policy, Clock clock,
            String expectedIssuer, String expectedAudience,
            BiFunction<HttpConfig, CommunityOutboundTls, HttpClientEngine> engineFactory) {
        CommunityJwksEndpoint endpoint = CommunityJwksEndpoint.of(jwksUri);
        CommunityJwksHttpKeySetSource source = CommunityJwksHttpKeySetSource.overEndpoint(endpoint, engineFactory);
        try {
            JwksKeyResolver resolver = new CommunityRotatingKeySet(initialKeys, source, policy, clock);
            // Same clock drives both rotation timing and token-expiry — no wall-clock skew between them.
            TokenValidator validator =
                    new CommunityOidcTokenValidator(resolver, expectedIssuer, expectedAudience, clock);
            return new CommunityOidcIdentityProvider(validator, expectedIssuer, source::close);
        } catch (RuntimeException | Error assemblyFailure) {
            try {
                source.close();
            } catch (RuntimeException | Error closeFailure) {
                assemblyFailure.addSuppressed(closeFailure);
            }
            throw assemblyFailure;
        }
    }

    /**
     * Releases the client engine the JWKS key set is fetched with, when this provider was built by
     * {@link #overJwksEndpoint}; does nothing for a provider over a fixed key map. Idempotent. A key
     * refresh after close fails, and a token that needs one is denied ({@code EX-SEC-2002}).
     *
     * @since 0.12
     */
    @Override
    public void close() {
        closeAction.run();
    }

    /** Package-private: lets the wiring test observe which mapper this provider was built with. */
    /* default */ ClaimsMapper claimsMapper() {
        return claimsMapper;
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Always {@code "oidc-community"}.
     */
    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Always {@code "ExerisCommunity/OIDC-JWKS"}.
     */
    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Always {@code 0}, the Community-tier priority.
     */
    @Override
    public int priority() {
        return 0;
    }

    /**
     * Routing peek: this provider attempts a token whose unverified {@code iss} matches the
     * configured issuer. Grants nothing — every trust decision flows through {@link #authenticate}.
     * Never throws; an unrecognised or unparseable token simply returns {@code false}.
     */
    @Override
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // routing peek MUST NOT throw
    public boolean canAttempt(LoanedBuffer rawToken) {
        try {
            String compactJwt = CommunityOidcTokenValidator.readCompactJwt(rawToken);
            String issuer = SignedJWT.parse(compactJwt).getJWTClaimsSet().getIssuer();
            return expectedIssuer.equals(issuer);
        } catch (Exception _) {
            return false;
        }
    }

    /**
     * {@inheritDoc}
     *
     * @implNote On {@link SecurityAuthenticationException} ({@code EX-SEC-2002}) this commits an
     *           {@code IdentityRejection} JFR event carrying the validator's failure reason
     *           before rethrowing unchanged — the caller sees the same denial either way.
     */
    @Override
    public AuthenticationResult authenticate(LoanedBuffer rawToken) {
        long startNanos = System.nanoTime();
        try {
            VerifiedClaims claims = tokenValidator.validate(rawToken);
            PrincipalContext principal = claimsMapper.map(claims);
            StorageContext storage = IdentityStorageMapping.fromClaims(
                    claims, principal.principalId(), JWT_TYPE, sharedScopeEnforced);
            // Single-phase JFR commit AFTER (possibly blocking) validation — never straddle a VT.
            CommunityIdentityJfrEvents.emitValidation(PROVIDER_ID, claims.issuer(), startNanos);
            return new AuthenticationResult(principal, storage);
        } catch (SecurityAuthenticationException ex) {
            Object[] args = ex.rawArgs();
            String reason = args.length > 1 ? String.valueOf(args[1]) : "validation-failed";
            CommunityIdentityJfrEvents.emitRejection(PROVIDER_ID, reason);
            throw ex;
        }
    }
}
