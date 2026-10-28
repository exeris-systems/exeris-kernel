/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.security;

import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;

import java.security.PublicKey;
import java.security.interfaces.EdECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A verification key together with the single algorithm it is installed for.
 *
 * <p>The pairing is checked at construction, so a key can never be held under an algorithm its
 * material cannot verify. Equality is that of the underlying key plus its pin, which is what lets the
 * rotating key set recognise an unchanged snapshot.
 *
 * @param publicKey the verification key
 * @param algorithm the algorithm the key verifies
 * @since 0.13
 */
record JwsVerificationKey(PublicKey publicKey, PinnedJwsAlgorithm algorithm) {

    private static final String ED25519_CURVE = "Ed25519";

    /**
     * Checks that the key material can verify the algorithm it is paired with.
     *
     * @throws IllegalArgumentException if the key is not of the type {@code algorithm} verifies
     */
    JwsVerificationKey {
        Objects.requireNonNull(publicKey, "publicKey must not be null");
        Objects.requireNonNull(algorithm, "algorithm must not be null");
        switch (algorithm) {
            case RS256 -> {
                if (!(publicKey instanceof RSAPublicKey)) {
                    throw new IllegalArgumentException("an RS256 key must be an RSA public key");
                }
            }
            case ED25519 -> {
                if (!(publicKey instanceof EdECPublicKey edKey)
                        || !ED25519_CURVE.equals(edKey.getParams().getName())) {
                    throw new IllegalArgumentException("an Ed25519 key must be an Ed25519 public key");
                }
            }
        }
    }

    /**
     * An RS256 key.
     *
     * @param key the RSA public key
     * @return the key pinned to RS256
     */
    /* default */ static JwsVerificationKey rs256(RSAPublicKey key) {
        return new JwsVerificationKey(key, PinnedJwsAlgorithm.RS256);
    }

    /**
     * An Ed25519 key.
     *
     * @param key the Ed25519 public key
     * @return the key pinned to EdDSA over Ed25519
     * @throws IllegalArgumentException if {@code key} is not an Ed25519 public key
     */
    /* default */ static JwsVerificationKey ed25519(PublicKey key) {
        return new JwsVerificationKey(key, PinnedJwsAlgorithm.ED25519);
    }

    /**
     * Pins every RSA key of a map to RS256.
     *
     * @param keysByKid {@code kid} to RSA public key
     * @return an immutable {@code kid} to RS256 key map
     */
    /* default */ static Map<String, JwsVerificationKey> rs256All(Map<String, RSAPublicKey> keysByKid) {
        Objects.requireNonNull(keysByKid, "keysByKid must not be null");
        return keysByKid.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> rs256(e.getValue())));
    }

    /**
     * A verifier for this key, fresh per call because a JCA {@code Signature} is not thread-safe.
     *
     * @return a verifier that accepts only this key's algorithm
     */
    /* default */ JWSVerifier verifier() {
        return switch (algorithm) {
            case RS256 -> new RSASSAVerifier((RSAPublicKey) publicKey);
            case ED25519 -> new CommunityEd25519JwsVerifier(publicKey);
        };
    }
}
