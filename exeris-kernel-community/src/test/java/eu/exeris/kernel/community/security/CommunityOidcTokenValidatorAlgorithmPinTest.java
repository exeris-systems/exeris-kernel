/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.security;

import eu.exeris.kernel.community.testkit.security.TestJwt;
import eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.security.identity.VerifiedClaims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CommunityOidcTokenValidator — the key's algorithm, not the header's")
class CommunityOidcTokenValidatorAlgorithmPinTest {

    private final CommunityOidcTokenValidator validator = new CommunityOidcTokenValidator(
            new StaticJwksKeyResolver(Map.of(
                    TestJwt.TEST_KID, JwsVerificationKey.rs256(TestJwt.testPublicKey()),
                    TestJwt.TEST_ED25519_KID, JwsVerificationKey.ed25519(TestJwt.testEd25519PublicKey()))),
            TestJwt.EXPECTED_ISSUER, TestJwt.EXPECTED_AUDIENCE, Clock.systemUTC());

    private static TestJwt.Builder ed() {
        return TestJwt.builder().signedWithEd25519().kid(TestJwt.TEST_ED25519_KID);
    }

    private void assertSignatureInvalid(TestJwt.Builder token) {
        try (LoanedBuffer buffer = token.toBuffer()) {
            assertThatThrownBy(() -> validator.validate(buffer))
                    .isInstanceOfSatisfying(SecurityAuthenticationException.class,
                            ex -> assertThat(ex.rawArgs()[1]).isEqualTo("signature-invalid"));
        }
    }

    @Test
    @DisplayName("an EdDSA token under its Ed25519 key is accepted")
    void edDsaAccepted() {
        try (LoanedBuffer buffer = ed().toBuffer()) {
            VerifiedClaims claims = validator.validate(buffer);
            assertThat(claims.subject()).isEqualTo(TestJwt.TEST_SUBJECT);
        }
    }

    @Test
    @DisplayName("an RS256 token under its RSA key is accepted")
    void rs256Accepted() {
        try (LoanedBuffer buffer = TestJwt.builder().toBuffer()) {
            assertThat(validator.validate(buffer).subject()).isEqualTo(TestJwt.TEST_SUBJECT);
        }
    }

    @Test
    @DisplayName("a header naming Ed25519 is accepted as a synonym of EdDSA")
    void ed25519HeaderAccepted() {
        try (LoanedBuffer buffer = ed().headerAlgorithm("Ed25519").toBuffer()) {
            assertThat(validator.validate(buffer).subject()).isEqualTo(TestJwt.TEST_SUBJECT);
        }
    }

    @Test
    @DisplayName("C1: an EdDSA token under an RSA key's kid is denied")
    void edDsaUnderRsaKid() {
        assertSignatureInvalid(ed().kid(TestJwt.TEST_KID));
    }

    @Test
    @DisplayName("C2: an RS256 token under an Ed25519 key's kid is denied")
    void rs256UnderEdDsaKid() {
        assertSignatureInvalid(TestJwt.builder().kid(TestJwt.TEST_ED25519_KID));
    }

    @Test
    @DisplayName("H1: an Ed25519-signed token whose header names RS256 is denied")
    void headerNamingRs256OverEd25519Key() {
        assertSignatureInvalid(ed().headerAlgorithm("RS256"));
    }

    @Test
    @DisplayName("H1: an Ed25519-signed token whose header names Ed448 is denied")
    void headerNamingEd448OverEd25519Key() {
        assertSignatureInvalid(ed().headerAlgorithm("Ed448"));
    }

    @Test
    @DisplayName("H2: an RS512 token signed by the RSA key is denied under its own kid")
    void rs512UnderPinnedRs256Key() {
        assertSignatureInvalid(TestJwt.builder().headerAlgorithm("RS512"));
    }

    @Test
    @DisplayName("an HS256 token keyed on the Ed25519 public key is denied")
    void hmacOverEd25519Key() {
        assertSignatureInvalid(TestJwt.builder().kid(TestJwt.TEST_ED25519_KID).hmacConfusionOverEd25519Key());
    }

    @Test
    @DisplayName("a tampered EdDSA signature is denied")
    void tamperedEdDsa() {
        assertSignatureInvalid(ed().tamperedSignature());
    }

    @Test
    @DisplayName("a token signed by an impostor Ed25519 key is denied")
    void impostorEd25519Key() throws Exception {
        var impostor = new JwsVerificationKey(
                java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic(),
                PinnedJwsAlgorithm.ED25519);
        var other = new CommunityOidcTokenValidator(
                new StaticJwksKeyResolver(Map.of(TestJwt.TEST_ED25519_KID, impostor)),
                TestJwt.EXPECTED_ISSUER, TestJwt.EXPECTED_AUDIENCE, Clock.systemUTC());
        try (LoanedBuffer buffer = ed().toBuffer()) {
            assertThatThrownBy(() -> other.validate(buffer))
                    .isInstanceOfSatisfying(SecurityAuthenticationException.class,
                            ex -> assertThat(ex.rawArgs()[1]).isEqualTo("signature-invalid"));
        }
    }

    @Test
    @DisplayName("a critical header parameter on an EdDSA token is denied")
    void criticalHeaderOnEdDsa() {
        assertSignatureInvalid(ed().criticalHeaderParam("ext", "1"));
    }

    @Test
    @DisplayName("a critical header parameter on an RS256 token is denied")
    void criticalHeaderOnRs256() {
        assertSignatureInvalid(TestJwt.builder().criticalHeaderParam("ext", "1"));
    }
}
