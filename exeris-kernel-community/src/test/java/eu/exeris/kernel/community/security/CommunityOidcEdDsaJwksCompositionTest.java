/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.security;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.util.Base64URL;
import eu.exeris.kernel.community.testkit.security.TestJwt;
import eu.exeris.kernel.spi.exceptions.security.SecurityAuthenticationException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.security.identity.KeyRotationPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Community OIDC — a JWKS publishing RSA and Ed25519 keys, end to end")
class CommunityOidcEdDsaJwksCompositionTest {

    private static final long TOKEN_LIFETIME_SECONDS = 86_400L;

    private static String rsaJwk() {
        return new RSAKey.Builder(TestJwt.testPublicKey()).keyID(TestJwt.TEST_KID).build().toJSONString();
    }

    private static String ed25519Jwk() {
        return new OctetKeyPair.Builder(Curve.Ed25519, Base64URL.encode(TestJwt.testEd25519PublicKeyRaw()))
                .keyID(TestJwt.TEST_ED25519_KID).build().toJSONString();
    }

    private static String jwks(String... entries) {
        return "{\"keys\":[" + String.join(",", entries) + "]}";
    }

    private static TestJwt.Builder rsaToken() {
        return TestJwt.builder().expiresInSeconds(TOKEN_LIFETIME_SECONDS);
    }

    private static TestJwt.Builder edToken() {
        return TestJwt.builder().signedWithEd25519().kid(TestJwt.TEST_ED25519_KID)
                .expiresInSeconds(TOKEN_LIFETIME_SECONDS);
    }

    private static final class Fixture {
        final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        String document;
        final CommunityOidcIdentityProvider provider;

        Fixture(String initialDocument) {
            this.document = initialDocument;
            var source = new CommunityJwksHttpKeySetSource(() -> document);
            var keySet = new CommunityRotatingKeySet(Map.of(), source, KeyRotationPolicy.defaults(), clock);
            this.provider = new CommunityOidcIdentityProvider(
                    keySet, TestJwt.EXPECTED_ISSUER, TestJwt.EXPECTED_AUDIENCE);
        }

        void assertAuthenticates(TestJwt.Builder token) {
            try (LoanedBuffer buffer = token.toBuffer()) {
                assertThat(provider.authenticate(buffer)).isNotNull();
            }
        }

        void assertDenied(TestJwt.Builder token) {
            try (LoanedBuffer buffer = token.toBuffer()) {
                assertThatThrownBy(() -> provider.authenticate(buffer))
                        .isInstanceOf(SecurityAuthenticationException.class);
            }
        }
    }

    @Test
    @DisplayName("EdDSA and RS256 tokens authenticate against one fetched JWKS")
    void bothAlgorithmsAuthenticate() {
        Fixture fixture = new Fixture(jwks(rsaJwk(), ed25519Jwk()));

        fixture.assertAuthenticates(edToken());
        fixture.assertAuthenticates(rsaToken());
    }

    @Test
    @DisplayName("an EdDSA token under the RSA kid and an RS256 token under the Ed25519 kid are denied")
    void crossedKidsDenied() {
        Fixture fixture = new Fixture(jwks(rsaJwk(), ed25519Jwk()));

        fixture.assertDenied(edToken().kid(TestJwt.TEST_KID));
        fixture.assertDenied(rsaToken().kid(TestJwt.TEST_ED25519_KID));
    }

    @Test
    @DisplayName("rotating from an RSA-only JWKS to an Ed25519-only one keeps each algorithm under its own kid")
    void rotationKeepsPinsPerGeneration() {
        Fixture fixture = new Fixture(jwks(rsaJwk()));
        fixture.assertAuthenticates(rsaToken());

        fixture.document = jwks(ed25519Jwk());
        fixture.clock.advance(Duration.ofMinutes(61L));

        // The RSA key lives only in the retiring generation, still inside its overlap window.
        fixture.assertAuthenticates(rsaToken());
        fixture.assertAuthenticates(edToken());
        fixture.assertDenied(edToken().kid(TestJwt.TEST_KID));
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant start) {
            this.instant = start;
        }

        private void advance(Duration amount) {
            this.instant = this.instant.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
