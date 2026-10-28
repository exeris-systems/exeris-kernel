/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.contract.crypto;

import eu.exeris.kernel.spi.contract.EntitlementClass;
import eu.exeris.kernel.spi.contract.ExecutionEnvironment;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.contract.ContractBreachException;
import eu.exeris.kernel.spi.time.TimeKernelProviders;
import eu.exeris.kernel.spi.time.TimeSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * What a manifest may say is bounded by the scope of the key that signed it. Every manifest here is signed by
 * a key the test generates and is verified through {@link IssuerKeyResolver#ofScoped(Map)}, so the cases
 * read the scope rules and no pinned key.
 */
@DisplayName("LicenseManifestVerifier: issuer key scope")
class LicenseManifestScopeTest {

    private static final String DEV = "scope-dev-k1";
    private static final String PROD = "scope-prod-k1";
    private static final String ENT = "scope-ent-k1";
    private static final Instant ISSUED = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant NOW = ISSUED.plus(Duration.ofDays(1));

    private KeyPair keyPair;
    private IssuerKeyResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        resolver = IssuerKeyResolver.ofScoped(Map.of(
                DEV, new TrustedIssuerKey(DEV, keyPair.getPublic(), IssuerKeyScope.DEV),
                PROD, new TrustedIssuerKey(PROD, keyPair.getPublic(), IssuerKeyScope.PROD),
                ENT, new TrustedIssuerKey(ENT, keyPair.getPublic(), IssuerKeyScope.ENT)));
    }

    // ── Fixture ─────────────────────────────────────────────────────────────

    /** A manifest under construction; the defaults are a conforming developer licence. */
    private static final class Spec {
        private String keyId = DEV;
        private List<String> environments = List.of("development");
        private String issuedAt = ISSUED.toString();
        private String validFrom = ISSUED.toString();
        private String validUntil = ISSUED.plus(Duration.ofDays(16)).toString();
        private String licenseMode = "SUBSCRIPTION";
        private Integer graceDays;
        private boolean envelope = true;
        private Long rps = 50L;
        private Integer connections = 10;
        private Integer growth = 0;
        private final Map<String, String> rules = new LinkedHashMap<>(Map.of(
                "capability", "HARD", "environment", "HARD"));

        Spec key(String id) {
            this.keyId = id;
            return this;
        }

        Spec environments(String... ids) {
            this.environments = List.of(ids);
            return this;
        }

        Spec issuedAt(Instant instant) {
            this.issuedAt = instant == null ? null : instant.toString();
            return this;
        }

        Spec validFrom(Instant instant) {
            this.validFrom = instant.toString();
            return this;
        }

        Spec validUntil(Instant instant) {
            this.validUntil = instant == null ? null : instant.toString();
            return this;
        }

        Spec perpetual() {
            this.licenseMode = "PERPETUAL_INTERNAL";
            return this;
        }

        Spec grace(Integer days) {
            this.graceDays = days;
            return this;
        }

        Spec noEnvelope() {
            this.envelope = false;
            return this;
        }

        Spec rps(Long value) {
            this.rps = value;
            return this;
        }

        Spec connections(Integer value) {
            this.connections = value;
            return this;
        }

        Spec growth(Integer value) {
            this.growth = value;
            return this;
        }

        Spec rule(String constraint, String level) {
            this.rules.put(constraint, level);
            return this;
        }

        Spec noRule(String constraint) {
            this.rules.remove(constraint);
            return this;
        }

        String unsigned() {
            StringBuilder execution = new StringBuilder("\"execution\":{\"environments\":")
                    .append(array(environments)).append(",\"authorizedInstances\":3,\"validFrom\":\"")
                    .append(validFrom).append('"');
            if (validUntil != null) {
                execution.append(",\"validUntil\":\"").append(validUntil).append('"');
            }
            if (graceDays != null) {
                execution.append(",\"gracePeriodDays\":").append(graceDays);
            }
            if (envelope) {
                List<String> parts = new ArrayList<>();
                if (rps != null) {
                    parts.add("\"maxThroughputRps\":" + rps);
                }
                if (connections != null) {
                    parts.add("\"maxConnections\":" + connections);
                }
                if (growth != null) {
                    parts.add("\"growthAllowancePercent\":" + growth);
                }
                execution.append(",\"workloadEnvelope\":{").append(String.join(",", parts)).append('}');
            }
            execution.append('}');
            List<String> ruleParts = new ArrayList<>();
            rules.forEach((constraint, level) -> ruleParts.add("\"" + constraint + "\":\"" + level + "\""));
            return "{\"$schema\":\"" + LicenseManifestVerifier.SCHEMA_V1 + "\","
                    + "\"contract\":{\"id\":\"EXR-SCOPE-1\",\"commercialModel\":\"STANDARD\"},"
                    + "\"entitlement\":{\"edition\":\"commercial\",\"sku\":\"exeris-sku-test\","
                    + "\"licenseMode\":\"" + licenseMode + "\",\"capabilities\":[\"gateway-core\"]},"
                    + execution + ","
                    + "\"enforcementRules\":{" + String.join(",", ruleParts) + "},"
                    + "\"issuer\":{\"keyId\":\"" + keyId + "\""
                    + (issuedAt == null ? "" : ",\"issuedAt\":\"" + issuedAt + "\"") + "}}";
        }

        private static String array(List<String> values) {
            return values.stream().map(v -> "\"" + v + "\"").collect(Collectors.joining(",", "[", "]"));
        }
    }

    private String sign(Spec spec) throws Exception {
        String unsigned = spec.unsigned();
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(JsonCanonicalizer.canonicalize(unsigned));
        return unsigned.substring(0, unsigned.length() - 1)
                + ",\"signature\":{\"algorithm\":\"Ed25519\",\"canonicalization\":\"RFC-8785\","
                + "\"value\":\"" + Base64.getEncoder().encodeToString(signer.sign()) + "\"}}";
    }

    private LicenseManifestVerifier.VerifiedManifest verify(Spec spec) throws Exception {
        return LicenseManifestVerifier.verifyScoped(sign(spec), NOW, resolver);
    }

    private ContractBreachException refusal(Spec spec) throws Exception {
        String manifest = sign(spec);
        return (ContractBreachException) catchThrowable(
                () -> LicenseManifestVerifier.verifyScoped(manifest, NOW, resolver));
    }

    private void assertRefused(Spec spec, String code) throws Exception {
        ContractBreachException breach = refusal(spec);
        assertThat(breach).as("the manifest must be refused with %s", code).isNotNull();
        assertThat(breach.errorCode()).isEqualTo(code);
    }

    // ── Environments ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Environments (EX-LIC-0009)")
    class Environments {

        @Test
        @DisplayName("A developer key authorizes development")
        void devAuthorizesDevelopment() throws Exception {
            assertThat(verify(new Spec()).contract().authorizedEnvironments()).containsExactly("development");
        }

        @Test
        @DisplayName("A developer manifest that also lists staging is refused, naming the key and the environment")
        void devListingStagingIsRefused() throws Exception {
            ContractBreachException breach = refusal(new Spec().environments("development", "staging"));

            assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0009);
            assertThat(breach.rawArgs()).containsExactly(DEV, List.of("staging"));
        }

        @Test
        @DisplayName("The environments outside the scope are listed sorted")
        void outsideEnvironmentsAreSorted() throws Exception {
            ContractBreachException breach = refusal(new Spec().environments("staging", "production", "development"));

            assertThat(breach.rawArgs()).containsExactly(DEV, List.of("production", "staging"));
        }

        @Test
        @DisplayName("A production key and an enterprise key authorize all six environments")
        void prodAndEntAuthorizeEverything() throws Exception {
            for (String key : List.of(PROD, ENT)) {
                Spec all = new Spec().key(key).environments("development", "staging", "production",
                        "production-load-sim", "dr-cold", "dr-hot").noEnvelope().rps(null);
                assertThat(verify(all).contract().authorizedEnvironments()).hasSize(6);
            }
        }
    }

    // ── Validity ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Validity (EX-LIC-0010)")
    class Validity {

        @Test
        @DisplayName("Sixteen days plus the default fourteen days of grace is exactly thirty days and passes")
        void exactlyThirtyDaysPasses() throws Exception {
            assertThat(verify(new Spec()).contract().validUntil()).isEqualTo(ISSUED.plus(Duration.ofDays(16)));
        }

        @Test
        @DisplayName("One second more than thirty days is refused, with the last boot instant in the arguments")
        void oneSecondOverIsRefused() throws Exception {
            Instant validUntil = ISSUED.plus(Duration.ofDays(16)).plusSeconds(1);
            ContractBreachException breach = refusal(new Spec().validUntil(validUntil));

            assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0010);
            assertThat(breach.rawArgs()).containsExactly(DEV, ISSUED, validUntil.plus(Duration.ofDays(14)), 30L);
        }

        @Test
        @DisplayName("With no grace period the limit is thirty days to validUntil, and one second more is refused")
        void zeroGraceBoundary() throws Exception {
            Instant limit = ISSUED.plus(Duration.ofDays(30));

            assertThat(verify(new Spec().grace(0).validUntil(limit)).contract().gracePeriodDays()).isZero();
            assertRefused(new Spec().grace(0).validUntil(limit.plusSeconds(1)), KernelErrorCodes.EX_LIC_0010);
        }

        @Test
        @DisplayName("The span is measured from issuedAt, not from validFrom")
        void spanStartsAtIssuedAt() throws Exception {
            Instant validFrom = ISSUED.plus(Duration.ofDays(10));
            Instant duringValidity = validFrom.plus(Duration.ofDays(1));

            Spec withinFromIssuedAt = new Spec().validFrom(validFrom).grace(0)
                    .validUntil(ISSUED.plus(Duration.ofDays(30)));
            assertThat(LicenseManifestVerifier.verifyScoped(sign(withinFromIssuedAt), duringValidity, resolver)
                    .contract().validFrom()).isEqualTo(validFrom);

            Spec withinFromValidFromOnly = new Spec().validFrom(validFrom).grace(0)
                    .validUntil(ISSUED.plus(Duration.ofDays(35)));
            assertRefused(withinFromValidFromOnly, KernelErrorCodes.EX_LIC_0010);
        }

        @Test
        @DisplayName("A manifest without issuedAt is refused, with a null issuedAt in the arguments")
        void missingIssuedAtIsRefused() throws Exception {
            ContractBreachException breach = refusal(new Spec().issuedAt(null));

            assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0010);
            assertThat(breach.rawArgs()[1]).isNull();
            assertThat(breach.getMessage()).contains("issuer.issuedAt");
        }

        @Test
        @DisplayName("validFrom one second before issuedAt is refused; at issuedAt it passes")
        void validFromBeforeIssuedAtIsRefused() throws Exception {
            assertRefused(new Spec().validFrom(ISSUED.minusSeconds(1)), KernelErrorCodes.EX_LIC_0010);
            assertThat(verify(new Spec().validFrom(ISSUED)).issuedAt()).isEqualTo(ISSUED);
        }

        @Test
        @DisplayName("PERPETUAL_INTERNAL without validUntil is refused under a developer key")
        void perpetualWithoutEndIsRefused() throws Exception {
            ContractBreachException breach = refusal(new Spec().perpetual().validUntil(null));

            assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0010);
            assertThat((Instant) breach.rawArgs()[2]).isEqualTo(Instant.MAX);
            assertThat(breach.getMessage()).contains("PERPETUAL_INTERNAL");
        }

        @Test
        @DisplayName("PERPETUAL_INTERNAL is refused under a developer key even with a validUntil")
        void perpetualWithEndIsRefused() throws Exception {
            assertRefused(new Spec().perpetual(), KernelErrorCodes.EX_LIC_0010);
        }

        @Test
        @DisplayName("A production key accepts PERPETUAL_INTERNAL with no end and no issuedAt limit")
        void prodAcceptsPerpetual() throws Exception {
            Spec perpetual = new Spec().key(PROD).perpetual().validUntil(null).noEnvelope().rps(null);

            assertThat(verify(perpetual).contract().validUntil()).isEqualTo(Instant.MAX);
        }
    }

    // ── Ceiling ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Workload ceiling (EX-LIC-0011)")
    class Ceiling {

        @Test
        @DisplayName("The ceiling itself passes: 50 requests per second, 10 connections, no growth")
        void ceilingPasses() throws Exception {
            assertThat(verify(new Spec()).contract().envelope().maxThroughputRps()).isEqualTo(50L);
        }

        @Test
        @DisplayName("51 requests per second is refused, with the envelope as read in the arguments")
        void rpsOverIsRefused() throws Exception {
            ContractBreachException breach = refusal(new Spec().rps(51L));

            assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0011);
            assertThat(breach.rawArgs()).containsExactly(DEV, 51L, 10, 0);
        }

        @Test
        @DisplayName("11 connections and a growth allowance of 1 are each refused")
        void connectionsAndGrowthOverAreRefused() throws Exception {
            assertRefused(new Spec().connections(11), KernelErrorCodes.EX_LIC_0011);
            assertRefused(new Spec().growth(1), KernelErrorCodes.EX_LIC_0011);
        }

        @Test
        @DisplayName("An absent envelope, an absent rate and absent connections are refused as unlimited")
        void absenceReadsAsUnlimited() throws Exception {
            ContractBreachException none = refusal(new Spec().noEnvelope());
            assertThat(none.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0011);
            assertThat(none.rawArgs()).containsExactly(DEV, Long.MAX_VALUE, Integer.MAX_VALUE, 0);

            assertRefused(new Spec().rps(null), KernelErrorCodes.EX_LIC_0011);
            assertRefused(new Spec().connections(null), KernelErrorCodes.EX_LIC_0011);
        }

        @Test
        @DisplayName("An absent growth allowance reads as zero and passes")
        void absentGrowthPasses() throws Exception {
            assertThat(verify(new Spec().growth(null)).contract().envelope().growthAllowancePercent()).isZero();
        }

        @Test
        @DisplayName("A production key accepts 200000 requests per second")
        void prodHasNoCeiling() throws Exception {
            assertThat(verify(new Spec().key(PROD).rps(200_000L)).contract().envelope().maxThroughputRps())
                    .isEqualTo(200_000L);
        }
    }

    // ── Precedence and enforcement rules ────────────────────────────────────

    @Nested
    @DisplayName("Order of the checks and enforcement rules")
    class Precedence {

        @Test
        @DisplayName("A manifest out of scope that has also expired is a scope breach, not an expiry")
        void scopeBeatsTime() throws Exception {
            Spec expired = new Spec().environments("development", "staging")
                    .validUntil(ISSUED.plus(Duration.ofDays(1))).grace(0);
            String manifest = sign(expired);
            Instant afterEnd = ISSUED.plus(Duration.ofDays(5));

            assertThatThrownBy(() -> LicenseManifestVerifier.verifyScoped(manifest, afterEnd, resolver))
                    .isInstanceOfSatisfying(ContractBreachException.class,
                            breach -> assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0009));
            assertThatThrownBy(() -> LicenseManifestVerifier.verifyScoped(
                    sign(new Spec().validUntil(ISSUED.plus(Duration.ofDays(1))).grace(0)), afterEnd, resolver))
                    .isInstanceOfSatisfying(ContractBreachException.class,
                            breach -> assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0003));
        }

        @Test
        @DisplayName("A tampered out-of-scope manifest fails its signature first")
        void signatureBeatsScope() throws Exception {
            String tampered = sign(new Spec()).replace("\"development\"", "\"production\"");

            assertThatThrownBy(() -> LicenseManifestVerifier.verifyScoped(tampered, NOW, resolver))
                    .isInstanceOfSatisfying(ContractBreachException.class,
                            breach -> assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0001));
        }

        @Test
        @DisplayName("An unknown key is EX-LIC-0002 before any scope is read")
        void unknownKeyBeatsScope() throws Exception {
            assertRefused(new Spec().key("nobody-k1"), KernelErrorCodes.EX_LIC_0002);
        }

        @Test
        @DisplayName("Environment, validity and ceiling breaches win in that order")
        void scopeChecksKeepTheirOrder() throws Exception {
            Spec everythingWrong = new Spec().environments("production").issuedAt(null).rps(500L);
            assertRefused(everythingWrong, KernelErrorCodes.EX_LIC_0009);
            assertRefused(new Spec().issuedAt(null).rps(500L), KernelErrorCodes.EX_LIC_0010);
            assertRefused(new Spec().rps(500L), KernelErrorCodes.EX_LIC_0011);
        }

        @Test
        @DisplayName("An enforcement rule set to AUDIT does not soften an environment breach")
        void auditDoesNotSoftenTheScope() throws Exception {
            Spec audit = new Spec().environments("development", "production")
                    .rule("capability", "AUDIT").rule("environment", "AUDIT").rule("workloadEnvelope", "AUDIT");

            assertRefused(audit, KernelErrorCodes.EX_LIC_0009);
            assertRefused(new Spec().rps(51L).rule("environment", "AUDIT"), KernelErrorCodes.EX_LIC_0011);
        }

        @Test
        @DisplayName("Under a developer key a capability or environment rule below HARD is refused, with the level as read")
        void developerRulesMustBeHard() throws Exception {
            ContractBreachException capability = refusal(new Spec().rule("capability", "SOFT"));
            assertThat(capability.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0013);
            assertThat(capability.rawArgs()).containsExactly(DEV, "capability", "SOFT");

            ContractBreachException environment = refusal(new Spec().rule("environment", "AUDIT"));
            assertThat(environment.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0013);
            assertThat(environment.rawArgs()).containsExactly(DEV, "environment", "AUDIT");
        }

        @Test
        @DisplayName("Under a developer key HARD rules, explicit or omitted, pass")
        void developerHardRulesPass() throws Exception {
            assertThatCode(() -> verify(new Spec())).doesNotThrowAnyException();
            assertThatCode(() -> verify(new Spec().noRule("capability").noRule("environment")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Without a ceiling the capability and environment rules stay free: SOFT passes under a production key")
        void productionRulesAreFree() throws Exception {
            Spec soft = new Spec().key(PROD).noEnvelope().rps(null).rule("capability", "SOFT")
                    .rule("environment", "AUDIT");

            assertThatCode(() -> verify(soft)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Without a ceiling a HARD workload envelope is a schema violation, EX-LIC-0001")
        void hardEnvelopeUnderNonDeveloperKeyIsRefused() throws Exception {
            for (String key : List.of(PROD, ENT)) {
                ContractBreachException breach = refusal(
                        new Spec().key(key).noEnvelope().rps(null).rule("workloadEnvelope", "HARD"));

                assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0001);
                assertThat(breach.getMessage()).contains("workloadEnvelope");
            }
        }

        @Test
        @DisplayName("Without a ceiling SOFT and AUDIT workload envelope rules pass, and a developer key may set HARD")
        void envelopeLevelsOtherThanHard() throws Exception {
            for (String level : List.of("SOFT", "AUDIT")) {
                Spec prod = new Spec().key(PROD).noEnvelope().rps(null).rule("workloadEnvelope", level);
                assertThatCode(() -> verify(prod)).doesNotThrowAnyException();
            }
            assertThatCode(() -> verify(new Spec().rule("workloadEnvelope", "HARD"))).doesNotThrowAnyException();
        }
    }

    // ── Results and resolvers ───────────────────────────────────────────────

    @Nested
    @DisplayName("verifyScoped and the resolvers")
    class Results {

        @Test
        @DisplayName("verifyScoped returns the contract, the signing key with its scope and issuedAt")
        void verifiedManifestCarriesKeyAndIssuedAt() throws Exception {
            LicenseManifestVerifier.VerifiedManifest verified = verify(new Spec());

            assertThat(verified.contract().contractId()).isEqualTo("EXR-SCOPE-1");
            assertThat(verified.key().keyId()).isEqualTo(DEV);
            assertThat(verified.key().scope()).isEqualTo(IssuerKeyScope.DEV);
            assertThat(verified.issuedAt()).isEqualTo(ISSUED);
        }

        @Test
        @DisplayName("issuedAt is null when the manifest carries none and the key does not need it")
        void issuedAtIsNullWhenAbsent() throws Exception {
            Spec prod = new Spec().key(PROD).issuedAt(null).noEnvelope().rps(null);

            assertThat(verify(prod).issuedAt()).isNull();
        }

        @Test
        @DisplayName("verify enforces the scope as well, so no caller can skip it")
        void verifyEnforcesScope() throws Exception {
            String manifest = sign(new Spec().environments("development", "staging"));

            assertThatThrownBy(() -> LicenseManifestVerifier.verify(manifest, NOW, resolver))
                    .isInstanceOfSatisfying(ContractBreachException.class,
                            breach -> assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0009));
        }

        @Test
        @DisplayName("The convenience verification reads the instant of the kernel time source, not the platform clock")
        void convenienceVerificationReadsTheKernelTimeSource() throws Exception {
            String manifest = sign(new Spec());

            assertThat(ScopedValue.where(TimeKernelProviders.TIME_SOURCE, sourceAt(NOW))
                    .call(() -> LicenseManifestVerifier.verifyAtKernelTime(manifest, resolver)).contractId())
                    .isEqualTo("EXR-SCOPE-1");
            ContractBreachException expired = catchThrowableOfType(ContractBreachException.class,
                    () -> ScopedValue.where(TimeKernelProviders.TIME_SOURCE, sourceAt(Instant.parse("2030-01-01T00:00:00Z")))
                            .run(() -> LicenseManifestVerifier.verifyAtKernelTime(manifest, resolver)));
            assertThat(expired).isNotNull();
            assertThat(expired.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0003);
            ContractBreachException early = catchThrowableOfType(ContractBreachException.class,
                    () -> ScopedValue.where(TimeKernelProviders.TIME_SOURCE, sourceAt(ISSUED.minusSeconds(1)))
                            .run(() -> LicenseManifestVerifier.verifyAtKernelTime(manifest, resolver)));
            assertThat(early).isNotNull();
            assertThat(early.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0006);
        }

        private TimeSource sourceAt(Instant instant) {
            return new TimeSource() {
                @Override
                public long nanoTime() {
                    return 0L;
                }

                @Override
                public Instant wallTime() {
                    return instant;
                }
            };
        }

        @Test
        @DisplayName("IssuerKeyResolver.of gives every key the unrestricted enterprise scope")
        void plainResolverIsUnrestricted() throws Exception {
            IssuerKeyResolver plain = IssuerKeyResolver.of(Map.of(DEV, keyPair.getPublic()));

            assertThat(plain.resolve(DEV).scope()).isEqualTo(IssuerKeyScope.ENT);
            assertThat(LicenseManifestVerifier.verifyScoped(
                    sign(new Spec().environments("production").rps(5000L).issuedAt(null).perpetual()
                            .validUntil(null)),
                    NOW, plain).key().scope()).isEqualTo(IssuerKeyScope.ENT);
        }

        @Test
        @DisplayName("ofScoped refuses a key registered under another key's identifier and an unknown identifier")
        void scopedResolverGuards() {
            TrustedIssuerKey key = new TrustedIssuerKey(DEV, keyPair.getPublic(), IssuerKeyScope.DEV);

            assertThatThrownBy(() -> IssuerKeyResolver.ofScoped(Map.of("other", key)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> resolver.resolve("nobody-k1"))
                    .isInstanceOfSatisfying(ContractBreachException.class,
                            breach -> assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0002));
            assertThatThrownBy(() -> resolver.resolve(null)).isInstanceOf(ContractBreachException.class);
        }
    }

    // ── Dry run under each scope ────────────────────────────────────────────

    @Nested
    @DisplayName("An issuer's dry run: sign under a scope, verify it, and breach each limit of the scope")
    class DryRun {

        private static Spec conforming(IssuerKeyScope scope, String keyId) {
            Spec spec = new Spec().key(keyId);
            if (scope.isDeveloperScope()) {
                return spec;
            }
            return spec.environments("development", "staging", "production", "production-load-sim", "dr-cold",
                    "dr-hot").noEnvelope().rps(null).validUntil(ISSUED.plus(Duration.ofDays(400)));
        }

        static Stream<Arguments> scopes() {
            return Stream.of(
                    Arguments.of(DEV, IssuerKeyScope.DEV),
                    Arguments.of(PROD, IssuerKeyScope.PROD),
                    Arguments.of(ENT, IssuerKeyScope.ENT));
        }

        /** One breach of a limit the scope has: the mutation of a conforming spec and the code it earns. */
        record Breach(String name, Consumer<Spec> mutation, String code) {
        }

        static Stream<Arguments> breaches() {
            Breach environment = new Breach("environment", spec -> spec.environments("development", "staging"),
                    KernelErrorCodes.EX_LIC_0009);
            Breach validity = new Breach("validity",
                    spec -> spec.validUntil(ISSUED.plus(Duration.ofDays(31))), KernelErrorCodes.EX_LIC_0010);
            Breach ceiling = new Breach("ceiling", spec -> spec.rps(51L), KernelErrorCodes.EX_LIC_0011);
            Breach rules = new Breach("rule", spec -> spec.rule("capability", "SOFT"), KernelErrorCodes.EX_LIC_0013);
            return Stream.of(
                    Arguments.of(DEV, IssuerKeyScope.DEV, environment),
                    Arguments.of(DEV, IssuerKeyScope.DEV, validity),
                    Arguments.of(DEV, IssuerKeyScope.DEV, ceiling),
                    Arguments.of(DEV, IssuerKeyScope.DEV, rules));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("scopes")
        @DisplayName("A conforming manifest verifies under its scope")
        void conformingManifestVerifies(String keyId, IssuerKeyScope scope) throws Exception {
            LicenseManifestVerifier.VerifiedManifest verified = verify(conforming(scope, keyId));

            assertThat(verified.key().scope()).isEqualTo(scope);
        }

        @ParameterizedTest(name = "{0} / {2}")
        @MethodSource("breaches")
        @DisplayName("Each limit of the developer scope earns its own code")
        void breachEarnsItsCode(String keyId, IssuerKeyScope scope, Breach breach) throws Exception {
            Spec spec = conforming(scope, keyId);
            breach.mutation().accept(spec);

            assertRefused(spec, breach.code());
        }

        @Test
        @DisplayName("Production and enterprise scopes have no environment, validity or ceiling limit to breach")
        void unrestrictedScopesHaveNoLimits() {
            for (IssuerKeyScope scope : List.of(IssuerKeyScope.PROD, IssuerKeyScope.ENT)) {
                assertThat(scope.environments()).containsExactlyInAnyOrder(ExecutionEnvironment.values());
                assertThat(scope.maxValidity()).isNull();
                assertThat(scope.workloadCeiling()).isNull();
                assertThat(scope.isDeveloperScope()).isFalse();
            }
            assertThat(IssuerKeyScope.PROD.classes()).containsExactly(EntitlementClass.COMMERCIAL);
            assertThat(IssuerKeyScope.ENT.classes()).containsExactlyInAnyOrder(EntitlementClass.values());
            assertThat(IssuerKeyScope.DEV.classes()).isEqualTo(Set.of(EntitlementClass.COMMERCIAL));
        }
    }
}
