/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.security.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * SPI: Format-blind view of the claims a {@link TokenValidator} has <em>already cryptographically
 * verified</em>.
 *
 * <p>This is the carrier that flows from {@link TokenValidator#validate} into
 * {@link ClaimsMapper#map} and {@link IdentityStorageMapping}. It deliberately exposes no
 * wire-format vocabulary — there is no JWT {@code JWSHeader}, no Nimbus {@code JWTClaimsSet}, no
 * PASETO footer here. A driver maps its concrete verified representation onto this view; the kernel
 * never sees the underlying library type (The Wall, ADR-006).
 *
 * <h2>Trust contract</h2>
 * <p>Every accessor returns material from a token whose signature, issuer, audience and time
 * window have <b>already</b> passed validation. Implementations MUST NOT return claims from an
 * unverified or partially-verified token — producing a {@code VerifiedClaims} instance is itself
 * the assertion that verification succeeded.
 *
 * @since 0.10.0
 * @see TokenValidator
 * @see ClaimsMapper
 */
public interface VerifiedClaims {

    /**
     * The verified subject identifier (the OIDC {@code sub} claim, or the driver's equivalent).
     *
     * @return non-null, non-blank subject identifier
     */
    String subject();

    /**
     * The verified token issuer (the OIDC {@code iss} claim, or the driver's equivalent).
     *
     * @return non-null issuer identifier
     */
    String issuer();

    /**
     * The verified audience values (the OIDC {@code aud} claim).
     *
     * @return immutable, possibly-empty set of audience identifiers; never {@code null}
     */
    Set<String> audience();

    /**
     * The verified expiry instant, when the token carries one.
     *
     * @return the expiry instant, or {@link Optional#empty()} if the token is non-expiring
     */
    Optional<Instant> expiresAt();

    /**
     * Reads a single-valued string claim by name.
     *
     * @param name the claim name (e.g. a {@link eu.exeris.kernel.spi.security.KernelIsolationClaims}
     *             key); never {@code null}
     * @return the claim value, or {@link Optional#empty()} when absent. A claim present but not
     *         representable as a single string is treated as absent — drivers that need to deny on a
     *         malformed isolation claim do so during validation, not here.
     */
    Optional<String> claim(String name);

    /**
     * Reads a multi-valued string claim by name (roles, scopes, groups).
     *
     * @param name the claim name; never {@code null}
     * @return an immutable set of the claim's string values; empty when the claim is absent.
     *         Never {@code null}.
     */
    Set<String> stringSetClaim(String name);
}
