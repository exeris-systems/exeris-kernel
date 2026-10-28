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
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Signed-JWT fixture for security contract tests: builds RSA-2048, Ed25519 and, for
 * algorithm-confusion tests, HMAC-signed tokens without a running identity provider.
 *
 * <p>Every RS256 token is signed with the single {@link #TEST_KEY_PAIR} generated once for the
 * lifetime of the JVM; {@link #keySet()} exposes the matching public key under {@link #TEST_KID}
 * for a validator fixture to resolve, and {@link #UNKNOWN_KID} is a kid deliberately absent from
 * that key set, for negative tests. An Ed25519 token ({@link Builder#signedWithEd25519()}) is
 * signed with {@link #TEST_ED25519_KEY_PAIR}, whose public key a fixture installs under
 * {@link #TEST_ED25519_KID}; {@link #keySet()} does not include it, because that map is RSA-typed.
 *
 * <p>What it deliberately does not simulate: there is no JWKS endpoint and no key rotation, and
 * {@link Builder#expired()} back-dates {@code exp} by a fixed offset rather than advancing a clock.
 * {@link Builder#algNone()}, {@link Builder#hmacConfusion()} and
 * {@link Builder#hmacConfusionOverEd25519Key()} mint tokens for the {@code alg=none} downgrade and the
 * symmetric-MAC confusion attacks, {@link Builder#headerAlgorithm(String)} and
 * {@link Builder#criticalHeaderParam(String, String)} shape the header a validator must pin against,
 * and {@link Builder#tamperedSignature()} corrupts a signed token's signature bytes. Together with
 * {@link Builder#claimRaw(String, Object)} for wrong-typed claims, the builder targets the malformed
 * and adversarial inputs a validator binding test needs, not the shape of a real identity provider.
 *
 * <p>Used by Community's {@code SecurityProvider} TCK binding test and by other security
 * integration tests in this repository.
 *
 * <p>Usage:
 * {@snippet lang="java" :
 * TestJwt.Builder builder = TestJwt.builder()
 *     .kid(TestJwt.TEST_KID)
 *     .issuer("https://auth.example.com")
 *     .audience("exeris-kernel")
 *     .subject("00000000-0000-7000-8000-000000000001")
 *     .expiresInSeconds(300);
 * LoanedBuffer buffer = builder.toBuffer();
 * }
 *
 * @since 0.5
 */
@SuppressWarnings({"PMD.TestClassWithoutTestCases", "PMD.UnitTestShouldUseTestAnnotation"})
public final class TestJwt {

    /** The stable test kid used for the test key pair. */
    public static final String TEST_KID = "test-key-1";

    /** An unknown kid that is NOT in the test key set. */
    public static final String UNKNOWN_KID = "unknown-key-99";

    /** Default {@code iss} claim for a token built without calling {@link Builder#issuer(String)}. */
    public static final String EXPECTED_ISSUER = "https://auth.example.com";

    /** Default {@code aud} claim for a token built without calling {@link Builder#audience(String)}. */
    public static final String EXPECTED_AUDIENCE = "exeris-kernel";

    /** Default {@code sub} claim for a token built without calling {@link Builder#subject(String)}. */
    public static final String TEST_SUBJECT = "00000000-0000-7000-8000-000000000001";

    /** The stable test kid used for the Ed25519 test key pair. */
    public static final String TEST_ED25519_KID = "test-ed25519-key-1";

    /** The test RSA key pair generated once for the lifetime of the JVM. */
    public static final KeyPair TEST_KEY_PAIR;

    /** The test Ed25519 key pair generated once for the lifetime of the JVM. */
    public static final KeyPair TEST_ED25519_KEY_PAIR;

    private static final int ED25519_RAW_KEY_LENGTH = 32;

    static {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            TEST_KEY_PAIR = kpg.generateKeyPair();
            TEST_ED25519_KEY_PAIR = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private TestJwt() {
        // Utility class.
    }

    /**
     * The RSA public key half of {@link #TEST_KEY_PAIR}, the key {@link #keySet()} exposes under
     * {@link #TEST_KID}.
     *
     * @return the test RSA public key
     */
    public static RSAPublicKey testPublicKey() {
        return (RSAPublicKey) TEST_KEY_PAIR.getPublic();
    }

    /**
     * The Ed25519 public key half of {@link #TEST_ED25519_KEY_PAIR}.
     *
     * @return the test Ed25519 public key
     */
    public static PublicKey testEd25519PublicKey() {
        return TEST_ED25519_KEY_PAIR.getPublic();
    }

    /**
     * The raw 32-byte Ed25519 public key (RFC 8032 encoding, the JWK {@code x} value before
     * base64url), the trailing bytes of the key's X.509 encoding.
     *
     * @return a fresh copy of the 32 raw public key bytes
     */
    public static byte[] testEd25519PublicKeyRaw() {
        byte[] encoded = TEST_ED25519_KEY_PAIR.getPublic().getEncoded();
        return Arrays.copyOfRange(encoded, encoded.length - ED25519_RAW_KEY_LENGTH, encoded.length);
    }

    /**
     * A single-entry key set mapping {@link #TEST_KID} to {@link #testPublicKey()}.
     *
     * @return an immutable map with exactly one key ID and its RSA public key
     */
    public static Map<String, RSAPublicKey> keySet() {
        return Map.of(TEST_KID, testPublicKey());
    }

    /**
     * Wraps the UTF-8 bytes of a compact JWT in a heap-backed {@link LoanedBuffer}.
     *
     * <p>The returned buffer copies rather than shares its bytes on {@code slice}/{@code view}, and
     * releases nothing on {@code close()} — it does not exercise the zero-copy or
     * reference-counting guarantees a real allocator's buffer makes.
     *
     * @param compactJwt the compact JWT serialization to wrap; must not be {@code null}
     * @return a loaned buffer over the token's UTF-8 bytes
     */
    public static LoanedBuffer bufferOf(String compactJwt) {
        return new HeapLoanedBuffer(Objects.requireNonNull(compactJwt, "compactJwt must not be null")
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Creates a new builder pre-populated with {@link #TEST_KID}, {@link #EXPECTED_ISSUER},
     * {@link #EXPECTED_AUDIENCE}, {@link #TEST_SUBJECT} and a 300-second expiry.
     *
     * @return a new, independently configurable builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Mutable builder for test JWTs.
     *
     * <p>Each setter mutates this builder and returns it, and {@link #serialize()} may be called
     * more than once — nothing marks a builder consumed. Not thread-safe: a single instance is not
     * meant to be shared across threads.
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
        private boolean ed25519;
        private boolean hmacOverEd25519;
        private String headerAlgorithm;
        private String criticalName;
        private String criticalValue;
        private final Map<String, String> customClaims = new LinkedHashMap<>();
        private final Map<String, Object> rawClaims = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Sets the {@code kid} header value, cancelling a prior {@link #noKid()}.
         *
         * @param kid the key ID to put in the JWS header
         * @return this builder
         */
        public Builder kid(String kid) {
            this.kid = kid;
            this.omitKid = false;
            return this;
        }

        /**
         * Omits the {@code kid} header entirely, producing a token with no key hint.
         *
         * @return this builder
         */
        public Builder noKid() {
            this.omitKid = true;
            return this;
        }

        /**
         * Sets the {@code iss} claim, overriding {@link #EXPECTED_ISSUER}.
         *
         * @param issuer the issuer to put in the JWT
         * @return this builder
         */
        public Builder issuer(String issuer) {
            this.issuer = issuer;
            return this;
        }

        /**
         * Sets the {@code aud} claim, overriding {@link #EXPECTED_AUDIENCE}.
         *
         * @param audience the audience to put in the JWT
         * @return this builder
         */
        public Builder audience(String audience) {
            this.audience = audience;
            return this;
        }

        /**
         * Sets the {@code sub} claim, overriding {@link #TEST_SUBJECT}.
         *
         * @param subject the subject to put in the JWT
         * @return this builder
         */
        public Builder subject(String subject) {
            this.subject = subject;
            return this;
        }

        /**
         * Sets how many seconds after signing the token expires, cancelling a prior
         * {@link #expired()}.
         *
         * @param seconds seconds from the moment {@link #serialize()} runs until {@code exp}
         * @return this builder
         */
        public Builder expiresInSeconds(long seconds) {
            this.expiresInSeconds = seconds;
            this.expired = false;
            return this;
        }

        /**
         * Produces a token whose {@code exp} is 60 seconds before its {@code iat}, overriding
         * {@link #expiresInSeconds(long)} so the token is already expired.
         *
         * @return this builder
         */
        public Builder expired() {
            this.expired = true;
            return this;
        }

        /**
         * Produces a token whose signature bytes are corrupted after signing, by flipping the low
         * bit of the last signature byte. A conforming verifier MUST reject it.
         *
         * @return this builder
         */
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
         *
         * @return this builder
         */
        public Builder algNone() {
            this.algNone = true;
            this.hmacConfusion = false;
            this.hmacOverEd25519 = false;
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
         *
         * @return this builder
         */
        public Builder hmacConfusion() {
            this.hmacConfusion = true;
            this.hmacOverEd25519 = false;
            this.algNone = false;
            return this;
        }

        /**
         * Signs the token with {@link #TEST_ED25519_KEY_PAIR}; the header names {@code EdDSA}
         * unless {@link #headerAlgorithm(String)} overrides it. Resets {@link #algNone()} and the
         * HMAC-confusion modes, and {@link #tamperedSignature()} still applies.
         *
         * @return this builder
         */
        public Builder signedWithEd25519() {
            this.ed25519 = true;
            this.algNone = false;
            this.hmacConfusion = false;
            this.hmacOverEd25519 = false;
            return this;
        }

        /**
         * Names {@code alg} in the JWS header instead of the algorithm the signing mode implies.
         *
         * <p>With the default RSA signing the token is genuinely signed with that algorithm
         * ({@code RS256/384/512} or {@code PS256/384/512}; any other name throws
         * {@link IllegalStateException} from {@link #serialize()}). With
         * {@link #signedWithEd25519()} only the header changes and the signature stays Ed25519,
         * which models a header that disagrees with the key that signed.
         *
         * @param alg the JWS algorithm name for the header
         * @return this builder
         */
        public Builder headerAlgorithm(String alg) {
            this.headerAlgorithm = Objects.requireNonNull(alg, "alg must not be null");
            return this;
        }

        /**
         * Produces a token signed with <b>HS256</b> using the X.509 bytes of the Ed25519
         * <i>public</i> key as the HMAC secret: the symmetric-MAC confusion attack aimed at an
         * Ed25519 key. The {@code kid} is retained so the token reaches the algorithm gate.
         *
         * @return this builder
         */
        public Builder hmacConfusionOverEd25519Key() {
            this.hmacOverEd25519 = true;
            this.hmacConfusion = false;
            this.ed25519 = false;
            this.algNone = false;
            return this;
        }

        /**
         * Adds a {@code crit} header listing {@code name} and a header parameter {@code name} with
         * {@code value}, so the token carries a critical extension a verifier does not understand.
         *
         * @param name  the critical header parameter name
         * @param value the parameter's string value
         * @return this builder
         */
        public Builder criticalHeaderParam(String name, String value) {
            this.criticalName = Objects.requireNonNull(name, "name must not be null");
            this.criticalValue = Objects.requireNonNull(value, "value must not be null");
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

                JWSAlgorithm alg = headerAlgorithmFor();
                JWSHeader.Builder headerBuilder = new JWSHeader.Builder(alg);
                if (!omitKid) {
                    headerBuilder.keyID(kid);
                }
                if (criticalName != null) {
                    headerBuilder.criticalParams(Set.of(criticalName));
                    headerBuilder.customParam(criticalName, criticalValue);
                }
                JWSHeader header = headerBuilder.build();
                SignedJWT jwt = new SignedJWT(header, claims);

                String serialized;
                if (ed25519) {
                    serialized = signEd25519(jwt);
                } else {
                    jwt.sign(signerFor());
                    serialized = jwt.serialize();
                }
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

        private JWSAlgorithm headerAlgorithmFor() {
            if (headerAlgorithm != null) {
                return JWSAlgorithm.parse(headerAlgorithm);
            }
            if (hmacConfusion || hmacOverEd25519) {
                return JWSAlgorithm.HS256;
            }
            return ed25519 ? JWSAlgorithm.EdDSA : JWSAlgorithm.RS256;
        }

        /** Signs the JWS signing input with the JDK's Ed25519 and joins the compact form by hand. */
        private String signEd25519(SignedJWT jwt) {
            try {
                Signature signature = Signature.getInstance("Ed25519");
                signature.initSign(TEST_ED25519_KEY_PAIR.getPrivate());
                String signingInput = new String(jwt.getSigningInput(), StandardCharsets.US_ASCII);
                signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
                return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
            } catch (GeneralSecurityException ex) {
                throw new IllegalStateException("Failed to sign test JWT with Ed25519", ex);
            }
        }

        private JWSSigner signerFor() throws JOSEException {
            if (hmacOverEd25519) {
                return new MACSigner(TEST_ED25519_KEY_PAIR.getPublic().getEncoded());
            }
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
     * {@link LoanedBuffer} backed by a heap byte array, for test use only.
     *
     * <p>Does not honour the zero-copy contract {@link LoanedBuffer} specifies: {@link #slice} and
     * {@link #view} copy the requested bytes into a new array rather than sharing the backing
     * segment.
     *
     * <p><b>Allocation:</b> one heap byte array per instance; {@code slice}/{@code view} each
     * allocate a further copy.
     * <p><b>Thread confinement:</b> none — a plain heap object with no native resource, safe to
     * hand to any thread.
     * <p><b>Ownership:</b> none to release; {@link #retain()} and {@link #close()} are no-ops,
     * {@link #refCount()} is always {@code 1}, and {@link #isAlive()} always reports {@code true}.
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
