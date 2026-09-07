/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.testkit.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * JWT test fixture for security contract tests.
 *
 * <p>Generates RSA-2048 signed JWTs for
 * {@link eu.exeris.kernel.tck.contract.security.AbstractSecurityProviderTck} binding tests and
 * integration tests. All generated tokens are signed with the {@link #TEST_KEY_PAIR} held by this class.
 *
 * <p>Usage:
 * <pre>{@code
 * TestJwt.Builder builder = TestJwt.builder()
 *     .kid(TestJwt.TEST_KID)
 *     .issuer("https://auth.example.com")
 *     .audience("exeris-kernel")
 *     .subject("00000000-0000-7000-8000-000000000001")
 *     .expiresInSeconds(300);
 * LoanedBuffer buffer = builder.toBuffer();
 * }</pre>
 *
 * @since 0.5
 */
@SuppressWarnings({"PMD.TestClassWithoutTestCases", "PMD.UnitTestShouldUseTestAnnotation"})
public final class TestJwt {

    /** The stable test kid used for the test key pair. */
    public static final String TEST_KID = "test-key-1";

    /** An unknown kid that is NOT in the test key set. */
    public static final String UNKNOWN_KID = "unknown-key-99";

    public static final String EXPECTED_ISSUER = "https://auth.example.com";

    public static final String EXPECTED_AUDIENCE = "exeris-kernel";

    public static final String TEST_SUBJECT = "00000000-0000-7000-8000-000000000001";

    /** The test RSA key pair generated once for the lifetime of the JVM. */
    public static final KeyPair TEST_KEY_PAIR;

    static {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            TEST_KEY_PAIR = kpg.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private TestJwt() {
        // Utility class.
    }

    /** Returns the RSA public key from the test key pair. */
    public static RSAPublicKey testPublicKey() {
        return (RSAPublicKey) TEST_KEY_PAIR.getPublic();
    }

    public static Map<String, RSAPublicKey> keySet() {
        return Map.of(TEST_KID, testPublicKey());
    }

    public static LoanedBuffer bufferOf(String compactJwt) {
        return new HeapLoanedBuffer(Objects.requireNonNull(compactJwt, "compactJwt must not be null")
                .getBytes(StandardCharsets.UTF_8));
    }

    /** Creates a new builder with sensible defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for test JWTs.
     */
    // Fluent test-fixture builder: many small mutator methods (highest individual complexity 7)
    // sum to a high class-total cyclomatic score — inherent to the builder shape, not a
    // maintainability concern. Suppressed for the same reason as TooManyMethods.
    @SuppressWarnings({"PMD.TooManyMethods", "PMD.CyclomaticComplexity"})
    public static final class Builder {

        private static final int COMPACT_JWT_SEGMENT_COUNT = 3;

        private String kid = TEST_KID;
        private String issuer = EXPECTED_ISSUER;
        private String audience = EXPECTED_AUDIENCE;
        private String subject = TEST_SUBJECT;
        private long expiresInSeconds = 300L;
        private boolean expired;
        private boolean tamperSignature;
        private boolean omitKid;
        private boolean algNone;
        private boolean hmacConfusion;
        private final Map<String, String> customClaims = new LinkedHashMap<>();
        private final Map<String, Object> rawClaims = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder kid(String kid) {
            this.kid = kid;
            this.omitKid = false;
            return this;
        }

        public Builder noKid() {
            this.omitKid = true;
            return this;
        }

        public Builder issuer(String issuer) {
            this.issuer = issuer;
            return this;
        }

        public Builder audience(String audience) {
            this.audience = audience;
            return this;
        }

        public Builder subject(String subject) {
            this.subject = subject;
            return this;
        }

        public Builder expiresInSeconds(long seconds) {
            this.expiresInSeconds = seconds;
            this.expired = false;
            return this;
        }

        /** Produces a token whose {@code exp} is already in the past. */
        public Builder expired() {
            this.expired = true;
            return this;
        }

        /** Produces a token whose signature bytes are corrupted after signing. */
        public Builder tamperedSignature() {
            this.tamperSignature = true;
            return this;
        }

        /**
         * Produces an <b>unsecured</b> JWT ({@code "alg":"none"}, empty signature segment).
         *
         * <p>Models the classic {@code alg=none} downgrade attack: an attacker strips the
         * signature and declares the token unsigned, hoping the verifier skips signature
         * checking. A conforming kernel validator MUST reject it.
         *
         * <p>Serialized as a {@link com.nimbusds.jwt.PlainJWT} with a fixed {@code {"alg":"none"}}
         * header, so {@link #kid(String)} / {@link #noKid()} are <b>ignored</b> in this mode — the
         * unsecured header carries no {@code kid}.
         */
        public Builder algNone() {
            this.algNone = true;
            this.hmacConfusion = false;
            return this;
        }

        /**
         * Produces a token signed with <b>HS256</b> using the bytes of the RSA <i>public</i>
         * key as the HMAC secret.
         *
         * <p>Models the RS256-&gt;HS256 algorithm-confusion attack: the verifier is expected
         * to treat the published RSA public key as an asymmetric verification key, but an
         * attacker re-signs the token with HMAC keyed on those same (public) bytes, hoping
         * the verifier validates it as a symmetric MAC. The {@code kid} is retained so the
         * token reaches the algorithm gate. A conforming validator that pins RS256 MUST reject.
         */
        public Builder hmacConfusion() {
            this.hmacConfusion = true;
            this.algNone = false;
            return this;
        }

        /**
         * Adds a custom claim to the JWT payload.
         *
         * @param key   claim name (must not be {@code null})
         * @param value claim value (may be {@code null} to represent a null/absent claim)
         * @return this builder
         */
        public Builder claim(String key, String value) {
            Objects.requireNonNull(key, "key must not be null");
            customClaims.put(key, value);
            return this;
        }

        /**
         * Adds a claim with a non-string JSON value (number, boolean, list, map). Used to mint
         * wrong-typed claims for adversarial fail-closed tests (e.g. a numeric
         * {@code ISOLATION_STRATEGY} that a string-typed reader rejects as malformed).
         *
         * @param key   claim name (must not be {@code null})
         * @param value raw claim value
         * @return this builder
         */
        public Builder claimRaw(String key, Object value) {
            Objects.requireNonNull(key, "key must not be null");
            rawClaims.put(key, value);
            return this;
        }

        /**
         * Builds the serialized JWT string.
         *
         * @return compact serialization (header.payload.signature)
         * @throws IllegalStateException if signing fails
         */
        public String serialize() {
            try {
                JWTClaimsSet claims = buildClaims();

                if (algNone) {
                    // Unsecured JWT: header {"alg":"none"}, empty signature segment.
                    return new PlainJWT(claims).serialize();
                }

                JWSAlgorithm alg = hmacConfusion ? JWSAlgorithm.HS256 : JWSAlgorithm.RS256;
                JWSHeader.Builder headerBuilder = new JWSHeader.Builder(alg);
                if (!omitKid) {
                    headerBuilder.keyID(kid);
                }
                SignedJWT jwt = new SignedJWT(headerBuilder.build(), claims);
                jwt.sign(signerFor());

                String serialized = jwt.serialize();
                if (tamperSignature) {
                    serialized = tamperSignatureSegment(serialized);
                }
                return serialized;
            } catch (JOSEException ex) {
                throw new IllegalStateException("Failed to sign test JWT", ex);
            }
        }

        private JWTClaimsSet buildClaims() {
            Instant issuedAt = Instant.now();
            Instant expiry = expired
                ? issuedAt.minusSeconds(60L)
                : issuedAt.plusSeconds(expiresInSeconds);

            JWTClaimsSet.Builder claimsBuilder = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(issuer)
                .audience(List.of(audience))
                .issueTime(Timestamp.from(issuedAt))
                .expirationTime(Timestamp.from(expiry));
            for (Map.Entry<String, String> entry : customClaims.entrySet()) {
                claimsBuilder.claim(entry.getKey(), entry.getValue());
            }
            for (Map.Entry<String, Object> entry : rawClaims.entrySet()) {
                claimsBuilder.claim(entry.getKey(), entry.getValue());
            }
            return claimsBuilder.build();
        }

        private JWSSigner signerFor() throws JOSEException {
            if (hmacConfusion) {
                // RS256->HS256 confusion: HMAC keyed on the RSA public key's encoded bytes
                // (>= 256 bits, satisfying the HS256 minimum-secret requirement).
                return new MACSigner(TEST_KEY_PAIR.getPublic().getEncoded());
            }
            return new RSASSASigner(TEST_KEY_PAIR.getPrivate());
        }

        /**
         * Builds the JWT and wraps it in a {@link LoanedBuffer} backed by a heap byte array.
         * The caller is responsible for closing the returned buffer.
         *
         * @return heap-backed loaned buffer containing the JWT bytes
         */
        public LoanedBuffer toBuffer() {
            return bufferOf(serialize());
        }

        private static String tamperSignatureSegment(String serialized) {
            String[] segments = serialized.split("\\.", -1);
            if (segments.length != COMPACT_JWT_SEGMENT_COUNT) {
                throw new IllegalStateException("Unexpected compact JWT format");
            }

            byte[] signature = Base64.getUrlDecoder().decode(segments[2]);
            signature[signature.length - 1] ^= 0x01;
            segments[2] = Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
            return String.join(".", segments);
        }
    }

    /**
     * {@link LoanedBuffer} backed by a heap byte array.
    * Suitable for test use only — no native resources, no reference counting.
     */
    /* default */
    static final class HeapLoanedBuffer implements LoanedBuffer {

        private final MemorySegment segment;
        private long size;

        /* default */ HeapLoanedBuffer(byte[] bytes) {
            this.segment = MemorySegment.ofArray(bytes);
            this.size = bytes.length;
        }

        @Override
        public MemorySegment segment() {
            return segment;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public long capacity() {
            return segment.byteSize();
        }

        @Override
        public LoanedBuffer slice(long offset, long length) {
            byte[] slice = segment.asSlice(offset, length).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
            return new HeapLoanedBuffer(slice);
        }

        @Override
        public LoanedBuffer view() {
            return new HeapLoanedBuffer(segment.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
        }

        @Override
        public LoanedBuffer peek(long offset, long length) {
            return slice(offset, length);
        }

        @Override
        public void retain() {
            // No-op: heap buffer has no reference counting.
        }

        @Override
        public void close() {
            // No-op: heap memory, no native resources.
        }

        @Override
        public int refCount() {
            return 1;
        }

        @Override
        public void setSize(long newSize) {
            this.size = newSize;
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public void addCloseAction(Runnable action) {
            // No-op: heap buffer does not own releasable resources.
        }
    }
}
