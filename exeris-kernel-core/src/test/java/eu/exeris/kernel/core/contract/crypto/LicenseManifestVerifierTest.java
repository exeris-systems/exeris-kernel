/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.contract.crypto;

import eu.exeris.kernel.spi.contract.EnforcementLevel;
import eu.exeris.kernel.spi.contract.ExecutionContract;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.contract.ContractBreachException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LicenseManifestVerifier")
class LicenseManifestVerifierTest {

    private static final String TEST_KEY_ID = "test-issuer-k1";
    private static final Instant FIXED_NOW = Instant.parse("2026-10-06T12:00:00Z");

    private KeyPair keyPair;
    private IssuerKeyResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        keyPair = kpg.generateKeyPair();
        resolver = IssuerKeyResolver.of(Map.of(TEST_KEY_ID, keyPair.getPublic()));
    }

    private ExecutionContract verify(String manifest, Instant now) {
        return LicenseManifestVerifier.verify(manifest, now, resolver);
    }

    private String createSignedManifest(
            String keyId,
            String edition,
            Instant validFrom,
            Instant validUntil,
            int graceDays,
            EnforcementLevel capabilityLevel,
            long maxThroughputRps
    ) throws Exception {
        String unsignedJson = String.format(
                "{\"$schema\":\"https://specs.exeris.eu/schema/v1/license-manifest.json\","
                        + "\"issuer\":{\"keyId\":\"%s\",\"issuedAt\":\"2026-01-01T00:00:00Z\"},"
                        + "\"contract\":{\"id\":\"EXR-TEST-001\",\"commercialModel\":\"STANDARD\"},"
                        + "\"entitlement\":{\"edition\":\"%s\",\"sku\":\"exeris-sku-default\","
                        + "\"licenseMode\":\"SUBSCRIPTION\",\"capabilities\":[\"ha-cluster\",\"tls-offload\"]},"
                        + "\"execution\":{\"environments\":[\"production\",\"staging\"],\"authorizedInstances\":10,"
                        + "\"validFrom\":\"%s\",\"validUntil\":\"%s\",\"gracePeriodDays\":%d,"
                        + "\"workloadEnvelope\":{\"maxThroughputRps\":%d,\"maxConnections\":500,"
                        + "\"growthAllowancePercent\":10}},"
                        + "\"enforcementRules\":{\"capability\":\"%s\",\"environment\":\"HARD\"}}",
                keyId,
                edition,
                validFrom,
                validUntil,
                graceDays,
                maxThroughputRps,
                capabilityLevel.name()
        );

        byte[] canonical = JsonCanonicalizer.canonicalize(unsignedJson);
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(keyPair.getPrivate());
        sig.update(canonical);
        String sigBase64 = Base64.getEncoder().encodeToString(sig.sign());

        return String.format(
                "{\"$schema\":\"https://specs.exeris.eu/schema/v1/license-manifest.json\","
                        + "\"issuer\":{\"keyId\":\"%s\",\"issuedAt\":\"2026-01-01T00:00:00Z\"},"
                        + "\"contract\":{\"id\":\"EXR-TEST-001\",\"commercialModel\":\"STANDARD\"},"
                        + "\"entitlement\":{\"edition\":\"%s\",\"sku\":\"exeris-sku-default\","
                        + "\"licenseMode\":\"SUBSCRIPTION\",\"capabilities\":[\"ha-cluster\",\"tls-offload\"]},"
                        + "\"execution\":{\"environments\":[\"production\",\"staging\"],\"authorizedInstances\":10,"
                        + "\"validFrom\":\"%s\",\"validUntil\":\"%s\",\"gracePeriodDays\":%d,"
                        + "\"workloadEnvelope\":{\"maxThroughputRps\":%d,\"maxConnections\":500,"
                        + "\"growthAllowancePercent\":10}},"
                        + "\"enforcementRules\":{\"capability\":\"%s\",\"environment\":\"HARD\"},"
                        + "\"signature\":{\"algorithm\":\"Ed25519\",\"canonicalization\":\"RFC-8785\",\"value\":\"%s\"}}",
                keyId,
                edition,
                validFrom,
                validUntil,
                graceDays,
                maxThroughputRps,
                capabilityLevel.name(),
                sigBase64
        );
    }

    @Test
    @DisplayName("Successfully verifies valid Ed25519 signed manifest")
    void verifiesValidManifest() throws Exception {
        Instant now = FIXED_NOW;
        Instant validFrom = now.minus(5, ChronoUnit.DAYS);
        Instant validUntil = now.plus(30, ChronoUnit.DAYS);

        String manifest = createSignedManifest(
                TEST_KEY_ID, "enterprise", validFrom, validUntil, 7, EnforcementLevel.HARD, 10000L
        );

        ExecutionContract contract = verify(manifest, now);

        assertThat(contract).isNotNull();
        assertThat(contract.contractId()).isEqualTo("EXR-TEST-001");
        assertThat(contract.edition()).isEqualTo("enterprise");
        assertThat(contract.envelope().maxConnections()).isEqualTo(500);
        assertThat(contract.envelope().maxThroughputRps()).isEqualTo(10000L);
        assertThat(contract.getEnforcement("capability")).isEqualTo(EnforcementLevel.HARD);
        assertThat(contract.allowsCapability("ha-cluster")).isTrue();
        assertThat(contract.allowsCapability("tls-offload")).isTrue();
        assertThat(contract.allowsCapability("unlicensed_feature")).isFalse();
        assertThat(contract.isEnvironmentAuthorized("production")).isTrue();
        assertThat(contract.isEnvironmentAuthorized("development")).isFalse();

        assertThatCode(() -> contract.assertCapability("ha-cluster")).doesNotThrowAnyException();
        assertThatThrownBy(() -> contract.assertCapability("unlicensed_feature"))
                .isInstanceOf(ContractBreachException.class)
                .satisfies(e -> assertThat(((ContractBreachException) e).errorCode())
                        .isEqualTo(KernelErrorCodes.EX_LIC_0004));
    }

    @Test
    @DisplayName("Detects tampering and throws EX_LIC_0001")
    void detectsTampering() throws Exception {
        Instant now = FIXED_NOW;
        String manifest = createSignedManifest(
                TEST_KEY_ID, "enterprise", now.minus(1, ChronoUnit.DAYS), now.plus(10, ChronoUnit.DAYS),
                7, EnforcementLevel.HARD, 10000L
        );

        // Tamper with maxThroughputRps: 10000 -> 99999
        String tampered = manifest.replace("\"maxThroughputRps\":10000", "\"maxThroughputRps\":99999");

        assertThatThrownBy(() -> verify(tampered, now))
                .isInstanceOf(ContractBreachException.class)
                .satisfies(e -> assertThat(((ContractBreachException) e).errorCode())
                        .isEqualTo(KernelErrorCodes.EX_LIC_0001));
    }

    @Test
    @DisplayName("Throws EX_LIC_0002 when issuer keyId is untrusted")
    void rejectsUntrustedKeyId() throws Exception {
        Instant now = FIXED_NOW;
        String manifest = createSignedManifest(
                "unknown-key-999", "enterprise", now.minus(1, ChronoUnit.DAYS),
                now.plus(10, ChronoUnit.DAYS), 7, EnforcementLevel.HARD, 10000L
        );

        assertThatThrownBy(() -> verify(manifest, now))
                .isInstanceOf(ContractBreachException.class)
                .satisfies(e -> {
                    ContractBreachException breach = (ContractBreachException) e;
                    assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0002);
                    assertThat(breach.rawArgs()).containsExactly("unknown-key-999");
                    assertThat(breach.getMessage()).doesNotContain("unknown-key-999");
                });
    }

    @Test
    @DisplayName("Throws EX_LIC_0003 when manifest is expired past grace period")
    void rejectsExpiredManifest() throws Exception {
        Instant now = FIXED_NOW;
        Instant validFrom = now.minus(30, ChronoUnit.DAYS);
        Instant validUntil = now.minus(10, ChronoUnit.DAYS); // Expired 10 days ago
        int graceDays = 7; // Grace period was 7 days -> expired 3 days past grace

        String manifest = createSignedManifest(
                TEST_KEY_ID, "enterprise", validFrom, validUntil, graceDays, EnforcementLevel.HARD, 10000L
        );

        assertThatThrownBy(() -> verify(manifest, now))
                .isInstanceOf(ContractBreachException.class)
                .satisfies(e -> assertThat(((ContractBreachException) e).errorCode())
                        .isEqualTo(KernelErrorCodes.EX_LIC_0003));
    }

    @Test
    @DisplayName("Allows expired manifest within grace period")
    void allowsExpiredWithinGracePeriod() throws Exception {
        Instant now = FIXED_NOW;
        Instant validFrom = now.minus(30, ChronoUnit.DAYS);
        Instant validUntil = now.minus(2, ChronoUnit.DAYS); // Expired 2 days ago
        int graceDays = 7; // Grace period 7 days -> within grace

        String manifest = createSignedManifest(
                TEST_KEY_ID, "enterprise", validFrom, validUntil, graceDays, EnforcementLevel.HARD, 10000L
        );

        ExecutionContract contract = verify(manifest, now);
        assertThat(contract).isNotNull();
        assertThat(contract.contractId()).isEqualTo("EXR-TEST-001");
    }

    @Test
    @DisplayName("Throws EX_LIC_0006 when manifest is not yet valid (future validFrom)")
    void rejectsFutureManifest() throws Exception {
        Instant now = FIXED_NOW;
        Instant validFrom = now.plus(5, ChronoUnit.DAYS);
        Instant validUntil = now.plus(30, ChronoUnit.DAYS);

        String manifest = createSignedManifest(
                TEST_KEY_ID, "enterprise", validFrom, validUntil, 0, EnforcementLevel.HARD, 10000L
        );

        assertThatThrownBy(() -> verify(manifest, now))
                .isInstanceOf(ContractBreachException.class)
                .satisfies(e -> assertThat(((ContractBreachException) e).errorCode())
                        .isEqualTo(KernelErrorCodes.EX_LIC_0006));
    }

    @Test
    @DisplayName("Perpetual license with Instant.MAX validUntil verifies without DateTimeException overflow")
    void perpetualLicenseDoesNotOverflow() throws Exception {
        Instant now = FIXED_NOW;
        String manifest = createSignedManifest(
                TEST_KEY_ID, "enterprise", now.minus(1, ChronoUnit.DAYS), Instant.MAX,
                14, EnforcementLevel.HARD, 10000L
        );

        ExecutionContract contract = verify(manifest, now);
        assertThat(contract).isNotNull();
        assertThat(contract.validUntil()).isEqualTo(Instant.MAX);
    }

    // ── v1 schema (ADR-088 §1): every violation is rejected, none is coerced ─

    private static final Instant SCHEMA_NOW = Instant.parse("2026-10-06T12:00:00Z");
    private static final String VALID_V1 = "{\"$schema\":\"https://specs.exeris.eu/schema/v1/license-manifest.json\","
            + "\"contract\":{\"id\":\"EXR-SCHEMA-001\",\"commercialModel\":\"STANDARD\"},"
            + "\"entitlement\":{\"edition\":\"enterprise\",\"sku\":\"exeris-sku-test\",\"licenseMode\":\"SUBSCRIPTION\","
            + "\"capabilities\":[\"gateway.core\",\"security.bot-fingerprinting\"]},"
            + "\"execution\":{\"environments\":[\"production\"],\"authorizedInstances\":5,"
            + "\"validFrom\":\"2026-01-01T00:00:00Z\",\"validUntil\":\"2027-01-01T00:00:00Z\",\"gracePeriodDays\":14,"
            + "\"workloadEnvelope\":{\"maxThroughputRps\":200000,\"maxConnections\":50000,\"growthAllowancePercent\":20}},"
            + "\"enforcementRules\":{\"capability\":\"HARD\",\"environment\":\"HARD\","
            + "\"authorizedInstances\":\"SOFT\",\"workloadEnvelope\":\"AUDIT\"},"
            + "\"issuer\":{\"authority\":\"Exeris License Issuer CA\",\"keyId\":\"test-issuer-k1\","
            + "\"issuedAt\":\"2026-01-01T00:00:00Z\"}}";

    private String sign(String unsignedJson, String signatureFields) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(JsonCanonicalizer.canonicalize(unsignedJson));
        String value = Base64.getEncoder().encodeToString(signer.sign());
        return unsignedJson.substring(0, unsignedJson.length() - 1)
                + ",\"signature\":{" + signatureFields + "\"value\":\"" + value + "\"}}";
    }

    private String sign(String unsignedJson) throws Exception {
        return sign(unsignedJson, "\"algorithm\":\"Ed25519\",\"canonicalization\":\"RFC-8785\",");
    }

    @Test
    @DisplayName("The schema fixture itself verifies, so each rejection below is caused by its one change")
    void schemaFixtureVerifies() throws Exception {
        ExecutionContract contract = verify(sign(VALID_V1), SCHEMA_NOW);

        assertThat(contract.contractId()).isEqualTo("EXR-SCHEMA-001");
        assertThat(contract.envelope().maxThroughputRps()).isEqualTo(200_000L);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "$schema v2              | /v1/                                       | /v2/                                                 | $schema must be exactly",
            "unknown top-level field | \"issuer\":{                              | \"extra\":1,\"issuer\":{                              | Unknown top-level field 'extra'",
            "commercialModel case    | \"STANDARD\"                               | \"standard\"                                         | contract.commercialModel",
            "edition case            | \"edition\":\"enterprise\"                 | \"edition\":\"Enterprise\"                           | entitlement.edition",
            "licenseMode case        | \"SUBSCRIPTION\"                           | \"subscription\"                                     | entitlement.licenseMode",
            "capability not kebab    | \"gateway.core\"                           | \"Gateway Core\"                                     | not a lowercase kebab-case identifier",
            "capability underscore   | \"gateway.core\"                           | \"gateway_core\"                                     | not a lowercase kebab-case identifier",
            "capability twice        | [\"gateway.core\",                         | [\"gateway.core\",\"gateway.core\",                  | twice",
            "capabilities not array  | [\"gateway.core\",\"security.bot-fingerprinting\"] | \"gateway.core\"                             | entitlement.capabilities must be an array",
            "environment unknown     | [\"production\"]                           | [\"moon\"]                                           | execution.environments: 'moon'",
            "environment test        | [\"production\"]                           | [\"test\"]                                           | execution.environments: 'test'",
            "environments empty      | [\"production\"]                           | []                                                   | at least one environment",
            "instances wrap past int | \"authorizedInstances\":5                  | \"authorizedInstances\":4294967297                   | execution.authorizedInstances",
            "instances fraction      | \"authorizedInstances\":5                  | \"authorizedInstances\":1.9                          | execution.authorizedInstances",
            "instances as string     | \"authorizedInstances\":5                  | \"authorizedInstances\":\"7\"                        | execution.authorizedInstances",
            "instances not a number  | \"authorizedInstances\":5                  | \"authorizedInstances\":\"abc\"                      | execution.authorizedInstances",
            "instances negative      | \"authorizedInstances\":5                  | \"authorizedInstances\":-1                           | execution.authorizedInstances",
            "instances missing       | \"authorizedInstances\":5,                 | ``                                                   | execution.authorizedInstances",
            "grace past int          | \"gracePeriodDays\":14                     | \"gracePeriodDays\":1e10                             | execution.gracePeriodDays",
            "connections past int    | \"maxConnections\":50000                   | \"maxConnections\":2147483648                        | maxConnections",
            "rps past 2^53           | \"maxThroughputRps\":200000                | \"maxThroughputRps\":9007199254740994                | maxThroughputRps",
            "validUntil missing      | ,\"validUntil\":\"2027-01-01T00:00:00Z\"   | ``                                                   | validUntil is required",
            "validFrom missing       | \"validFrom\":\"2026-01-01T00:00:00Z\",    | ``                                                   | execution.validFrom",
            "validUntil before from  | \"validUntil\":\"2027-01-01T00:00:00Z\"    | \"validUntil\":\"2025-01-01T00:00:00Z\"              | precedes",
            "unknown level           | \"capability\":\"HARD\"                    | \"capability\":\"HARDD\"                             | must be HARD, SOFT or AUDIT",
            "lowercase level         | \"capability\":\"HARD\"                    | \"capability\":\"hard\"                              | must be HARD, SOFT or AUDIT",
            "unknown constraint      | \"workloadEnvelope\":\"AUDIT\"}            | \"workloadEnvelope\":\"AUDIT\",\"bandwidth\":\"HARD\"} | unknown constraint 'bandwidth'",
            "issuedAt not instant    | \"issuedAt\":\"2026-01-01T00:00:00Z\"      | \"issuedAt\":\"yesterday\"                           | issuer.issuedAt",
            "contract id not string  | \"id\":\"EXR-SCHEMA-001\"                  | \"id\":42                                            | contract.id"
    })
    @DisplayName("A validly signed manifest that breaks the v1 schema fails with EX-LIC-0001 naming the rule")
    void rejectsSchemaViolation(String rule, String find, String replacement, String reason) throws Exception {
        assertThat(VALID_V1).as("fixture must contain the text the case replaces").contains(find);
        String manifest = sign(VALID_V1.replaceFirst(java.util.regex.Pattern.quote(find),
                java.util.regex.Matcher.quoteReplacement(replacement)));

        assertThatThrownBy(() -> verify(manifest, SCHEMA_NOW))
                .as(rule)
                .isInstanceOf(ContractBreachException.class)
                .hasMessageContaining(reason)
                .satisfies(e -> {
                    ContractBreachException breach = (ContractBreachException) e;
                    assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0001);
                    assertThat(breach.rawArgs()).isEmpty();
                });
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "issuer block missing | ,\"issuer\":{\"authority\":\"Exeris License Issuer CA\",\"keyId\":\"test-issuer-k1\",\"issuedAt\":\"2026-01-01T00:00:00Z\"} | ``",
            "keyId blank          | \"keyId\":\"test-issuer-k1\"  | \"keyId\":\" \"",
            "keyId not a string   | \"keyId\":\"test-issuer-k1\"  | \"keyId\":7"
    })
    @DisplayName("A missing issuer block or keyId is a schema violation: EX-LIC-0001, no rawArgs")
    void missingIssuerIsSchemaViolation(String rule, String find, String replacement) throws Exception {
        assertThat(VALID_V1).contains(find);
        String manifest = sign(VALID_V1.replace(find, replacement));

        assertThatThrownBy(() -> verify(manifest, SCHEMA_NOW))
                .as(rule)
                .isInstanceOf(ContractBreachException.class)
                .satisfies(e -> {
                    ContractBreachException breach = (ContractBreachException) e;
                    assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0001);
                    assertThat(breach.rawArgs()).isEmpty();
                });
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "not Base64     | %%%not-base64%%%",
            "63-byte value  | 63",
            "65-byte value  | 65"
    })
    @DisplayName("A malformed or wrong-length signature value fails with EX-LIC-0001, never a raw exception")
    void malformedSignatureValue(String rule, String value) throws Exception {
        String signed = sign(VALID_V1);
        String encoded = value.chars().allMatch(Character::isDigit)
                ? Base64.getEncoder().encodeToString(new byte[Integer.parseInt(value)])
                : value;
        String manifest = signed.replaceFirst("\"value\":\"[^\"]+\"", "\"value\":\"" + encoded + "\"");

        assertThatThrownBy(() -> verify(manifest, SCHEMA_NOW))
                .as(rule)
                .isExactlyInstanceOf(ContractBreachException.class)
                .satisfies(e -> assertThat(((ContractBreachException) e).errorCode())
                        .isEqualTo(KernelErrorCodes.EX_LIC_0001));
    }

    @Test
    @DisplayName("A signature that does not verify fails with EX-LIC-0001 and no rawArgs")
    void failedSignatureCarriesNoRawArgs() throws Exception {
        String tampered = sign(VALID_V1).replace("\"exeris-sku-test\"", "\"exeris-sku-tesu\"");

        assertThatThrownBy(() -> verify(tampered, SCHEMA_NOW))
                .isInstanceOf(ContractBreachException.class)
                .hasMessageContaining("signature verification failed")
                .satisfies(e -> assertThat(((ContractBreachException) e).rawArgs()).isEmpty());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "algorithm case          | \"algorithm\":\"ed25519\",\"canonicalization\":\"RFC-8785\", | signature.algorithm",
            "canonicalization absent | \"algorithm\":\"Ed25519\",                                 | signature.canonicalization",
            "canonicalization case   | \"algorithm\":\"Ed25519\",\"canonicalization\":\"rfc-8785\", | signature.canonicalization"
    })
    @DisplayName("The signature block's algorithm and canonicalization must match exactly")
    void rejectsSignatureBlockVariant(String rule, String signatureFields, String reason) throws Exception {
        String manifest = sign(VALID_V1, signatureFields);

        assertThatThrownBy(() -> verify(manifest, SCHEMA_NOW))
                .as(rule)
                .isInstanceOf(ContractBreachException.class)
                .hasMessageContaining(reason);
    }

    @Test
    @DisplayName("Omitted enforcement rules take ADR-089's levels, never AUDIT for capability or environment")
    void omittedEnforcementRulesFailClosed() throws Exception {
        String withoutRules = VALID_V1.replaceFirst(
                "\"enforcementRules\":\\{[^}]*},", "");
        assertThat(withoutRules).doesNotContain("enforcementRules");

        ExecutionContract contract = verify(sign(withoutRules), SCHEMA_NOW);

        assertThat(contract.getEnforcement("capability")).isEqualTo(EnforcementLevel.HARD);
        assertThat(contract.getEnforcement("environment")).isEqualTo(EnforcementLevel.HARD);
        assertThat(contract.getEnforcement("authorizedInstances")).isEqualTo(EnforcementLevel.SOFT);
        assertThat(contract.getEnforcement("workloadEnvelope")).isEqualTo(EnforcementLevel.AUDIT);
        assertThatThrownBy(() -> contract.assertEnvironment("staging"))
                .isInstanceOf(ContractBreachException.class);
    }

    @Test
    @DisplayName("Inside the grace period the manifest verifies and a SOFT gracePeriod WARNING is logged")
    void gracePeriodLogsWarning() throws Exception {
        String manifest = sign(VALID_V1);
        Instant insideGrace = Instant.parse("2027-01-05T00:00:00Z");

        List<java.util.logging.LogRecord> warnings =
                eu.exeris.kernel.core.contract.jfr.ContractViolationReporterTest.captureWarnings(
                        () -> verify(manifest, insideGrace));

        assertThat(warnings).singleElement()
                .satisfies(r -> assertThat(r.getParameters()).contains("EXR-SCHEMA-001", "gracePeriod"));
    }

    @Test
    @DisplayName("PERPETUAL_INTERNAL may omit validUntil, and then never expires")
    void perpetualMayOmitValidUntil() throws Exception {
        String perpetual = VALID_V1
                .replace("\"SUBSCRIPTION\"", "\"PERPETUAL_INTERNAL\"")
                .replace(",\"validUntil\":\"2027-01-01T00:00:00Z\"", "");

        ExecutionContract contract = verify(sign(perpetual), Instant.parse("2090-01-01T00:00:00Z"));

        assertThat(contract.validUntil()).isEqualTo(Instant.MAX);
    }

    @Test
    @DisplayName("Fields read are fields signed: a value the canonical form cannot carry is rejected, not mapped")
    void loneSurrogateInSignedFieldIsRejected() throws Exception {
        String signedWithQuestionMark = sign(VALID_V1.replace("\"gateway.core\"", "\"gateway.core?\""));
        String swapped = signedWithQuestionMark.replace("gateway.core?", "gateway.core\\ud800");

        assertThatThrownBy(() -> verify(swapped, SCHEMA_NOW))
                .isInstanceOf(ContractBreachException.class)
                .hasMessageContaining("Unpaired surrogate");
    }

    @Test
    @DisplayName("Future validFrom breach carries [contractId, validFrom]")
    void futureValidFromAdheresToGlassBoxSchema() throws Exception {
        Instant now = FIXED_NOW;
        Instant validFrom = now.plus(5, ChronoUnit.DAYS);
        Instant validUntil = now.plus(30, ChronoUnit.DAYS);

        String manifest = createSignedManifest(
                TEST_KEY_ID, "enterprise", validFrom, validUntil, 0, EnforcementLevel.HARD, 10000L
        );

        assertThatThrownBy(() -> verify(manifest, now))
                .isInstanceOf(ContractBreachException.class)
                .satisfies(e -> {
                    ContractBreachException cbe = (ContractBreachException) e;
                    assertThat(cbe.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0006);
                    assertThat(cbe.rawArgs()).hasSize(2);
                    assertThat(cbe.rawArgs()[0]).isEqualTo("EXR-TEST-001");
                    assertThat(cbe.rawArgs()[1]).isEqualTo(validFrom);
                });
    }
}
