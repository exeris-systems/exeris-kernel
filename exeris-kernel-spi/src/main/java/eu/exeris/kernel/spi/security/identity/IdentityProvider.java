/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.security.identity;

import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.security.AuthenticationResult;

/**
 * SPI: A pluggable identity backend that validates one credential format into an
 * {@link AuthenticationResult} (ADR-040).
 *
 * <p>{@link eu.exeris.kernel.spi.security.SecurityProvider} delegates validation to one selected
 * {@code IdentityProvider}. A typical driver composes a {@link TokenValidator} (cryptographic
 * verification → {@link VerifiedClaims}) with a {@link ClaimsMapper} (verified claims →
 * {@code PrincipalContext}), then pairs the principal with the
 * {@link eu.exeris.kernel.spi.security.StorageContext} derived by {@link IdentityStorageMapping}.
 *
 * <h2>The Wall (ADR-006)</h2>
 * <p>This interface has zero knowledge of JWT/JWKS/OIDC/PASETO libraries. Validator dependencies
 * (Nimbus, a PASETO lib, an HTTP client for JWKS fetch) are Community/Enterprise-tier concerns.
 *
 * <p><b>Thread confinement:</b> any thread — a single instance is shared across all virtual
 * threads for the kernel's lifetime.
 * <p><b>Ownership:</b> the caller owns the {@link LoanedBuffer} passed to {@link #canAttempt} and
 * {@link #authenticate}; an implementation must not close it or retain it beyond that call.
 *
 * @implSpec Implementations must be thread-safe: a single instance is shared across all virtual
 *           threads for the kernel's lifetime.
 * @apiNote When several providers are registered (ADR-012), {@link IdentityProviderRegistry}
 *          selects exactly one by priority + {@link #canAttempt(LoanedBuffer)}. Treat the selected
 *          provider's {@link #authenticate} failure as terminal — never fall back to another
 *          provider on that failure. Re-dispatch would be fail-open: a token rejected by its
 *          rightful issuer's provider could be accepted by a laxer one (token-confusion).
 * @since 0.10
 * @see IdentityProviderRegistry
 * @see TokenValidator
 * @see ClaimsMapper
 * @see IdentityStorageMapping
 */
public interface IdentityProvider {

    /**
     * Stable identifier for this identity backend (e.g. {@code "oidc-community"}).
     *
     * @return stable provider identifier; never {@code null}
     */
    String providerId();

    /**
     * Human-readable name for diagnostics and JFR events.
     *
     * @return human-readable name; never {@code null}
     */
    String providerName();

    /**
     * Selection priority when several providers are registered: higher wins on a registry
     * collision.
     *
     * @return priority; default {@code 0}
     * @implSpec A Community binding must return {@code 0} and an Enterprise overlay must return
     *           {@code 100}, so an Enterprise binding always displaces the Community default
     *           (open-core tier convention).
     */
    default int priority() {
        return 0;
    }

    /**
     * Cheap routing pre-check: would this provider attempt to validate the given token?
     *
     * <p>This is <b>routing, not trust</b>. It may peek unverified structure (token format, an
     * unverified {@code iss}) to let the registry choose a candidate, but it grants nothing — every
     * authorization decision flows through {@link #authenticate}.
     *
     * @param rawToken the raw credential in a caller-owned loaned buffer; never {@code null}
     * @return {@code true} if this provider is the dispatch candidate for the token
     * @implSpec Must be cheap and side-effect-free, and must not throw; an unrecognised token
     *           simply returns {@code false}.
     */
    boolean canAttempt(LoanedBuffer rawToken);

    /**
     * Validates the raw token and returns the authenticated principal + storage context.
     *
     * <p>The raw token arrives in a caller-owned {@link LoanedBuffer}.
     *
     * @param rawToken the raw credential in a caller-owned loaned buffer; never {@code null}
     * @return the authentication result; never {@code null}
     * @throws eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException
     *         {@code EX-SEC-2002} — on any validation failure (terminal deny)
     * @implSpec Must not close or retain {@code rawToken} beyond this call, and must throw rather
     *           than return {@code null} or fail open on any validation failure.
     */
    AuthenticationResult authenticate(LoanedBuffer rawToken);
}
