/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.SignedJWT;
import eu.exeris.kernel.community.testkit.security.TestJwt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CommunityEd25519JwsVerifier — JDK Ed25519 behind the Nimbus verifier seam")
class CommunityEd25519JwsVerifierTest {

    private final CommunityEd25519JwsVerifier verifier = new CommunityEd25519JwsVerifier(TestJwt.testEd25519PublicKey());

    private static SignedJWT edToken(String headerAlgorithm) throws Exception {
        var builder = TestJwt.builder().signedWithEd25519().kid(TestJwt.TEST_ED25519_KID);
        if (headerAlgorithm != null) {
            builder.headerAlgorithm(headerAlgorithm);
        }
        return SignedJWT.parse(builder.serialize());
    }

    @Test
    @DisplayName("verifies a valid EdDSA signature")
    void validSignature() throws Exception {
        SignedJWT jwt = edToken(null);
        assertThat(verifier.verify(jwt.getHeader(), jwt.getSigningInput(), jwt.getSignature())).isTrue();
    }

    @Test
    @DisplayName("a header naming RS256 is refused, whatever the signature")
    void headerNamingRs256Throws() throws Exception {
        SignedJWT jwt = edToken("RS256");
        assertThatThrownBy(() -> verifier.verify(jwt.getHeader(), jwt.getSigningInput(), jwt.getSignature()))
                .isInstanceOf(JOSEException.class);
    }

    @Test
    @DisplayName("a signature that is not 64 bytes does not verify")
    void shortSignatureFails() throws Exception {
        SignedJWT jwt = edToken(null);
        byte[] shortened = Arrays.copyOf(jwt.getSignature().decode(), 63);
        assertThat(verifier.verify(jwt.getHeader(), jwt.getSigningInput(), Base64URL.encode(shortened))).isFalse();
    }

    @Test
    @DisplayName("a critical header parameter makes verification fail")
    void criticalHeaderFails() throws Exception {
        SignedJWT jwt = SignedJWT.parse(TestJwt.builder().signedWithEd25519().kid(TestJwt.TEST_ED25519_KID)
                .criticalHeaderParam("ext", "1").serialize());
        assertThat(jwt.getHeader().getCriticalParams()).containsExactly("ext");
        assertThat(verifier.verify(jwt.getHeader(), jwt.getSigningInput(), jwt.getSignature())).isFalse();
    }

    @Test
    @DisplayName("a signature by another Ed25519 key does not verify")
    void otherKeyFails() throws Exception {
        var other = new CommunityEd25519JwsVerifier(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic());
        SignedJWT jwt = edToken(null);
        assertThat(other.verify(jwt.getHeader(), jwt.getSigningInput(), jwt.getSignature())).isFalse();
    }

    @Test
    @DisplayName("declares EdDSA and Ed25519 as its supported algorithms")
    void supportedAlgorithms() {
        assertThat(verifier.supportedJWSAlgorithms()).isEqualTo(Set.of(JWSAlgorithm.EdDSA, JWSAlgorithm.Ed25519));
        assertThat(verifier.getJCAContext()).isNotNull();
        assertThat(new JWSHeader(JWSAlgorithm.EdDSA).getAlgorithm()).isEqualTo(JWSAlgorithm.EdDSA);
    }
}
