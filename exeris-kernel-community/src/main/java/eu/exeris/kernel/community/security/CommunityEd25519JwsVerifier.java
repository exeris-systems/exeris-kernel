/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.jca.JCAContext;
import com.nimbusds.jose.util.Base64URL;

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Objects;
import java.util.Set;

/**
 * {@link JWSVerifier} for EdDSA over Ed25519 that verifies with the JDK's default-provider Ed25519,
 * as Core's licence verifier does, so no optional Nimbus crypto dependency is needed.
 *
 * <p>Instances are cheap and hold no {@link Signature}; each {@link #verify} builds its own, so an
 * instance is safe to share.
 *
 * @since 0.13
 */
final class CommunityEd25519JwsVerifier implements JWSVerifier {

    private static final int SIGNATURE_LENGTH = 64;
    private static final Set<JWSAlgorithm> SUPPORTED = Set.of(JWSAlgorithm.EdDSA, JWSAlgorithm.Ed25519);

    private final PublicKey publicKey;
    private final JCAContext jcaContext = new JCAContext();

    /**
     * Creates a verifier for one Ed25519 key.
     *
     * @param publicKey the Ed25519 public key signatures are checked against
     */
    /* default */ CommunityEd25519JwsVerifier(PublicKey publicKey) {
        this.publicKey = Objects.requireNonNull(publicKey, "publicKey must not be null");
    }

    @Override
    public Set<JWSAlgorithm> supportedJWSAlgorithms() {
        return SUPPORTED;
    }

    @Override
    public JCAContext getJCAContext() {
        return jcaContext;
    }

    /**
     * {@inheritDoc}
     *
     * @throws JOSEException if the header names an algorithm this verifier does not support
     */
    @Override
    public boolean verify(JWSHeader header, byte[] signingInput, Base64URL signature) throws JOSEException {
        if (!SUPPORTED.contains(header.getAlgorithm())) {
            throw new JOSEException("Unsupported JWS algorithm " + header.getAlgorithm()
                    + ", must be EdDSA or Ed25519");
        }
        if (header.getCriticalParams() != null && !header.getCriticalParams().isEmpty()) {
            return false;
        }
        byte[] signatureBytes = signature.decode();
        if (signatureBytes.length != SIGNATURE_LENGTH) {
            return false;
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(signingInput);
            return verifier.verify(signatureBytes);
        } catch (GeneralSecurityException _) {
            return false;
        }
    }
}
