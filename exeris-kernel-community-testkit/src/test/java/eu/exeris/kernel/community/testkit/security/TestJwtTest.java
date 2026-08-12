/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.testkit.security;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the attack-shape builders produce the token they advertise.
 *
 * <p>These are the fixtures every negative security test in the reactor is built on. A builder that
 * quietly produced a <em>valid</em> token when asked for {@code algNone()} or {@code expired()} would
 * not fail anything — it would make each of those tests pass while proving nothing at all. So each
 * case below is paired against the plain, genuinely-valid token: the assertion is always
 * "this differs from a good token in exactly the advertised way".
 */
@DisplayName("TestJwt")
class TestJwtTest {

    @Nested
    @DisplayName("The baseline token is actually valid")
    class Baseline {

        @Test
        @DisplayName("verifies against the published public key and carries the expected claims")
        void plainTokenVerifies() throws Exception {
            SignedJWT jwt = SignedJWT.parse(TestJwt.builder().serialize());

            assertThat(jwt.verify(new RSASSAVerifier(TestJwt.testPublicKey())))
                    .as("every negative case below is only meaningful because this one is true")
                    .isTrue();
            assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
            assertThat(jwt.getHeader().getKeyID()).isEqualTo(TestJwt.TEST_KID);
            assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo(TestJwt.EXPECTED_ISSUER);
            assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly(TestJwt.EXPECTED_AUDIENCE);
            assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(TestJwt.TEST_SUBJECT);
            assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant())
                    .isAfter(Instant.now());
        }

        @Test
        @DisplayName("custom claims reach the payload")
        void customClaimsArePresent() throws Exception {
            SignedJWT jwt = SignedJWT.parse(TestJwt.builder()
                    .claim("scope", "security:read")
                    .serialize());

            assertThat(jwt.getJWTClaimsSet().getStringClaim("scope")).isEqualTo("security:read");
        }
    }

    @Nested
    @DisplayName("Attack shapes differ from the baseline in the advertised way")
    class AttackShapes {

        @Test
        @DisplayName("expired() puts exp in the past and leaves the signature intact")
        void expiredTokenIsExpiredButStillSigned() throws Exception {
            SignedJWT jwt = SignedJWT.parse(TestJwt.builder().expired().serialize());

            assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant()).isBefore(Instant.now());
            assertThat(jwt.verify(new RSASSAVerifier(TestJwt.testPublicKey())))
                    .as("expiry must be the only defect — a validator that rejects on the signature "
                            + "would pass the test for the wrong reason")
                    .isTrue();
        }

        @Test
        @DisplayName("tamperedSignature() breaks verification and nothing else")
        void tamperedSignatureFailsVerification() throws Exception {
            SignedJWT jwt = SignedJWT.parse(TestJwt.builder().tamperedSignature().serialize());

            assertThat(jwt.verify(new RSASSAVerifier(TestJwt.testPublicKey()))).isFalse();
            assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant()).isAfter(Instant.now());
        }

        @Test
        @DisplayName("algNone() emits an unsecured header with an empty signature segment")
        void algNoneIsUnsecured() {
            String[] segments = TestJwt.builder().algNone().serialize().split("\\.", -1);

            assertThat(segments).hasSize(3);
            assertThat(decode(segments[0])).contains("\"alg\":\"none\"");
            assertThat(segments[2])
                    .as("a non-empty signature segment would make this a signed token wearing an "
                            + "unsecured header, which is not the attack being modelled")
                    .isEmpty();
        }

        @Test
        @DisplayName("hmacConfusion() declares HS256, so an RSA verifier cannot be the check")
        void hmacConfusionDeclaresSymmetricAlg() {
            String[] segments = TestJwt.builder().hmacConfusion().serialize().split("\\.", -1);

            assertThat(decode(segments[0])).contains("\"alg\":\"HS256\"");
            assertThat(segments[2]).isNotEmpty();
        }

        @Test
        @DisplayName("noKid() omits kid; kid() sets the one asked for")
        void kidIsControllable() {
            String withoutKid = decode(TestJwt.builder().noKid().serialize().split("\\.", -1)[0]);
            String withUnknownKid =
                    decode(TestJwt.builder().kid(TestJwt.UNKNOWN_KID).serialize().split("\\.", -1)[0]);

            assertThat(withoutKid).doesNotContain("kid");
            assertThat(withUnknownKid).contains(TestJwt.UNKNOWN_KID);
        }
    }

    @Nested
    @DisplayName("Buffer handoff")
    class Buffers {

        @Test
        @DisplayName("toBuffer() carries exactly the serialized token bytes")
        void bufferHoldsTheToken() {
            String compact = TestJwt.builder().serialize();

            try (LoanedBuffer buffer = TestJwt.bufferOf(compact)) {
                assertThat(buffer.size()).isEqualTo(compact.getBytes(StandardCharsets.UTF_8).length);
                assertThat(readUtf8(buffer)).isEqualTo(compact);
            }
        }

        @Test
        @DisplayName("the key set publishes the test key under its kid")
        void keySetPublishesTestKey() {
            assertThat(TestJwt.keySet())
                    .containsOnlyKeys(TestJwt.TEST_KID)
                    .containsEntry(TestJwt.TEST_KID, TestJwt.testPublicKey());
        }
    }

    private static String decode(String base64UrlSegment) {
        return new String(Base64.getUrlDecoder().decode(base64UrlSegment), StandardCharsets.UTF_8);
    }

    private static String readUtf8(LoanedBuffer buffer) {
        MemorySegment segment = buffer.segment();
        byte[] bytes = new byte[(int) buffer.size()];
        MemorySegment.copy(segment, java.lang.foreign.ValueLayout.JAVA_BYTE, 0L, bytes, 0, bytes.length);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
