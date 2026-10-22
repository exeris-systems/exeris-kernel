/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.contract;

import eu.exeris.kernel.core.contract.crypto.IssuerKeyResolver;
import eu.exeris.kernel.core.contract.crypto.IssuerKeyScope;
import eu.exeris.kernel.core.contract.crypto.JsonCanonicalizer;
import eu.exeris.kernel.core.contract.crypto.TrustedIssuerKey;
import eu.exeris.kernel.core.contract.jfr.ContractViolationReporterTest;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.contract.EntitlementClass;
import eu.exeris.kernel.spi.contract.EntitlementRequirement;
import eu.exeris.kernel.spi.contract.ExecutionContract;
import eu.exeris.kernel.spi.contract.ExecutionEnvironment;
import eu.exeris.kernel.spi.contract.WorkloadAdmission;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.contract.ContractBreachException;
import eu.exeris.kernel.spi.time.TimeKernelProviders;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@DisplayName("ContractBootstrapStep — Phase 0 contract gate")
class ContractBootstrapStepTest {

    private static final String PROD_KEY = "gate-prod-k1";
    private static final String DEV_KEY = "gate-dev-k1";
    private static final String ENT_KEY = "gate-ent-k1";
    private static final String LIMITED_KEY = "gate-limited-k1";
    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    private static final RequirementDiscovery.DeclaredRequirements NO_REQUIREMENT = declared();
    private static final RequirementDiscovery.DeclaredRequirements GATEWAY = declared("gateway-core");

    private KeyPair keyPair;
    private IssuerKeyResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        resolver = IssuerKeyResolver.ofScoped(Map.of(
                PROD_KEY, new TrustedIssuerKey(PROD_KEY, keyPair.getPublic(), IssuerKeyScope.PROD),
                DEV_KEY, new TrustedIssuerKey(DEV_KEY, keyPair.getPublic(), IssuerKeyScope.DEV),
                ENT_KEY, new TrustedIssuerKey(ENT_KEY, keyPair.getPublic(), IssuerKeyScope.ENT),
                LIMITED_KEY, new TrustedIssuerKey(LIMITED_KEY, keyPair.getPublic(), new IssuerKeyScope(
                        EnumSet.of(ExecutionEnvironment.DEVELOPMENT, ExecutionEnvironment.STAGING), null, null,
                        EnumSet.of(EntitlementClass.COMMERCIAL)))));
    }

    private ContractDecision decide(ExecutionEnvironment environment,
                                    RequirementDiscovery.DeclaredRequirements requirements, byte[] manifest) {
        return ContractBootstrapStep.decide(environment, requirements, manifest, NOW, resolver);
    }

    private ContractBreachException refusal(ExecutionEnvironment environment,
                                            RequirementDiscovery.DeclaredRequirements requirements,
                                            byte[] manifest) {
        return catchThrowableOfType(ContractBreachException.class, () -> decide(environment, requirements, manifest));
    }

    // ── No manifest ─────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @EnumSource(ExecutionEnvironment.class)
    @DisplayName("No manifest and no requirement on the classpath: Community boots in every environment, ungated")
    void communityBootsEverywhereWithoutManifest(ExecutionEnvironment environment) {
        ContractDecision decision = decide(environment, NO_REQUIREMENT, null);

        assertThat(decision.contract().edition()).isEqualTo("community");
        assertThat(decision.contract().isEnvironmentAuthorized(environment.id())).isTrue();
        assertThat(decision.admission()).isSameAs(WorkloadAdmission.UNGATED);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ExecutionEnvironment.class)
    @DisplayName("No manifest and a requirement: the boot stops with EX-LIC-0005 in every environment")
    void requirementWithoutManifestHaltsEverywhere(ExecutionEnvironment environment) {
        ContractBreachException breach = refusal(
                environment, declared("gateway-core", "bot-fingerprinting"), null);

        assertThat(breach).isNotNull();
        assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0005);
        assertThat(breach.rawArgs()).containsExactly(environment.id(), List.of("bot-fingerprinting", "gateway-core"));
    }

    // ── Manifest present ────────────────────────────────────────────────────

    @Test
    @DisplayName("Production, manifest entitles the required capability and the environment: contract returned, ungated")
    void productionWithEntitlingManifestBoots() throws Exception {
        byte[] manifest = signedManifest(PROD_KEY, List.of("production"), List.of("gateway-core"));

        ContractDecision decision = decide(ExecutionEnvironment.PRODUCTION, GATEWAY, manifest);

        assertThat(decision.contract().contractId()).isEqualTo("EXR-PHASE0-001");
        assertThat(decision.admission()).isSameAs(WorkloadAdmission.UNGATED);
    }

    @Test
    @DisplayName("Production, manifest does not authorize the environment (HARD): EX-LIC-0007")
    void productionNotAuthorizedByManifestHalts() throws Exception {
        byte[] manifest = signedManifest(PROD_KEY, List.of("staging"), List.of("gateway-core"));

        assertThat(refusal(ExecutionEnvironment.PRODUCTION, GATEWAY, manifest).errorCode())
                .isEqualTo(KernelErrorCodes.EX_LIC_0007);
    }

    @Test
    @DisplayName("Production, required capability not entitled (HARD): EX-LIC-0004")
    void productionWithUnentitledRequirementHalts() throws Exception {
        byte[] manifest = signedManifest(PROD_KEY, List.of("production"), List.of("security-tls"));

        assertThat(refusal(ExecutionEnvironment.PRODUCTION, GATEWAY, manifest).errorCode())
                .isEqualTo(KernelErrorCodes.EX_LIC_0004);
    }

    @Test
    @DisplayName("Production, required capability not entitled at SOFT: the boot continues and a WARNING is logged")
    void productionSoftCapabilityBootsAndWarns() throws Exception {
        byte[] manifest = signedManifest(PROD_KEY, List.of("production"), List.of("security-tls"), "SOFT");

        List<LogRecord> warnings = ContractViolationReporterTest.captureWarnings(
                () -> decide(ExecutionEnvironment.PRODUCTION, GATEWAY, manifest));

        assertThat(warnings).singleElement()
                .satisfies(r -> assertThat(r.getParameters()).contains("capability", "gateway-core"));
    }

    @Test
    @DisplayName("Production, required capability not entitled at AUDIT: the boot continues and nothing is logged")
    void productionAuditCapabilityBootsSilently() throws Exception {
        byte[] manifest = signedManifest(PROD_KEY, List.of("production"), List.of("security-tls"), "AUDIT");

        List<LogRecord> warnings = ContractViolationReporterTest.captureWarnings(
                () -> decide(ExecutionEnvironment.PRODUCTION, GATEWAY, manifest));

        assertThat(warnings).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ExecutionEnvironment.class, names = {"DEVELOPMENT", "STAGING", "DR_COLD"})
    @DisplayName("A manifest that lists only production does not authorize a non-production environment (HARD): EX-LIC-0007")
    void nonProductionEnvironmentIsAuthorizedByTheManifest(ExecutionEnvironment environment) throws Exception {
        byte[] manifest = signedManifest(PROD_KEY, List.of("production"), List.of("gateway-core"));

        assertThat(refusal(environment, GATEWAY, manifest).errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0007);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ExecutionEnvironment.class, names = {"DEVELOPMENT", "STAGING", "DR_COLD"})
    @DisplayName("A required capability the manifest does not entitle halts a non-production environment (HARD): EX-LIC-0004")
    void nonProductionEnvironmentEntitlesTheCapability(ExecutionEnvironment environment) throws Exception {
        byte[] manifest = signedManifest(PROD_KEY, List.of(environment.id()), List.of("security-tls"));

        assertThat(refusal(environment, GATEWAY, manifest).errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0004);
    }

    @Test
    @DisplayName("A tampered manifest fails verification even where no requirement is declared")
    void tamperedManifestFailsInDevelopment() throws Exception {
        String manifest = new String(signedManifest(PROD_KEY, List.of("production"), List.of("gateway-core")),
                StandardCharsets.UTF_8);
        byte[] tampered = manifest.replace("gateway-core", "gateway-cora").getBytes(StandardCharsets.UTF_8);

        assertThat(refusal(ExecutionEnvironment.DEVELOPMENT, NO_REQUIREMENT, tampered).errorCode())
                .isEqualTo(KernelErrorCodes.EX_LIC_0001);
    }

    @Test
    @DisplayName("A manifest with no requirement is still verified: out of its key's scope it halts, in scope it is ungated")
    void manifestWithoutRequirementIsStillVerified() throws Exception {
        byte[] outOfScope = signedManifest(DEV_KEY, List.of("development", "staging"), List.of("gateway-core"));
        byte[] inScope = signedManifest(DEV_KEY, List.of("development"), List.of("gateway-core"));

        assertThat(refusal(ExecutionEnvironment.DEVELOPMENT, NO_REQUIREMENT, outOfScope).errorCode())
                .isEqualTo(KernelErrorCodes.EX_LIC_0009);
        assertThat(decide(ExecutionEnvironment.DEVELOPMENT, NO_REQUIREMENT, inScope).admission())
                .isSameAs(WorkloadAdmission.UNGATED);
    }

    // ── Declared environment and key scope ─────────────────────────────────

    @Test
    @DisplayName("A developer key never entitles a production environment: EX-LIC-0009 with the declared environment")
    void developerKeyDoesNotEntitleProduction() throws Exception {
        byte[] manifest = signedManifest(DEV_KEY, List.of("development"), List.of("gateway-core"));

        ContractBreachException breach = refusal(ExecutionEnvironment.PRODUCTION, GATEWAY, manifest);

        assertThat(breach).isNotNull();
        assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0009);
        assertThat(breach.rawArgs()).containsExactly(DEV_KEY, List.of("production"));
    }

    @ParameterizedTest(name = "environment rule {0}")
    @ValueSource(strings = {"HARD", "SOFT", "AUDIT"})
    @DisplayName("The declared environment outside the key's scope is refused whatever the environment rule says")
    void declaredEnvironmentOutsideScopeIgnoresTheRule(String level) throws Exception {
        byte[] manifest = signedManifest(LIMITED_KEY, List.of("development"), List.of("gateway-core"), "HARD", level);

        ContractBreachException breach = refusal(ExecutionEnvironment.PRODUCTION, GATEWAY, manifest);

        assertThat(breach).isNotNull();
        assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0009);
        assertThat(breach.rawArgs()).containsExactly(LIMITED_KEY, List.of("production"));
    }

    @Test
    @DisplayName("The declared environment inside the key's scope but outside the manifest still follows the environment rule")
    void declaredEnvironmentInsideScopeFollowsTheRule() throws Exception {
        byte[] hard = signedManifest(LIMITED_KEY, List.of("development"), List.of("gateway-core"), "HARD", "HARD");
        byte[] audit = signedManifest(LIMITED_KEY, List.of("development"), List.of("gateway-core"), "HARD", "AUDIT");

        assertThat(refusal(ExecutionEnvironment.STAGING, GATEWAY, hard).errorCode())
                .isEqualTo(KernelErrorCodes.EX_LIC_0007);
        assertThat(decide(ExecutionEnvironment.STAGING, GATEWAY, audit).contract().contractId())
                .isEqualTo("EXR-PHASE0-001");
    }

    // ── Entitlement class ───────────────────────────────────────────────────

    @Test
    @DisplayName("An enterprise key entitles an enterprise-private requirement")
    void enterpriseKeyEntitlesEnterprisePrivate() throws Exception {
        byte[] manifest = signedManifest(ENT_KEY, List.of("production"), List.of("bot-fingerprinting"));

        assertThat(decide(ExecutionEnvironment.PRODUCTION, declared(EntitlementClass.ENTERPRISE_PRIVATE,
                "bot-fingerprinting"), manifest).contract().allowsCapability("bot-fingerprinting")).isTrue();
    }

    @Test
    @DisplayName("A production key may not entitle an enterprise-private requirement, even one the manifest does not list")
    void productionKeyDoesNotEntitleEnterprisePrivate() throws Exception {
        byte[] manifest = signedManifest(PROD_KEY, List.of("production"), List.of("gateway-core"));

        ContractBreachException breach = refusal(ExecutionEnvironment.PRODUCTION, declared(
                EntitlementClass.ENTERPRISE_PRIVATE, "bot-fingerprinting"), manifest);

        assertThat(breach).isNotNull();
        assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0012);
        assertThat(breach.rawArgs()).containsExactly(PROD_KEY, "enterprise-private", "test-provider");
    }

    @Test
    @DisplayName("A developer key may not entitle an enterprise-private requirement")
    void developerKeyDoesNotEntitleEnterprisePrivate() throws Exception {
        byte[] manifest = signedManifest(DEV_KEY, List.of("development"), List.of("bot-fingerprinting"));

        assertThat(refusal(ExecutionEnvironment.DEVELOPMENT, declared(
                EntitlementClass.ENTERPRISE_PRIVATE, "bot-fingerprinting"), manifest).errorCode())
                .isEqualTo(KernelErrorCodes.EX_LIC_0012);
    }

    @Test
    @DisplayName("With no requirement declared no class is checked: a production key boots a manifest listing any capability")
    void noRequirementChecksNoClass() throws Exception {
        byte[] manifest = signedManifest(PROD_KEY, List.of("production"), List.of("bot-fingerprinting"));

        assertThatCode(() -> decide(ExecutionEnvironment.PRODUCTION, NO_REQUIREMENT, manifest))
                .doesNotThrowAnyException();
    }

    // ── Workload admission ──────────────────────────────────────────────────

    @Test
    @DisplayName("A developer key and a requirement: the decision carries an enforced gate holding the manifest's envelope")
    void developerKeyWithRequirementIsGated() throws Exception {
        byte[] manifest = signedManifest(DEV_KEY, List.of("development"), List.of("gateway-core"));

        WorkloadAdmission admission = decide(ExecutionEnvironment.DEVELOPMENT, GATEWAY, manifest).admission();

        assertThat(admission).isInstanceOf(DeveloperWorkloadAdmission.class);
        assertThat(admission.enforced()).isTrue();
        for (int connection = 0; connection < 10; connection++) {
            assertThat(admission.tryAdmitConnection()).as("connection %d", connection).isTrue();
        }
        assertThat(admission.tryAdmitConnection()).as("the eleventh connection").isFalse();
    }

    @Test
    @DisplayName("A developer key and no requirement: the Community-only classpath is not gated")
    void developerKeyWithoutRequirementIsUngated() throws Exception {
        byte[] manifest = signedManifest(DEV_KEY, List.of("development"), List.of("gateway-core"));

        assertThat(decide(ExecutionEnvironment.DEVELOPMENT, NO_REQUIREMENT, manifest).admission())
                .isSameAs(WorkloadAdmission.UNGATED);
    }

    @Test
    @DisplayName("A production or enterprise key and a requirement: no developer envelope applies")
    void nonDeveloperKeysAreUngated() throws Exception {
        for (String key : List.of(PROD_KEY, ENT_KEY)) {
            byte[] manifest = signedManifest(key, List.of("production"), List.of("gateway-core"));

            assertThat(decide(ExecutionEnvironment.PRODUCTION, GATEWAY, manifest).admission())
                    .as(key).isSameAs(WorkloadAdmission.UNGATED);
        }
    }

    // ── Environment declaration ─────────────────────────────────────────────

    @Test
    @DisplayName("No configuration, or no 'environment' key: DEVELOPMENT")
    void undeclaredEnvironmentIsDevelopment() {
        assertThat(ContractBootstrapStep.declaredEnvironment(null)).isEqualTo(ExecutionEnvironment.DEVELOPMENT);
        assertThat(ContractBootstrapStep.declaredEnvironment(config(Map.of())))
                .isEqualTo(ExecutionEnvironment.DEVELOPMENT);
    }

    @Test
    @DisplayName("The kernel profile does not declare the environment")
    void kernelProfileIsNotTheEnvironment() {
        ConfigProvider config = config(Map.of("kernel.profile", "prod", "profile", "production"));

        assertThat(ContractBootstrapStep.declaredEnvironment(config)).isEqualTo(ExecutionEnvironment.DEVELOPMENT);
    }

    @Test
    @DisplayName("The 'environment' key resolves case-insensitively, ignoring surrounding whitespace")
    void environmentKeyResolves() {
        assertThat(ContractBootstrapStep.declaredEnvironment(config(Map.of("environment", " DR-hot "))))
                .isEqualTo(ExecutionEnvironment.DR_HOT);
    }

    @Test
    @DisplayName("An 'environment' value naming no environment class fails with EX-CFG-1002")
    void unknownEnvironmentIsRejected() {
        assertThatThrownBy(() -> ContractBootstrapStep.declaredEnvironment(config(Map.of("environment", "prod"))))
                .isInstanceOf(ConfigProvider.ConfigProviderException.class)
                .satisfies(e -> assertThat(((ConfigProvider.ConfigProviderException) e).errorCode())
                        .isEqualTo(KernelErrorCodes.EX_CFG_1002));
    }

    // ── Manifest location ───────────────────────────────────────────────────

    @Test
    @DisplayName("A configured path that does not exist stops the boot instead of falling through")
    void missingConfiguredPathIsFatal(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("license-manifest.json"), "{}");
        Path configured = dir.resolve("absent.json");

        assertThatThrownBy(() -> ManifestLocator.resolve(
                Optional.of(configured.toString()), dir.resolve("license-manifest.json"), getClass().getClassLoader()))
                .isInstanceOf(ContractBreachException.class)
                .satisfies(e -> {
                    ContractBreachException breach = (ContractBreachException) e;
                    assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0001);
                    assertThat(breach.rawArgs()).containsExactly(configured.toString());
                });
    }

    @Test
    @DisplayName("A configured directory is not a manifest")
    void configuredDirectoryIsFatal(@TempDir Path dir) {
        assertThatThrownBy(() -> ManifestLocator.resolve(
                Optional.of(dir.toString()), dir.resolve("none.json"), getClass().getClassLoader()))
                .isInstanceOf(ContractBreachException.class);
    }

    @Test
    @DisplayName("A configured path is read in preference to the default file")
    void configuredPathWins(@TempDir Path dir) throws Exception {
        Path configured = Files.writeString(dir.resolve("configured.json"), "configured");
        Path fallback = Files.writeString(dir.resolve("license-manifest.json"), "default");

        ManifestLocator.ManifestSource manifest = ManifestLocator.resolve(
                Optional.of(configured.toString()), fallback, getClass().getClassLoader());

        assertThat(manifest.bytes()).isEqualTo("configured".getBytes(StandardCharsets.UTF_8));
        assertThat(manifest.source()).isEqualTo(configured.toString());
    }

    @Test
    @DisplayName("Without a configured path the default file is read, and without either nothing is found")
    void defaultFileThenNothing(@TempDir Path dir) throws Exception {
        Path fallback = dir.resolve("license-manifest.json");
        assertThat(ManifestLocator.resolve(Optional.empty(), fallback, getClass().getClassLoader()))
                .isNull();

        Files.writeString(fallback, "default");
        assertThat(ManifestLocator.resolve(Optional.empty(), fallback, getClass().getClassLoader())
                .bytes()).isEqualTo("default".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("A configured path bound to a blank value stops the boot instead of falling through")
    void blankConfiguredPathIsFatal(@TempDir Path dir) throws Exception {
        Path fallback = Files.writeString(dir.resolve("license-manifest.json"), "default");

        assertThatThrownBy(() -> ManifestLocator.resolve(
                Optional.of(" "), fallback, getClass().getClassLoader()))
                .isInstanceOf(ContractBreachException.class)
                .satisfies(e -> {
                    ContractBreachException breach = (ContractBreachException) e;
                    assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0001);
                    assertThat(breach.rawArgs()).containsExactly(ContractBootstrapStep.MANIFEST_PATH_KEY);
                });
    }

    @Test
    @DisplayName("A manifest larger than 64 KiB is refused before it is read")
    void oversizedManifestIsRefused(@TempDir Path dir) throws Exception {
        Path large = Files.write(dir.resolve("large.json"), new byte[64 * 1024 + 1]);
        Path exact = Files.write(dir.resolve("exact.json"), new byte[64 * 1024]);

        assertThatThrownBy(() -> ManifestLocator.resolve(
                Optional.of(large.toString()), dir.resolve("none"), getClass().getClassLoader()))
                .isInstanceOf(ContractBreachException.class)
                .hasMessageContaining("exceeds")
                .satisfies(e -> assertThat(((ContractBreachException) e).rawArgs())
                        .containsExactly(large.toString()));
        assertThat(ManifestLocator.resolve(
                Optional.of(exact.toString()), dir.resolve("none"), getClass().getClassLoader()).bytes())
                .hasSize(64 * 1024);
    }

    @Test
    @DisplayName("A null class loader reads the classpath through the system class loader, not into a NullPointerException")
    void nullClassLoaderFallsBackToSystemLoader(@TempDir Path dir) {
        assertThat(ManifestLocator.resolve(Optional.empty(), dir.resolve("license-manifest.json"), null)).isNull();
        assertThat(RequirementDiscovery.declared(null).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("A classpath manifest larger than 64 KiB is refused, naming the classpath source")
    void oversizedClasspathManifestIsRefused(@TempDir Path root) throws Exception {
        Files.write(root.resolve("license-manifest.json"), new byte[64 * 1024 + 1]);
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            assertThatThrownBy(() -> ManifestLocator.resolve(
                    Optional.empty(), root.resolve("cwd").resolve("license-manifest.json"), loader))
                    .isInstanceOf(ContractBreachException.class)
                    .satisfies(e -> assertThat(((ContractBreachException) e).rawArgs())
                            .containsExactly("classpath:license-manifest.json"));
        }
    }

    @Test
    @DisplayName("The classpath resource is consulted last")
    void classpathResourceIsLast(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("license-manifest.json"), "classpath");
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            ManifestLocator.ManifestSource manifest = ManifestLocator.resolve(
                    Optional.empty(), root.resolve("cwd").resolve("license-manifest.json"), loader);
            assertThat(manifest.bytes()).isEqualTo("classpath".getBytes(StandardCharsets.UTF_8));
            assertThat(manifest.source()).isEqualTo("classpath:license-manifest.json");
        }
    }

    @Test
    @DisplayName("Each gate run records one ContractResolved event: environment, source, contract, outcome")
    void gateRecordsResolvedEvent(@TempDir Path dir) throws Exception {
        Path dump = dir.resolve("gate.jfr");
        try (Recording recording = new Recording()) {
            recording.enable("eu.exeris.kernel.contract.Resolved");
            recording.start();
            try (URLClassLoader empty = new URLClassLoader(new URL[0], null)) {
                ContractBootstrapStep.run(config(Map.of("environment", "staging")), empty);
            }
            assertThatThrownBy(() -> ContractBootstrapStep.run(config(Map.of("environment", "moon")), null));
            recording.stop();
            recording.dump(dump);
        }

        List<RecordedEvent> events = RecordingFile.readAllEvents(dump).stream()
                .filter(e -> e.getEventType().getName().equals("eu.exeris.kernel.contract.Resolved"))
                .toList();
        assertThat(events).extracting(e -> e.getString("environment") + "|" + e.getString("source") + "|"
                        + e.getString("contractId") + "|" + e.getString("outcome"))
                .containsExactlyInAnyOrder("staging|none|COMMUNITY|bound", "|none||" + KernelErrorCodes.EX_CFG_1002);
    }

    @Test
    @DisplayName("A scope refusal raised inside the verifier still records the signing key on the event")
    void verifierRefusalRecordsTheKey(@TempDir Path dir) throws Exception {
        Path outside = Files.write(dir.resolve("outside.json"),
                signedManifest(DEV_KEY, List.of("development", "staging"), List.of("gateway-core")));
        Path dump = dir.resolve("gate.jfr");
        try (Recording recording = new Recording();
             URLClassLoader empty = new URLClassLoader(new URL[0], null)) {
            recording.enable("eu.exeris.kernel.contract.Resolved");
            recording.start();
            assertThatThrownBy(() -> ContractBootstrapStep.run(
                    config(Map.of(ContractBootstrapStep.MANIFEST_PATH_KEY, outside.toString())), empty, resolver))
                    .isInstanceOfSatisfying(ContractBreachException.class,
                            breach -> assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0009));
            recording.stop();
            recording.dump(dump);
        }

        assertThat(RecordingFile.readAllEvents(dump))
                .filteredOn(e -> e.getEventType().getName().equals("eu.exeris.kernel.contract.Resolved"))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getString("keyId")).isEqualTo(DEV_KEY);
                    assertThat(e.getString("outcome")).isEqualTo(KernelErrorCodes.EX_LIC_0009);
                });
    }

    @Test
    @DisplayName("The public decision takes requirements by class and decides as the gate does")
    void publicDecisionMatchesTheGate() throws Exception {
        byte[] manifest = signedManifest(DEV_KEY, List.of("development"), List.of("gateway-core"));

        ContractDecision decision = ContractBootstrapStep.decide(ExecutionEnvironment.DEVELOPMENT,
                Map.of(EntitlementClass.COMMERCIAL, Set.of("gateway-core")), manifest, NOW, resolver);
        assertThat(decision.admission().enforced()).isTrue();

        assertThatThrownBy(() -> ContractBootstrapStep.decide(ExecutionEnvironment.DEVELOPMENT,
                Map.of(EntitlementClass.ENTERPRISE_PRIVATE, Set.of("gateway-core")), manifest, NOW, resolver))
                .isInstanceOfSatisfying(ContractBreachException.class, breach -> {
                    assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0012);
                    assertThat(breach.rawArgs()).containsExactly(DEV_KEY, "enterprise-private",
                            "declared:enterprise-private");
                });
        assertThat(ContractBootstrapStep.decide(ExecutionEnvironment.PRODUCTION, Map.of(), null, NOW, resolver)
                .contract().edition()).isEqualTo("community");
    }

    @Test
    @DisplayName("A manifest that is both expired and short of a declared class reports its time code first")
    void timeCodeWinsOverTheGateChecks() throws Exception {
        byte[] manifest = signedManifest(PROD_KEY, List.of("production"), List.of("gateway-core"));
        Instant afterExpiry = Instant.parse("2030-01-01T00:00:00Z");

        assertThatThrownBy(() -> ContractBootstrapStep.decide(ExecutionEnvironment.PRODUCTION,
                Map.of(EntitlementClass.ENTERPRISE_PRIVATE, Set.of("gateway-core")), manifest, afterExpiry, resolver))
                .isInstanceOfSatisfying(ContractBreachException.class,
                        breach -> assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0003));
    }

    @Test
    @DisplayName("The event names the signing key and a developer licence, bound or refused after resolution")
    void gateRecordsKeyAndDeveloperLicence(@TempDir Path dir) throws Exception {
        Path services = Files.createDirectories(dir.resolve("classpath/META-INF/services"));
        Files.writeString(services.resolve(EntitlementRequirement.class.getName()),
                GatewayRequirement.class.getName() + "\n");
        Path developer = Files.write(dir.resolve("developer.json"),
                signedManifest(DEV_KEY, List.of("development"), List.of("gateway-core")));
        Path production = Files.write(dir.resolve("production.json"),
                signedManifest(PROD_KEY, List.of("production"), List.of("gateway-core")));
        Path dump = dir.resolve("gate.jfr");
        try (Recording recording = new Recording();
             URLClassLoader loader = new URLClassLoader(
                     new URL[] {dir.resolve("classpath").toUri().toURL()}, getClass().getClassLoader())) {
            recording.enable("eu.exeris.kernel.contract.Resolved");
            recording.start();
            ContractBootstrapStep.run(config(Map.of(ContractBootstrapStep.MANIFEST_PATH_KEY, developer.toString())),
                    loader, resolver);
            ContractBootstrapStep.run(config(Map.of("environment", "production",
                    ContractBootstrapStep.MANIFEST_PATH_KEY, production.toString())), loader, resolver);
            assertThatThrownBy(() -> ContractBootstrapStep.run(config(Map.of("environment", "production",
                    ContractBootstrapStep.MANIFEST_PATH_KEY, developer.toString())), loader, resolver));
            recording.stop();
            recording.dump(dump);
        }

        List<RecordedEvent> events = RecordingFile.readAllEvents(dump).stream()
                .filter(e -> e.getEventType().getName().equals("eu.exeris.kernel.contract.Resolved"))
                .toList();
        assertThat(events).extracting(e -> e.getString("keyId") + "|" + e.getBoolean("developerLicence") + "|"
                        + e.getString("outcome"))
                .containsExactlyInAnyOrder(
                        DEV_KEY + "|true|bound",
                        PROD_KEY + "|false|bound",
                        DEV_KEY + "|false|" + KernelErrorCodes.EX_LIC_0009);
    }

    // ── Validity is read through the kernel time source ────────────────────

    @Test
    @DisplayName("The validity window is judged at the kernel time source's instant, not the platform clock")
    void validityReadsTheKernelTimeSource(@TempDir Path dir) throws Exception {
        Path manifest = Files.write(dir.resolve("manifest.json"),
                signedManifest(PROD_KEY, List.of("development"), List.of("gateway-core")));
        ConfigProvider config = config(Map.of(ContractBootstrapStep.MANIFEST_PATH_KEY, manifest.toString()));
        try (URLClassLoader empty = new URLClassLoader(new URL[0], null)) {
            ContractBreachException expired = catchThrowableOfType(ContractBreachException.class,
                    () -> ScopedValue.where(TimeKernelProviders.TIME_SOURCE, sourceAt("2030-01-01T00:00:00Z"))
                            .run(() -> ContractBootstrapStep.run(config, empty, resolver)));
            assertThat(expired).isNotNull();
            assertThat(expired.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0003);

            ContractBreachException early = catchThrowableOfType(ContractBreachException.class,
                    () -> ScopedValue.where(TimeKernelProviders.TIME_SOURCE, sourceAt("2025-06-01T00:00:00Z"))
                            .run(() -> ContractBootstrapStep.run(config, empty, resolver)));
            assertThat(early).isNotNull();
            assertThat(early.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0006);

            ContractDecision valid = ScopedValue.where(TimeKernelProviders.TIME_SOURCE, sourceAt("2026-10-06T12:00:00Z"))
                    .call(() -> ContractBootstrapStep.run(config, empty, resolver));
            assertThat(valid.contract().contractId()).isEqualTo("EXR-PHASE0-001");
        }
    }

    @Test
    @DisplayName("A developer manifest's thirty-day window is judged at the kernel time source's instant too")
    void developerWindowReadsTheKernelTimeSource(@TempDir Path dir) throws Exception {
        Path manifest = Files.write(dir.resolve("manifest.json"),
                signedManifest(DEV_KEY, List.of("development"), List.of("gateway-core")));
        ConfigProvider config = config(Map.of(ContractBootstrapStep.MANIFEST_PATH_KEY, manifest.toString()));
        try (URLClassLoader empty = new URLClassLoader(new URL[0], null)) {
            ContractBreachException expired = catchThrowableOfType(ContractBreachException.class,
                    () -> ScopedValue.where(TimeKernelProviders.TIME_SOURCE, sourceAt("2026-12-01T00:00:00Z"))
                            .run(() -> ContractBootstrapStep.run(config, empty, resolver)));

            assertThat(expired).isNotNull();
            assertThat(expired.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0003);
        }
    }

    private static ManualTimeSource sourceAt(String instant) {
        return new ManualTimeSource(Duration.between(Instant.EPOCH, Instant.parse(instant)).toNanos());
    }

    @Test
    @DisplayName("The gate decides UNGATED for the Community fallback: the workload mechanism is inert")
    void fallbackDecidesUngated() throws Exception {
        try (URLClassLoader empty = new URLClassLoader(new URL[0], null)) {
            ContractDecision decision = ContractBootstrapStep.run(config(Map.of()), empty);

            assertThat(decision.admission()).isSameAs(WorkloadAdmission.UNGATED);
            assertThat(decision.contract().edition()).isEqualTo("community");
        }
    }

    /** Test requirement: one commercial capability. */
    public static final class GatewayRequirement implements EntitlementRequirement {
        @Override
        public Set<String> requiredCapabilities() {
            return Set.of("gateway-core");
        }

        @Override
        public EntitlementClass entitlementClass() {
            return EntitlementClass.COMMERCIAL;
        }
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private static RequirementDiscovery.DeclaredRequirements declared(String... capabilities) {
        return declared(EntitlementClass.COMMERCIAL, capabilities);
    }

    private static RequirementDiscovery.DeclaredRequirements declared(
            EntitlementClass entitlementClass, String... capabilities) {
        SortedMap<String, EntitlementClass> byId = new TreeMap<>();
        SortedMap<String, String> providerById = new TreeMap<>();
        for (String capability : capabilities) {
            byId.put(capability, entitlementClass);
            providerById.put(capability, "test-provider");
        }
        return new RequirementDiscovery.DeclaredRequirements(byId, providerById);
    }

    private byte[] signedManifest(String keyId, List<String> environments, List<String> capabilities)
            throws Exception {
        return signedManifest(keyId, environments, capabilities, "HARD");
    }

    private byte[] signedManifest(String keyId, List<String> environments, List<String> capabilities,
                                  String capabilityLevel) throws Exception {
        return signedManifest(keyId, environments, capabilities, capabilityLevel, "HARD");
    }

    /** A manifest that fits every scope: a developer key's thirty days, its ceiling, and issuedAt. */
    private byte[] signedManifest(String keyId, List<String> environments, List<String> capabilities,
                                  String capabilityLevel, String environmentLevel) throws Exception {
        boolean developer = DEV_KEY.equals(keyId);
        String from = developer ? "2026-10-01T00:00:00Z" : "2026-01-01T00:00:00Z";
        String until = developer ? "2026-10-17T00:00:00Z" : "2027-01-01T00:00:00Z";
        String unsigned = "{\"$schema\":\"https://specs.exeris.eu/schema/v1/license-manifest.json\",\"contract\":{\"id\":\"EXR-PHASE0-001\",\"commercialModel\":\"STANDARD\"},"
                + "\"entitlement\":{\"edition\":\"commercial\",\"sku\":\"exeris-sku-test\","
                + "\"licenseMode\":\"SUBSCRIPTION\",\"capabilities\":" + jsonArray(capabilities) + "},"
                + "\"execution\":{\"environments\":" + jsonArray(environments) + ",\"authorizedInstances\":3,"
                + "\"validFrom\":\"" + from + "\",\"validUntil\":\"" + until + "\","
                + "\"workloadEnvelope\":{\"maxThroughputRps\":50,\"maxConnections\":10,"
                + "\"growthAllowancePercent\":0}},"
                + "\"enforcementRules\":{\"capability\":\"" + capabilityLevel + "\",\"environment\":\""
                + environmentLevel + "\"},"
                + "\"issuer\":{\"keyId\":\"" + keyId + "\",\"issuedAt\":\"" + from + "\"}}";
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(JsonCanonicalizer.canonicalize(unsigned));
        String value = Base64.getEncoder().encodeToString(signer.sign());
        String signed = unsigned.substring(0, unsigned.length() - 1)
                + ",\"signature\":{\"algorithm\":\"Ed25519\",\"canonicalization\":\"RFC-8785\","
                + "\"value\":\"" + value + "\"}}";
        return signed.getBytes(StandardCharsets.UTF_8);
    }

    private static String jsonArray(List<String> values) {
        return values.stream().map(v -> "\"" + v + "\"").reduce((a, b) -> a + "," + b)
                .map(joined -> "[" + joined + "]").orElse("[]");
    }

    private static ConfigProvider config(Map<String, String> values) {
        return new ConfigProvider() {
            @Override public Supplier<KernelSettings> kernelSettings() { return KernelSettings::defaults; }
            @Override public Optional<String> getString(String key) { return Optional.ofNullable(values.get(key)); }
            @Override public Optional<Integer> getInt(String key) { return Optional.empty(); }
            @Override public Optional<Long> getLong(String key) { return Optional.empty(); }
            @Override public Optional<Boolean> getBoolean(String key) { return Optional.empty(); }
            @Override public <T> Optional<T> get(String key, Class<T> type) { return Optional.empty(); }
            @Override public void watch(String file, String key, Consumer<Object> callback) {
                // no hot reload in this fixture
            }
            @Override public String providerName() { return "MapConfigProvider"; }
        };
    }
}
