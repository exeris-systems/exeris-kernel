/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.security;

import com.nimbusds.jose.Algorithm;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.KeyType;

/**
 * The one JWS algorithm a verification key is installed for.
 *
 * <p>A key verifies one algorithm, fixed when it is installed; the token header can only agree with
 * it, never choose it. The pin is derived from the key's own JWK description ({@code kty}, {@code crv}
 * and, when present, {@code alg}), so an algorithm the key was not published for is never offered to
 * a verifier.
 *
 * @since 0.13
 */
enum PinnedJwsAlgorithm {

    /** RSASSA-PKCS1-v1_5 with SHA-256 over an RSA key. */
    RS256,

    /** EdDSA over Ed25519; the header may spell it {@code EdDSA} or {@code Ed25519}. */
    ED25519;

    /**
     * Whether a token header naming {@code headerAlgorithm} agrees with this pin.
     *
     * @param headerAlgorithm the JWS {@code alg} header value; may be {@code null}
     * @return {@code true} only when the header names exactly an algorithm this pin verifies
     */
    /* default */ boolean admits(JWSAlgorithm headerAlgorithm) {
        if (headerAlgorithm == null) {
            return false;
        }
        return switch (this) {
            case RS256 -> JWSAlgorithm.RS256.equals(headerAlgorithm);
            case ED25519 -> JWSAlgorithm.EdDSA.equals(headerAlgorithm)
                    || JWSAlgorithm.Ed25519.equals(headerAlgorithm);
        };
    }

    /**
     * The pin for a JWK, or {@code null} when the key is not one this validator installs.
     *
     * @param keyType the JWK {@code kty}
     * @param curve   the {@code crv} of an {@code OKP} key; ignored for other key types
     * @param alg     the JWK {@code alg}, or {@code null} when the JWK does not declare one
     * @return the pin, or {@code null} for an unsupported key type, curve or declared algorithm
     */
    /* default */ static PinnedJwsAlgorithm fromJwk(KeyType keyType, Curve curve, Algorithm alg) {
        if (KeyType.RSA.equals(keyType)) {
            return alg == null || JWSAlgorithm.RS256.equals(alg) ? RS256 : null;
        }
        if (KeyType.OKP.equals(keyType) && Curve.Ed25519.equals(curve)) {
            boolean edDsa = alg == null || JWSAlgorithm.EdDSA.equals(alg) || JWSAlgorithm.Ed25519.equals(alg);
            return edDsa ? ED25519 : null;
        }
        return null;
    }
}
