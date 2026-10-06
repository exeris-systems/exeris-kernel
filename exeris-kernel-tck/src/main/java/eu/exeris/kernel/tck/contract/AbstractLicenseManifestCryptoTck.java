/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract;

import eu.exeris.kernel.spi.contract.EnforcementLevel;
import eu.exeris.kernel.spi.contract.ExecutionContract;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.contract.ContractBreachException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK cryptographic verification suite for license manifests per ADR-088.
 *
 * <p>Covers the four assertions of ADR-088's Engineering Protocol item 4 — a bit flip in the capability
 * list breaks the signature, key reordering and reformatting do not, an unregistered {@code keyId} fails
 * with {@code EX-LIC-0002}, and expiry beyond the grace period fails with {@code EX-LIC-0003} (and use before {@code validFrom} with
 * {@code EX-LIC-0006}) — and
 * anchors the canonicalization to a manifest signed by an independent RFC 8785 implementation, so a
 * canonicalizer that agrees only with itself cannot pass. Every evaluation instant is fixed.
 *
 * @since 0.13
 */
public abstract class AbstractLicenseManifestCryptoTck {

    /** keyId the suite's generated test issuer key is trusted under. */
    protected static final String TEST_KEY_ID = "tck-test-issuer-k1";

    /** keyId of the golden manifest's issuer. */
    protected static final String GOLDEN_KEY_ID = "tck-golden-k1";

    /** Raw Ed25519 public key of the golden manifest's issuer; the key exists only for this suite. */
    protected static final String GOLDEN_PUBLIC_KEY_HEX =
            "cd43bd42e7de23704d671a10a9014f14cb96e6b3e9398d2d5e8fb926c6d8274b";

    /** An instant inside the golden manifest's validity window. */
    protected static final Instant GOLDEN_EVALUATION_TIME = Instant.parse("2026-10-06T12:00:00Z");

    /** Evaluation instant of every non-golden case. */
    protected static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    /**
     * A manifest canonicalized and signed by an implementation independent of the kernel (a JavaScript
     * RFC 8785 serializer and Node's Ed25519). Its keys are out of canonical order, it is pretty-printed,
     * and its numbers are written as {@code 2e5}, {@code 5.0e4} and {@code 20.0}, and a string carries
     * U+001F, which RFC 8785 escapes with lowercase hex digits: it verifies only if the
     * implementation under test produces the same canonical bytes.
     */
    protected static final String GOLDEN_SIGNED_MANIFEST = """
            {
              "signature": {
                "value": "CRzK5Xs2KBVjGDxZjU9pyQrs9wSqROT+LDTFKltgfCCJiJPBobDbsgFqqOpIAbjZB/tPPzVs8X5HgJAKvlkiDw==",
                "canonicalization": "RFC-8785",
                "algorithm": "Ed25519"
              },
              "issuer": {
                "keyId": "tck-golden-k1",
                "issuedAt": "2026-09-12T12:00:00Z",
                "authority": "Exeris \\"Golden\\" Issuer / TCK"
              },
              "execution": {
                "workloadEnvelope": { "growthAllowancePercent": 20.0, "maxConnections": 5.0e4, "maxThroughputRps": 2e5 },
                "validUntil": "2027-09-30T23:59:59Z",
                "gracePeriodDays": 14,
                "authorizedInstances": 25,
                "environments": ["production", "dr-hot"],
                "validFrom": "2026-10-01T00:00:00Z"
              },
              "enforcementRules": {
                "workloadEnvelope": "AUDIT",
                "authorizedInstances": "SOFT",
                "environment": "HARD",
                "capability": "HARD"
              },
              "entitlement": {
                "capabilities": ["security.tls", "gateway.core", "gateway.rate-limit"],
                "sku": "exeris-sku-\\u20ac-gateway",
                "licenseMode": "SUBSCRIPTION",
                "edition": "enterprise",
                "workloadProfileRef": "WP-2026-\\u00e9\\ud83d\\ude00-01\\t\\u001f"
              },
              "contract": { "commercialModel": "CAPACITY", "framework": "ECSL-1.0", "id": "EXR-TCK-GOLDEN-0001" },
              "$schema": "https://specs.exeris.eu/schema/v1/license-manifest.json"
            }""";

    /** {@link #GOLDEN_SIGNED_MANIFEST} without its {@code signature} block. */
    protected static final String GOLDEN_UNSIGNED_MANIFEST = """
            {
              "issuer": {
                "keyId": "tck-golden-k1",
                "issuedAt": "2026-09-12T12:00:00Z",
                "authority": "Exeris \\"Golden\\" Issuer / TCK"
              },
              "execution": {
                "workloadEnvelope": { "growthAllowancePercent": 20.0, "maxConnections": 5.0e4, "maxThroughputRps": 2e5 },
                "validUntil": "2027-09-30T23:59:59Z",
                "gracePeriodDays": 14,
                "authorizedInstances": 25,
                "environments": ["production", "dr-hot"],
                "validFrom": "2026-10-01T00:00:00Z"
              },
              "enforcementRules": {
                "workloadEnvelope": "AUDIT",
                "authorizedInstances": "SOFT",
                "environment": "HARD",
                "capability": "HARD"
              },
              "entitlement": {
                "capabilities": ["security.tls", "gateway.core", "gateway.rate-limit"],
                "sku": "exeris-sku-\\u20ac-gateway",
                "licenseMode": "SUBSCRIPTION",
                "edition": "enterprise",
                "workloadProfileRef": "WP-2026-\\u00e9\\ud83d\\ude00-01\\t\\u001f"
              },
              "contract": { "commercialModel": "CAPACITY", "framework": "ECSL-1.0", "id": "EXR-TCK-GOLDEN-0001" },
              "$schema": "https://specs.exeris.eu/schema/v1/license-manifest.json"
            }""";

    /** The canonical form of {@link #GOLDEN_UNSIGNED_MANIFEST}, as the independent implementation produced it. */
    protected static final String GOLDEN_CANONICAL = "{\"$schema\":\"https://specs.exeris.eu/schema/v1/license-manifest.json\",\"contract\":{\"commercialModel\":\"CAPACITY\",\"framework\":\"ECSL-1.0\",\"id\":\"EXR-TCK-GOLDEN-0001\"},\"enforcementRules\":{\"authorizedInstances\":\"SOFT\",\"capability\":\"HARD\",\"environment\":\"HARD\",\"workloadEnvelope\":\"AUDIT\"},\"entitlement\":{\"capabilities\":[\"security.tls\",\"gateway.core\",\"gateway.rate-limit\"],\"edition\":\"enterprise\",\"licenseMode\":\"SUBSCRIPTION\",\"sku\":\"exeris-sku-\u20ac-gateway\",\"workloadProfileRef\":\"WP-2026-\u00e9\ud83d\ude00-01\\t\\u001f\"},\"execution\":{\"authorizedInstances\":25,\"environments\":[\"production\",\"dr-hot\"],\"gracePeriodDays\":14,\"validFrom\":\"2026-10-01T00:00:00Z\",\"validUntil\":\"2027-09-30T23:59:59Z\",\"workloadEnvelope\":{\"growthAllowancePercent\":20,\"maxConnections\":50000,\"maxThroughputRps\":200000}},\"issuer\":{\"authority\":\"Exeris \\\"Golden\\\" Issuer / TCK\",\"issuedAt\":\"2026-09-12T12:00:00Z\",\"keyId\":\"tck-golden-k1\"}}";

    private static final Instant VALID_FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant VALID_UNTIL = Instant.parse("2026-06-01T00:00:00Z");
    private static final int GRACE_DAYS = 7;
    private static final byte[] ED25519_SPKI_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    private KeyPair keyPair;

    /** Constructor for subclasses. */
    protected AbstractLicenseManifestCryptoTck() {
        // binding classes supply the verifier and canonicalizer under test
    }

    /**
     * Verifies the given license manifest against an evaluation timestamp, trusting exactly one
     * issuer key. The binding must route the key through the implementation's caller-supplied key
     * resolution and never through a process-wide trust store.
     *
     * @param manifestJson   manifest payload JSON
     * @param evaluationTime evaluation instant
     * @param issuerKeyId    the only keyId the verification may trust
     * @param issuerKey      the Ed25519 public key for {@code issuerKeyId}
     * @return validated execution contract
     */
    protected abstract ExecutionContract verifyManifest(
            String manifestJson, Instant evaluationTime, String issuerKeyId, PublicKey issuerKey);

    /**
     * Canonicalizes the provided JSON payload according to RFC 8785.
     *
     * @param json raw JSON string
     * @return canonical bytes
     */
    protected abstract byte[] canonicalizeJson(String json);

    /**
     * Generates this run's test issuer key pair.
     *
     * @throws Exception if the JDK has no Ed25519 key pair generator
     */
    @BeforeEach
    protected void setUpKeys() throws Exception {
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    /**
     * Verifies {@code manifestJson} trusting only this suite's generated test issuer key.
     *
     * @param manifestJson   manifest payload JSON
     * @param evaluationTime evaluation instant
     * @return validated execution contract
     */
    protected final ExecutionContract verifyManifest(String manifestJson, Instant evaluationTime) {
        return verifyManifest(manifestJson, evaluationTime, TEST_KEY_ID, keyPair.getPublic());
    }

    /**
     * Verifies {@code manifestJson} trusting only the golden issuer key.
     *
     * @param manifestJson manifest payload JSON
     * @return validated execution contract
     * @throws Exception if the golden key cannot be decoded
     */
    protected final ExecutionContract verifyGolden(String manifestJson) throws Exception {
        byte[] spki = new byte[ED25519_SPKI_PREFIX.length + 32];
        System.arraycopy(ED25519_SPKI_PREFIX, 0, spki, 0, ED25519_SPKI_PREFIX.length);
        System.arraycopy(HexFormat.of().parseHex(GOLDEN_PUBLIC_KEY_HEX), 0, spki, ED25519_SPKI_PREFIX.length, 32);
        PublicKey goldenKey = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
        return verifyManifest(manifestJson, GOLDEN_EVALUATION_TIME, GOLDEN_KEY_ID, goldenKey);
    }

    /**
     * Builds a manifest and signs it with this run's test issuer key over the canonical form the
     * implementation under test produces.
     *
     * @param keyId            issuer keyId written into the manifest
     * @param edition          entitlement edition
     * @param validFrom        start of validity
     * @param validUntil       end of nominal validity
     * @param graceDays        grace period in days
     * @param level            capability enforcement level
     * @param maxThroughputRps workload envelope throughput
     * @return signed manifest JSON
     * @throws Exception if signing fails
     */
    protected String buildSignedManifest(
            String keyId,
            String edition,
            Instant validFrom,
            Instant validUntil,
            int graceDays,
            EnforcementLevel level,
            long maxThroughputRps
    ) throws Exception {
        String unsignedJson = String.format(
                "{\"$schema\":\"https://specs.exeris.eu/schema/v1/license-manifest.json\","
                        + "\"issuer\":{\"keyId\":\"%s\",\"issuedAt\":\"2026-01-01T00:00:00Z\"},"
                        + "\"contract\":{\"id\":\"TCK-CONTRACT-001\",\"commercialModel\":\"STANDARD\"},"
                        + "\"entitlement\":{\"edition\":\"%s\",\"sku\":\"exeris-sku-core\","
                        + "\"licenseMode\":\"SUBSCRIPTION\",\"capabilities\":[\"ha-clustering\",\"tls-offload\"]},"
                        + "\"execution\":{\"environments\":[\"production\",\"staging\"],\"authorizedInstances\":5,"
                        + "\"validFrom\":\"%s\",\"validUntil\":\"%s\",\"gracePeriodDays\":%d,"
                        + "\"workloadEnvelope\":{\"maxThroughputRps\":%d,\"maxConnections\":250,"
                        + "\"growthAllowancePercent\":15}},"
                        + "\"enforcementRules\":{\"capability\":\"%s\",\"environment\":\"HARD\"}}",
                keyId,
                edition,
                validFrom,
                validUntil,
                graceDays,
                maxThroughputRps,
                level.name()
        );
        return withSignature(unsignedJson, sign(keyPair, canonicalizeJson(unsignedJson)));
    }

    private static String sign(KeyPair signer, byte[] payload) throws Exception {
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(signer.getPrivate());
        sig.update(payload);
        return Base64.getEncoder().encodeToString(sig.sign());
    }

    private static String withSignature(String unsignedJson, String signatureBase64) {
        return unsignedJson.substring(0, unsignedJson.length() - 1)
                + ",\"signature\":{\"algorithm\":\"Ed25519\",\"canonicalization\":\"RFC-8785\","
                + "\"value\":\"" + signatureBase64 + "\"}}";
    }

    private String windowManifest(Instant validFrom, Instant validUntil, int graceDays) throws Exception {
        return buildSignedManifest(TEST_KEY_ID, "enterprise", validFrom, validUntil, graceDays,
                EnforcementLevel.HARD, 5000L);
    }

    private static void assertBreach(ThrowingCall call, String errorCode) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ContractBreachException.class)
                .satisfies(e -> assertThat(((ContractBreachException) e).errorCode()).isEqualTo(errorCode));
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }

    // ── Independent canonicalization (ADR-088 Engineering Protocol 4, test 2) ──

    @Test
    @DisplayName("A manifest signed by an independent RFC 8785 implementation verifies despite reordering and reformatting")
    void goldenManifestVerifies() throws Exception {
        ExecutionContract contract = verifyGolden(GOLDEN_SIGNED_MANIFEST);

        assertThat(contract.contractId()).isEqualTo("EXR-TCK-GOLDEN-0001");
        assertThat(contract.entitledCapabilities())
                .containsExactlyInAnyOrder("security.tls", "gateway.core", "gateway.rate-limit");
        assertThat(contract.envelope().maxThroughputRps()).isEqualTo(200_000L);
        assertThat(contract.envelope().maxConnections()).isEqualTo(50_000);
    }

    @Test
    @DisplayName("Canonical bytes equal the independent implementation's, byte for byte")
    void goldenCanonicalBytesMatch() {
        assertThat(canonicalizeJson(GOLDEN_UNSIGNED_MANIFEST))
                .isEqualTo(GOLDEN_CANONICAL.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("The golden manifest minified into canonical order verifies with the same signature")
    void goldenManifestMinifiedVerifies() throws Exception {
        String signature = GOLDEN_SIGNED_MANIFEST.replaceAll("(?s).*\"value\": \"([^\"]+)\".*", "$1");

        assertThat(verifyGolden(withSignature(GOLDEN_CANONICAL, signature)).contractId())
                .isEqualTo("EXR-TCK-GOLDEN-0001");
    }

    // ── Tamper detection (test 1) ───────────────────────────────────────────

    @Test
    @DisplayName("One changed character in the capability list breaks the signature with EX-LIC-0001")
    void capabilityBitFlipInvalidatesSignature() {
        String tampered = GOLDEN_SIGNED_MANIFEST.replace("\"gateway.core\"", "\"gateway.cord\"");
        assertThat(tampered).isNotEqualTo(GOLDEN_SIGNED_MANIFEST);

        assertBreach(() -> verifyGolden(tampered), KernelErrorCodes.EX_LIC_0001);
    }

    @Test
    @DisplayName("A changed workload envelope value breaks the signature with EX-LIC-0001")
    void tamperDetectionInvalidatesSignature() throws Exception {
        String manifest = windowManifest(NOW.minus(2, ChronoUnit.DAYS), NOW.plus(10, ChronoUnit.DAYS), 7);
        assertThat(verifyManifest(manifest, NOW)).isNotNull();

        String tampered = manifest.replace("\"maxThroughputRps\":5000", "\"maxThroughputRps\":5001");
        assertBreach(() -> verifyManifest(tampered, NOW), KernelErrorCodes.EX_LIC_0001);
    }

    @Test
    @DisplayName("Whitespace and line breaks do not change the canonical bytes")
    void jsonFormattingIndifference() throws Exception {
        String manifest = windowManifest(NOW.minus(2, ChronoUnit.DAYS), NOW.plus(10, ChronoUnit.DAYS), 7);
        String reformatted = manifest
                .replace("{\"", "{\n  \"")
                .replace(",\"", ",\n  \"")
                .replace("\":", "\"  :  ");

        assertThat(verifyManifest(reformatted, NOW).contractId()).isEqualTo("TCK-CONTRACT-001");
    }

    @Test
    @DisplayName("A signature by another key under a trusted keyId fails with EX-LIC-0001")
    void signatureByAnotherKeyFails() throws Exception {
        KeyPair impostor = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String unsigned = GOLDEN_UNSIGNED_MANIFEST.replace("tck-golden-k1", TEST_KEY_ID);
        String forged = withSignature(
                new String(canonicalizeJson(unsigned), StandardCharsets.UTF_8),
                sign(impostor, canonicalizeJson(unsigned)));

        assertBreach(() -> verifyManifest(forged, GOLDEN_EVALUATION_TIME), KernelErrorCodes.EX_LIC_0001);
    }

    @Test
    @DisplayName("A manifest without a signature block fails with EX-LIC-0001")
    void missingSignatureFails() {
        assertBreach(() -> verifyGolden(GOLDEN_UNSIGNED_MANIFEST), KernelErrorCodes.EX_LIC_0001);
    }

    @Test
    @DisplayName("Duplicate keys in manifest payload fail verification with EX-LIC-0001 per RFC 8785")
    void duplicateKeysInManifestThrows() throws Exception {
        String manifest = windowManifest(NOW.minus(2, ChronoUnit.DAYS), NOW.plus(10, ChronoUnit.DAYS), 7);
        String schema = "\"$schema\":\"https://specs.exeris.eu/schema/v1/license-manifest.json\",";
        String duplicated = manifest.replace(schema, schema + schema);

        assertBreach(() -> verifyManifest(duplicated, NOW), KernelErrorCodes.EX_LIC_0001);
    }

    // ── Issuer key (test 3) ─────────────────────────────────────────────────

    @Test
    @DisplayName("Unknown issuer keyId fails verification with EX-LIC-0002")
    void untrustedIssuerKeyIdThrows() throws Exception {
        String manifest = buildSignedManifest("untrusted-rogue-key", "enterprise",
                NOW.minus(2, ChronoUnit.DAYS), NOW.plus(10, ChronoUnit.DAYS), 7, EnforcementLevel.HARD, 5000L);

        assertThatThrownBy(() -> verifyManifest(manifest, NOW))
                .isInstanceOf(ContractBreachException.class)
                .satisfies(e -> {
                    ContractBreachException breach = (ContractBreachException) e;
                    assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0002);
                    assertThat(breach.rawArgs()).containsExactly("untrusted-rogue-key");
                });
    }

    // ── Validity window (test 4), each edge to the second ───────────────────

    @Test
    @DisplayName("Valid at exactly validFrom; one second earlier fails with EX-LIC-0006")
    void validFromEdge() throws Exception {
        String manifest = windowManifest(VALID_FROM, VALID_UNTIL, GRACE_DAYS);

        assertThat(verifyManifest(manifest, VALID_FROM)).isNotNull();
        assertBreach(() -> verifyManifest(manifest, VALID_FROM.minusSeconds(1)), KernelErrorCodes.EX_LIC_0006);
    }

    @Test
    @DisplayName("The grace period extends only the end of validity, never its start")
    void graceDoesNotPrecedeValidFrom() throws Exception {
        String manifest = windowManifest(VALID_FROM, VALID_UNTIL, GRACE_DAYS);

        assertBreach(() -> verifyManifest(manifest, VALID_FROM.minus(2, ChronoUnit.DAYS)),
                KernelErrorCodes.EX_LIC_0006);
    }

    @Test
    @DisplayName("Inside the grace period the manifest still verifies")
    void withinGraceSucceeds() throws Exception {
        String manifest = windowManifest(VALID_FROM, VALID_UNTIL, GRACE_DAYS);

        assertThat(verifyManifest(manifest, VALID_UNTIL.plusSeconds(1)).contractId()).isEqualTo("TCK-CONTRACT-001");
    }

    @Test
    @DisplayName("Valid at exactly validUntil + gracePeriodDays; one second later fails with EX-LIC-0003")
    void graceDeadlineEdge() throws Exception {
        String manifest = windowManifest(VALID_FROM, VALID_UNTIL, GRACE_DAYS);
        Instant deadline = VALID_UNTIL.plus(GRACE_DAYS, ChronoUnit.DAYS);

        assertThat(verifyManifest(manifest, deadline)).isNotNull();
        assertThatThrownBy(() -> verifyManifest(manifest, deadline.plusSeconds(1)))
                .isInstanceOf(ContractBreachException.class)
                .satisfies(e -> {
                    ContractBreachException breach = (ContractBreachException) e;
                    assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0003);
                    assertThat(breach.rawArgs()).containsExactly("TCK-CONTRACT-001", VALID_UNTIL, GRACE_DAYS);
                });
    }

    @Test
    @DisplayName("With no grace period, one second past validUntil fails with EX-LIC-0003")
    void zeroGraceEdge() throws Exception {
        String manifest = windowManifest(VALID_FROM, VALID_UNTIL, 0);

        assertThat(verifyManifest(manifest, VALID_UNTIL)).isNotNull();
        assertBreach(() -> verifyManifest(manifest, VALID_UNTIL.plusSeconds(1)), KernelErrorCodes.EX_LIC_0003);
    }

    @Test
    @DisplayName("Perpetual manifest with Instant.MAX validUntil verifies without DateTimeException")
    void perpetualManifestSucceeds() throws Exception {
        String manifest = windowManifest(VALID_FROM, Instant.MAX, 14);

        assertThat(verifyManifest(manifest, Instant.parse("2999-01-01T00:00:00Z")).validUntil())
                .isEqualTo(Instant.MAX);
    }
}
