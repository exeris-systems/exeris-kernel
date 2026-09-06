/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.security;

import com.nimbusds.jwt.JWTClaimsSet;
import eu.exeris.kernel.spi.security.identity.VerifiedClaims;

import java.text.ParseException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@link VerifiedClaims} view over a Nimbus {@link JWTClaimsSet} that has already passed the
 * {@link CommunityOidcTokenValidator} pipeline (signature, issuer, audience, time).
 *
 * <p>The Nimbus type never crosses The Wall — it stays packaged here, behind the SPI interface.
 * Per the {@link VerifiedClaims#claim(String)} contract, a claim that is present but not a single
 * string is treated as absent; a driver that must deny on a malformed claim (e.g. a wrong-typed
 * isolation-strategy claim) does so during validation, not here.
 *
 * @since 0.10
 */
final class CommunityVerifiedClaims implements VerifiedClaims {

    private final JWTClaimsSet claims;

    /* default */ CommunityVerifiedClaims(JWTClaimsSet claims) {
        this.claims = Objects.requireNonNull(claims, "claims must not be null");
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Returns the underlying {@code JWTClaimsSet}'s {@code sub} claim verbatim.
     */
    @Override
    public String subject() {
        return claims.getSubject();
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Returns the underlying {@code JWTClaimsSet}'s {@code iss} claim verbatim.
     */
    @Override
    public String issuer() {
        return claims.getIssuer();
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Returns the underlying {@code JWTClaimsSet}'s {@code aud} claim as an immutable
     *           set, or an empty set when the claim is absent.
     */
    @Override
    public Set<String> audience() {
        List<String> aud = claims.getAudience();
        return aud == null ? Set.of() : Set.copyOf(aud);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Converts the underlying {@code JWTClaimsSet}'s {@code exp} claim from its
     *           legacy {@code Date} representation to an {@link Instant} via epoch-millis;
     *           empty when the claim is absent.
     */
    @Override
    public Optional<Instant> expiresAt() {
        // getExpirationTime() yields a legacy date type (Nimbus API). Convert via epoch-millis so
        // the source neither names the banned java.util.* type (EXERIS L0 rule) nor collapses to a
        // method reference on it (PMD LambdaCanBeMethodReference); same millisecond semantics.
        return Optional.ofNullable(claims.getExpirationTime())
                .map(exp -> Instant.ofEpochMilli(exp.getTime()));
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Reads {@code name} as a single string via the underlying {@code JWTClaimsSet};
     *           a claim present but not parseable as a single string yields
     *           {@link Optional#empty()}, per the interface contract.
     */
    @Override
    public Optional<String> claim(String name) {
        try {
            return Optional.ofNullable(claims.getStringClaim(name));
        } catch (ParseException _) {
            // Present but not a single string → treated as absent (VerifiedClaims contract).
            return Optional.empty();
        }
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Reads {@code name} as a string list via the underlying {@code JWTClaimsSet};
     *           empty when the claim is absent or not representable as a string list.
     */
    @Override
    public Set<String> stringSetClaim(String name) {
        try {
            List<String> values = claims.getStringListClaim(name);
            return values == null ? Set.of() : Set.copyOf(values);
        } catch (ParseException _) {
            return Set.of();
        }
    }
}
