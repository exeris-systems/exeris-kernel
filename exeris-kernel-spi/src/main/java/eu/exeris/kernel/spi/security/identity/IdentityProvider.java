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
 * <h2>Multi-IDP dispatch (fail-closed, ADR-012)</h2>
 * <p>When several providers are registered, {@link IdentityProviderRegistry} selects exactly one
 * by priority + {@link #canAttempt(LoanedBuffer)}. The selected provider's {@link #authenticate}
 * failure is <b>terminal</b> — the dispatcher MUST NOT fall back to another provider. Re-dispatch
 * on failure would be fail-open: a token rejected by its rightful issuer's provider could be
 * accepted by a laxer one (token-confusion).
 *
 * <h2>The Wall (ADR-006)</h2>
 * <p>This interface has zero knowledge of JWT/JWKS/OIDC/PASETO libraries. Validator dependencies
 * (Nimbus, a PASETO lib, an HTTP client for JWKS fetch) are Community/Enterprise-tier concerns.
 *
 * <h2>Thread safety</h2>
 * <p>Implementations MUST be thread-safe; a single instance is shared across all virtual threads
 * for the kernel's lifetime.
 *
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
     * Selection priority. Higher wins on registry collision. Community returns {@code 0};
     * an Enterprise overlay returns {@code 100} (open-core tier convention).
     *
     * @return priority; default {@code 0}
     */
    default int priority() {
        return 0;
    }

    /**
     * Cheap routing pre-check: would this provider attempt to validate the given token?
     *
     * <p>This is <b>routing, not trust</b>. It may peek unverified structure (token format, an
     * unverified {@code iss}) to let the registry choose a candidate, but it grants nothing — every
     * authorization decision flows through {@link #authenticate}. It MUST be cheap, side-effect-free,
     * and MUST NOT throw; an unrecognised token simply returns {@code false}.
     *
     * @param rawToken the raw credential in a caller-owned loaned buffer; never {@code null}
     * @return {@code true} if this provider is the dispatch candidate for the token
     */
    boolean canAttempt(LoanedBuffer rawToken);

    /**
     * Validates the raw token and returns the authenticated principal + storage context.
     *
     * <p>The raw token arrives in a caller-owned {@link LoanedBuffer}; the provider MUST NOT close
     * or retain it beyond this call. On any validation failure this MUST throw
     * {@link eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException}
     * ({@code EX-SEC-2002}) — never return {@code null}, never fail-open.
     *
     * @param rawToken the raw credential in a caller-owned loaned buffer; never {@code null}
     * @return the authentication result; never {@code null}
     * @throws eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException
     *         on any validation failure (terminal deny)
     */
    AuthenticationResult authenticate(LoanedBuffer rawToken);
}
