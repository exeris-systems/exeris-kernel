/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.KeyType;
import eu.exeris.kernel.community.testkit.security.TestJwt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.security.PublicKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PinnedJwsAlgorithm / JwsVerificationKey — a key verifies one algorithm")
class PinnedJwsAlgorithmTest {

    @Test
    @DisplayName("RS256 admits exactly RS256")
    void rs256AdmitsOnlyRs256() {
        assertThat(PinnedJwsAlgorithm.RS256.admits(JWSAlgorithm.RS256)).isTrue();
        for (JWSAlgorithm other : new JWSAlgorithm[] {JWSAlgorithm.RS384, JWSAlgorithm.RS512, JWSAlgorithm.PS256,
                JWSAlgorithm.HS256, JWSAlgorithm.EdDSA, JWSAlgorithm.Ed25519, new JWSAlgorithm("none")}) {
            assertThat(PinnedJwsAlgorithm.RS256.admits(other)).as(other.getName()).isFalse();
        }
        assertThat(PinnedJwsAlgorithm.RS256.admits(null)).isFalse();
    }

    @Test
    @DisplayName("ED25519 admits EdDSA and Ed25519 only")
    void ed25519AdmitsEdDsaSpellings() {
        assertThat(PinnedJwsAlgorithm.ED25519.admits(JWSAlgorithm.EdDSA)).isTrue();
        assertThat(PinnedJwsAlgorithm.ED25519.admits(JWSAlgorithm.Ed25519)).isTrue();
        for (JWSAlgorithm other : new JWSAlgorithm[] {JWSAlgorithm.Ed448, JWSAlgorithm.RS256, JWSAlgorithm.ES256,
                JWSAlgorithm.HS256}) {
            assertThat(PinnedJwsAlgorithm.ED25519.admits(other)).as(other.getName()).isFalse();
        }
        assertThat(PinnedJwsAlgorithm.ED25519.admits(null)).isFalse();
    }

    @Test
    @DisplayName("fromJwk pins RSA to RS256 and OKP Ed25519 to EdDSA, and nothing else")
    void fromJwkTable() {
        assertThat(PinnedJwsAlgorithm.fromJwk(KeyType.RSA, null, null)).isEqualTo(PinnedJwsAlgorithm.RS256);
        assertThat(PinnedJwsAlgorithm.fromJwk(KeyType.RSA, null, JWSAlgorithm.RS256))
                .isEqualTo(PinnedJwsAlgorithm.RS256);
        for (JWSAlgorithm alg : new JWSAlgorithm[] {JWSAlgorithm.RS384, JWSAlgorithm.RS512, JWSAlgorithm.PS256,
                JWSAlgorithm.EdDSA}) {
            assertThat(PinnedJwsAlgorithm.fromJwk(KeyType.RSA, null, alg)).as("RSA " + alg).isNull();
        }
        assertThat(PinnedJwsAlgorithm.fromJwk(KeyType.OKP, Curve.Ed25519, null)).isEqualTo(PinnedJwsAlgorithm.ED25519);
        assertThat(PinnedJwsAlgorithm.fromJwk(KeyType.OKP, Curve.Ed25519, JWSAlgorithm.EdDSA))
                .isEqualTo(PinnedJwsAlgorithm.ED25519);
        assertThat(PinnedJwsAlgorithm.fromJwk(KeyType.OKP, Curve.Ed25519, JWSAlgorithm.Ed25519))
                .isEqualTo(PinnedJwsAlgorithm.ED25519);
        assertThat(PinnedJwsAlgorithm.fromJwk(KeyType.OKP, Curve.Ed25519, JWSAlgorithm.RS256)).isNull();
        assertThat(PinnedJwsAlgorithm.fromJwk(KeyType.OKP, Curve.Ed25519, JWSAlgorithm.ES256)).isNull();
        assertThat(PinnedJwsAlgorithm.fromJwk(KeyType.OKP, Curve.X25519, null)).isNull();
        assertThat(PinnedJwsAlgorithm.fromJwk(KeyType.OKP, Curve.X448, null)).isNull();
        assertThat(PinnedJwsAlgorithm.fromJwk(KeyType.OKP, Curve.Ed448, null)).isNull();
        assertThat(PinnedJwsAlgorithm.fromJwk(KeyType.OKP, null, null)).isNull();
        assertThat(PinnedJwsAlgorithm.fromJwk(KeyType.EC, Curve.P_256, null)).isNull();
        assertThat(PinnedJwsAlgorithm.fromJwk(KeyType.OCT, null, JWSAlgorithm.HS256)).isNull();
    }

    @Test
    @DisplayName("ed25519() rejects an Ed448 key and an RSA key")
    void ed25519RejectsOtherKeys() throws Exception {
        PublicKey ed448 = KeyPairGenerator.getInstance("Ed448").generateKeyPair().getPublic();
        assertThatThrownBy(() -> JwsVerificationKey.ed25519(ed448)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JwsVerificationKey.ed25519(TestJwt.testPublicKey()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rs256() rejects an Ed25519 key")
    void rs256RejectsEd25519Key() {
        assertThatThrownBy(() -> new JwsVerificationKey(TestJwt.testEd25519PublicKey(), PinnedJwsAlgorithm.RS256))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("two keys built from the same raw x are equal")
    void sameRawKeyIsEqual() throws Exception {
        JwsVerificationKey a = JwsVerificationKey.ed25519(Ed25519PublicKeys.fromRaw(TestJwt.testEd25519PublicKeyRaw()));
        JwsVerificationKey b = JwsVerificationKey.ed25519(Ed25519PublicKeys.fromRaw(TestJwt.testEd25519PublicKeyRaw()));
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }

    @Test
    @DisplayName("fromRaw rejects a key that is not 32 bytes")
    void fromRawRejectsWrongLength() {
        assertThatThrownBy(() -> Ed25519PublicKeys.fromRaw(new byte[31])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ed25519PublicKeys.fromRaw(new byte[33])).isInstanceOf(IllegalArgumentException.class);
    }
}
