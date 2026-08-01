/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.storage;

import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SigV4 signing, checked where a live store cannot check it.
 *
 * <p>Whether a signature <em>verifies</em> is settled by {@code CommunityS3BlobStorageTckIT} against
 * MinIO — no assertion written here could establish that, and one that tried would only restate the
 * implementation. What is checked here is what the integration gate cannot see: that the digest matches
 * published vectors, that every input actually reaches the signature, and that the secret key never
 * appears in anything the driver emits.
 *
 * @since 0.11.0
 */
@DisplayName("Community S3: SigV4 signing")
class CommunityS3SignerTest {

    /** RFC 6234 / FIPS 180-4 published vector: SHA-256 of the empty string. */
    private static final String EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    /** RFC 6234 / FIPS 180-4 published vector: SHA-256 of {@code "abc"}. */
    private static final String ABC_SHA256 =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    private static final String SECRET = "secret-that-must-never-be-emitted";
    private static final String PATH = "/bucket/t-6162/documents/object.bin";
    private static final Instant FIXED = Instant.parse("2026-08-01T12:00:00Z");

    private static CommunityS3Settings settings(String secret) {
        return new CommunityS3Settings("minio.internal", 9000, "bucket", "access-key", secret,
                "us-east-1", CommunityS3Settings.DEFAULT_MAX_OBJECT_BYTES);
    }

    private static CommunityS3Signer signerAt(Instant instant, String secret) {
        return new CommunityS3Signer(settings(secret), Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static String authorizationOf(List<HttpHeader> headers) {
        return valueOf(headers, "Authorization");
    }

    private static String valueOf(List<HttpHeader> headers, String name) {
        Optional<HttpHeader> found = headers.stream()
                .filter(header -> header.nameEqualsIgnoreCase(name))
                .findFirst();
        assertThat(found).as("header %s", name).isPresent();
        return found.get().value();
    }

    private static String signatureOf(String authorization) {
        int marker = authorization.indexOf("Signature=");
        return authorization.substring(marker + "Signature=".length());
    }

    @Nested
    @DisplayName("Payload digest")
    class PayloadDigest {

        @Test
        @DisplayName("matches the published SHA-256 vectors")
        void matchesPublishedVectors() {
            assertThat(CommunityS3Signer.emptyPayloadHash()).isEqualTo(EMPTY_SHA256);

            try (Arena arena = Arena.ofConfined()) {
                byte[] payload = "abc".getBytes(StandardCharsets.UTF_8);
                MemorySegment segment = arena.allocate(payload.length);
                MemorySegment.copy(MemorySegment.ofArray(payload), 0L, segment, 0L, payload.length);
                assertThat(CommunityS3Signer.hashPayload(segment, payload.length))
                        .isEqualTo(ABC_SHA256);
            }
        }

        @Test
        @DisplayName("hashes only the declared prefix, not the whole allocated segment")
        void hashesDeclaredPrefixOnly() {
            try (Arena arena = Arena.ofConfined()) {
                byte[] payload = "abc".getBytes(StandardCharsets.UTF_8);
                // Oversized on purpose: a pool hands back more than was asked for, and hashing the
                // slack would sign bytes the wire never carries.
                MemorySegment segment = arena.allocate(64);
                MemorySegment.copy(MemorySegment.ofArray(payload), 0L, segment, 0L, payload.length);
                assertThat(CommunityS3Signer.hashPayload(segment, payload.length))
                        .isEqualTo(ABC_SHA256);
            }
        }
    }

    @Nested
    @DisplayName("Every input reaches the signature")
    class InputsReachSignature {

        @Test
        @DisplayName("the signature is a lowercase 64-character hex digest")
        void signatureShape() {
            String signature = signatureOf(authorizationOf(
                    signerAt(FIXED, SECRET).sign(HttpMethod.GET, PATH, EMPTY_SHA256)));

            assertThat(signature).hasSize(64).matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("changing the secret, the clock, the method, the path or the payload changes it")
        void everyInputMatters() {
            String base = signatureOf(authorizationOf(
                    signerAt(FIXED, SECRET).sign(HttpMethod.GET, PATH, EMPTY_SHA256)));

            assertThat(signatureOf(authorizationOf(
                    signerAt(FIXED, "other-secret").sign(HttpMethod.GET, PATH, EMPTY_SHA256))))
                    .as("an input that does not change the signature is an input the store is not "
                            + "checking")
                    .isNotEqualTo(base);
            assertThat(signatureOf(authorizationOf(
                    signerAt(FIXED.plusSeconds(1), SECRET).sign(HttpMethod.GET, PATH, EMPTY_SHA256))))
                    .isNotEqualTo(base);
            assertThat(signatureOf(authorizationOf(
                    signerAt(FIXED, SECRET).sign(HttpMethod.PUT, PATH, EMPTY_SHA256))))
                    .isNotEqualTo(base);
            assertThat(signatureOf(authorizationOf(
                    signerAt(FIXED, SECRET).sign(HttpMethod.GET, PATH + "x", EMPTY_SHA256))))
                    .isNotEqualTo(base);
            assertThat(signatureOf(authorizationOf(
                    signerAt(FIXED, SECRET).sign(HttpMethod.GET, PATH, ABC_SHA256))))
                    .isNotEqualTo(base);
        }

        @Test
        @DisplayName("the credential scope names the date, region and service the store expects")
        void credentialScope() {
            List<HttpHeader> headers = signerAt(FIXED, SECRET).sign(HttpMethod.GET, PATH, EMPTY_SHA256);

            assertThat(authorizationOf(headers))
                    .startsWith("AWS4-HMAC-SHA256 Credential=access-key/20260801/us-east-1/s3/"
                            + "aws4_request")
                    .contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date");
            assertThat(valueOf(headers, "x-amz-date")).isEqualTo("20260801T120000Z");
            assertThat(valueOf(headers, "x-amz-content-sha256")).isEqualTo(EMPTY_SHA256);
            assertThat(valueOf(headers, "Host"))
                    .as("the Host header is signed, so it must be sent rather than left to the "
                            + "request encoder's own idea of the authority")
                    .isEqualTo("minio.internal:9000");
        }
    }

    @Nested
    @DisplayName("Presigned URLs")
    class Presigned {

        @Test
        @DisplayName("carry every query parameter the store needs to verify them")
        void carriesRequiredParameters() {
            String url = signerAt(FIXED, SECRET).presign(HttpMethod.GET, PATH, Duration.ofMinutes(5));

            assertThat(url)
                    .startsWith("http://minio.internal:9000" + PATH + "?")
                    .contains("X-Amz-Algorithm=AWS4-HMAC-SHA256")
                    .contains("X-Amz-Credential=access-key%2F20260801%2Fus-east-1%2Fs3%2Faws4_request")
                    .contains("X-Amz-Date=20260801T120000Z")
                    .contains("X-Amz-Expires=300")
                    .contains("X-Amz-SignedHeaders=host")
                    .contains("X-Amz-Signature=");
        }

        @Test
        @DisplayName("a shorter grant is a different URL, so the expiry is genuinely signed")
        void expirySigned() {
            CommunityS3Signer signer = signerAt(FIXED, SECRET);

            assertThat(signer.presign(HttpMethod.GET, PATH, Duration.ofMinutes(1)))
                    .isNotEqualTo(signer.presign(HttpMethod.GET, PATH, Duration.ofMinutes(5)));
        }
    }

    @Nested
    @DisplayName("Secret safety")
    class SecretSafety {

        @Test
        @DisplayName("the secret key appears in no header and in no minted URL")
        void secretNeverEmitted() {
            CommunityS3Signer signer = signerAt(FIXED, SECRET);

            assertThat(signer.sign(HttpMethod.PUT, PATH, EMPTY_SHA256))
                    .as("a credential that reaches a header reaches a proxy log")
                    .noneSatisfy(header -> assertThat(header.value()).contains(SECRET));
            assertThat(signer.presign(HttpMethod.GET, PATH, Duration.ofMinutes(5)))
                    .doesNotContain(SECRET);
        }
    }
}
