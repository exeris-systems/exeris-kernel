/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.contract;

import eu.exeris.kernel.core.contract.crypto.IssuerKeyResolver;
import eu.exeris.kernel.core.contract.crypto.JsonCanonicalizer;
import eu.exeris.kernel.core.contract.jfr.ContractViolationReporterTest;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.contract.EntitlementRequirement;
import eu.exeris.kernel.spi.contract.ExecutionContract;
import eu.exeris.kernel.spi.contract.ExecutionEnvironment;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.contract.ContractBreachException;
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
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ContractBootstrapStep — Phase 0 contract gate")
class ContractBootstrapStepTest {

    private static final String KEY_ID = "phase0-test-k1";
    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    private static final Set<String> NO_REQUIREMENT = Set.of();
    private static final Set<String> GATEWAY = Set.of("gateway-core");

    private KeyPair keyPair;
    private IssuerKeyResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        resolver = IssuerKeyResolver.of(Map.of(KEY_ID, keyPair.getPublic()));
    }

    // ── No manifest ─────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @EnumSource(ExecutionEnvironment.class)
    @DisplayName("No manifest and no requirement on the classpath: Community boots in every environment")
    void communityBootsEverywhereWithoutManifest(ExecutionEnvironment environment) {
        ExecutionContract contract = ContractBootstrapStep.decide(environment, NO_REQUIREMENT, null, NOW, resolver);

        assertThat(contract.edition()).isEqualTo("community");
        assertThat(contract.isEnvironmentAuthorized(environment.id())).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ExecutionEnvironment.class, names = {"PRODUCTION", "PRODUCTION_LOAD_SIM", "DR_HOT"})
    @DisplayName("No manifest, a requirement, and a production-class environment: the boot stops with EX-LIC-0005")
    void productionClassWithRequirementAndNoManifestHalts(ExecutionEnvironment environment) {
        assertThatThrownBy(() -> ContractBootstrapStep.decide(
                environment, Set.of("gateway-core", "bot-fingerprinting"), null, NOW, resolver))
                .isInstanceOf(ContractBreachException.class)
                .satisfies(e -> {
                    ContractBreachException breach = (ContractBreachException) e;
                    assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0005);
                    assertThat(breach.rawArgs())
                            .containsExactly(environment.id(), List.of("bot-fingerprinting", "gateway-core"));
                });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ExecutionEnvironment.class, names = {"DEVELOPMENT", "STAGING", "DR_COLD"})
    @DisplayName("No manifest and a requirement in a non-production environment: Community fallback, no halt")
    void nonProductionWithRequirementAndNoManifestFallsBack(ExecutionEnvironment environment) {
        ExecutionContract contract = ContractBootstrapStep.decide(environment, GATEWAY, null, NOW, resolver);

        assertThat(contract.edition()).isEqualTo("community");
    }

    // ── Manifest present ────────────────────────────────────────────────────

    @Test
    @DisplayName("Production, manifest entitles the required capability and the environment: contract returned")
    void productionWithEntitlingManifestBoots() throws Exception {
        byte[] manifest = signedManifest(List.of("production"), List.of("gateway-core"));

        ExecutionContract contract = ContractBootstrapStep.decide(
                ExecutionEnvironment.PRODUCTION, GATEWAY, manifest, NOW, resolver);

        assertThat(contract.contractId()).isEqualTo("EXR-PHASE0-001");
    }

    @Test
    @DisplayName("Production, manifest does not authorize the environment (HARD): EX-LIC-0007")
    void productionNotAuthorizedByManifestHalts() throws Exception {
        byte[] manifest = signedManifest(List.of("staging"), List.of("gateway-core"));

        assertThatThrownBy(() -> ContractBootstrapStep.decide(
                ExecutionEnvironment.PRODUCTION, GATEWAY, manifest, NOW, resolver))
                .isInstanceOf(ContractBreachException.class)
                .satisfies(e -> assertThat(((ContractBreachException) e).errorCode())
                        .isEqualTo(KernelErrorCodes.EX_LIC_0007));
    }

    @Test
    @DisplayName("Production, required capability not entitled (HARD): EX-LIC-0004")
    void productionWithUnentitledRequirementHalts() throws Exception {
        byte[] manifest = signedManifest(List.of("production"), List.of("security-tls"));

        assertThatThrownBy(() -> ContractBootstrapStep.decide(
                ExecutionEnvironment.PRODUCTION, GATEWAY, manifest, NOW, resolver))
                .isInstanceOf(ContractBreachException.class)
                .satisfies(e -> assertThat(((ContractBreachException) e).errorCode())
                        .isEqualTo(KernelErrorCodes.EX_LIC_0004));
    }

    @Test
    @DisplayName("Production, required capability not entitled at SOFT: the boot continues and a WARNING is logged")
    void productionSoftCapabilityBootsAndWarns() throws Exception {
        byte[] manifest = signedManifest(List.of("production"), List.of("security-tls"), "SOFT");

        List<LogRecord> warnings = ContractViolationReporterTest.captureWarnings(() -> ContractBootstrapStep.decide(
                ExecutionEnvironment.PRODUCTION, GATEWAY, manifest, NOW, resolver));

        assertThat(warnings).singleElement()
                .satisfies(r -> assertThat(r.getParameters()).contains("capability", "gateway-core"));
    }

    @Test
    @DisplayName("Production, required capability not entitled at AUDIT: the boot continues and nothing is logged")
    void productionAuditCapabilityBootsSilently() throws Exception {
        byte[] manifest = signedManifest(List.of("production"), List.of("security-tls"), "AUDIT");

        List<LogRecord> warnings = ContractViolationReporterTest.captureWarnings(() -> ContractBootstrapStep.decide(
                ExecutionEnvironment.PRODUCTION, GATEWAY, manifest, NOW, resolver));

        assertThat(warnings).isEmpty();
    }

    @Test
    @DisplayName("Development, manifest neither authorizes the environment nor entitles the requirement: boots")
    void developmentIsNotGatedByManifestContent() throws Exception {
        byte[] manifest = signedManifest(List.of("production"), List.of("security-tls"));

        assertThatCode(() -> ContractBootstrapStep.decide(
                ExecutionEnvironment.DEVELOPMENT, GATEWAY, manifest, NOW, resolver))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A tampered manifest fails verification even where no entitlement is required")
    void tamperedManifestFailsInDevelopment() throws Exception {
        String manifest = new String(signedManifest(List.of("production"), List.of("gateway-core")),
                StandardCharsets.UTF_8);
        byte[] tampered = manifest.replace("gateway-core", "gateway-cora").getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> ContractBootstrapStep.decide(
                ExecutionEnvironment.DEVELOPMENT, NO_REQUIREMENT, tampered, NOW, resolver))
                .isInstanceOf(ContractBreachException.class)
                .satisfies(e -> assertThat(((ContractBreachException) e).errorCode())
                        .isEqualTo(KernelErrorCodes.EX_LIC_0001));
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
        assertThat(RequirementDiscovery.requiredCapabilities(null)).isEmpty();
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

    // ── Requirement discovery ───────────────────────────────────────────────

    @Test
    @DisplayName("Requirements are discovered through ServiceLoader and merged")
    void requirementsAreDiscovered(@TempDir Path root) throws Exception {
        Path services = root.resolve("META-INF/services");
        Files.createDirectories(services);
        Files.writeString(services.resolve(EntitlementRequirement.class.getName()),
                GatewayRequirement.class.getName() + "\n" + BotRequirement.class.getName() + "\n");

        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {root.toUri().toURL()}, getClass().getClassLoader())) {
            assertThat(RequirementDiscovery.requiredCapabilities(loader))
                    .containsExactly("bot-fingerprinting", "gateway-core", "gateway-routing");
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(classes = {
            ThrowingRequirement.class, NullRequirement.class, EmptyRequirement.class, MalformedRequirement.class})
    @DisplayName("A requirement that throws, or declares null, nothing or a malformed id, stops the boot with EX-LIC-0008")
    void invalidRequirementIsRefused(Class<?> provider, @TempDir Path root) throws Exception {
        try (URLClassLoader loader = loaderDeclaring(root, provider.getName())) {
            assertThatThrownBy(() -> RequirementDiscovery.requiredCapabilities(loader))
                    .isInstanceOf(ContractBreachException.class)
                    .satisfies(e -> {
                        ContractBreachException breach = (ContractBreachException) e;
                        assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0008);
                        assertThat(breach.rawArgs()).containsExactly(provider.getName());
                    });
        }
    }

    @Test
    @DisplayName("A requirement class that cannot be loaded stops the boot with EX-LIC-0008, not a raw Error")
    void unloadableRequirementIsRefused(@TempDir Path root) throws Exception {
        try (URLClassLoader loader = loaderDeclaring(root, "eu.exeris.example.MissingRequirement")) {
            assertThatThrownBy(() -> RequirementDiscovery.requiredCapabilities(loader))
                    .isInstanceOf(ContractBreachException.class)
                    .satisfies(e -> {
                        ContractBreachException breach = (ContractBreachException) e;
                        assertThat(breach.errorCode()).isEqualTo(KernelErrorCodes.EX_LIC_0008);
                        assertThat(breach.rawArgs()).containsExactly(EntitlementRequirement.class.getName());
                    });
        }
    }

    @Test
    @DisplayName("A requirement outside production with no manifest boots, and warns that the code runs ungated")
    void ungatedRequirementWarns() {
        List<LogRecord> warnings = ContractViolationReporterTest.captureWarnings(() ->
                assertThat(ContractBootstrapStep.decide(
                        ExecutionEnvironment.STAGING, Set.of("staging-only-capability"), null, NOW, resolver)
                        .edition()).isEqualTo("community"));

        assertThat(warnings).singleElement().satisfies(r -> assertThat(
                java.text.MessageFormat.format(r.getMessage(), r.getParameters()))
                .contains("staging-only-capability").contains("'staging'"));
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

    private static URLClassLoader loaderDeclaring(Path root, String providerClass) throws Exception {
        Path services = root.resolve("META-INF/services");
        Files.createDirectories(services);
        Files.writeString(services.resolve(EntitlementRequirement.class.getName()), providerClass + "\n");
        return new URLClassLoader(new URL[] {root.toUri().toURL()}, ContractBootstrapStepTest.class.getClassLoader());
    }

    /** Test requirement that fails to declare. */
    public static final class ThrowingRequirement implements EntitlementRequirement {
        @Override
        public Set<String> requiredCapabilities() {
            throw new IllegalStateException("not ready");
        }
    }

    /** Test requirement that declares null. */
    public static final class NullRequirement implements EntitlementRequirement {
        @Override
        public Set<String> requiredCapabilities() {
            return null;
        }
    }

    /** Test requirement that declares nothing. */
    public static final class EmptyRequirement implements EntitlementRequirement {
        @Override
        public Set<String> requiredCapabilities() {
            return Set.of();
        }
    }

    /** Test requirement that declares an identifier no manifest could entitle. */
    public static final class MalformedRequirement implements EntitlementRequirement {
        @Override
        public Set<String> requiredCapabilities() {
            return Set.of("Gateway Core");
        }
    }

    @Test
    @DisplayName("A classpath with no requirement declares nothing")
    void noRequirementOnPlainClasspath() {
        assertThat(RequirementDiscovery.requiredCapabilities(getClass().getClassLoader())).isEmpty();
    }

    /** Test requirement: two gateway capabilities. */
    public static final class GatewayRequirement implements EntitlementRequirement {
        @Override
        public Set<String> requiredCapabilities() {
            return Set.of("gateway-core", "gateway-routing");
        }
    }

    /** Test requirement: one overlapping and one distinct capability. */
    public static final class BotRequirement implements EntitlementRequirement {
        @Override
        public Set<String> requiredCapabilities() {
            return Set.of("gateway-core", "bot-fingerprinting");
        }
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private byte[] signedManifest(List<String> environments, List<String> capabilities) throws Exception {
        return signedManifest(environments, capabilities, "HARD");
    }

    private byte[] signedManifest(List<String> environments, List<String> capabilities, String capabilityLevel)
            throws Exception {
        String unsigned = "{\"$schema\":\"https://specs.exeris.eu/schema/v1/license-manifest.json\",\"contract\":{\"id\":\"EXR-PHASE0-001\",\"commercialModel\":\"STANDARD\"},"
                + "\"entitlement\":{\"edition\":\"commercial\",\"sku\":\"exeris-sku-test\","
                + "\"licenseMode\":\"SUBSCRIPTION\",\"capabilities\":" + jsonArray(capabilities) + "},"
                + "\"execution\":{\"environments\":" + jsonArray(environments) + ",\"authorizedInstances\":3,"
                + "\"validFrom\":\"2026-01-01T00:00:00Z\",\"validUntil\":\"2027-01-01T00:00:00Z\"},"
                + "\"enforcementRules\":{\"capability\":\"" + capabilityLevel + "\",\"environment\":\"HARD\"},"
                + "\"issuer\":{\"keyId\":\"" + KEY_ID + "\",\"issuedAt\":\"2026-01-01T00:00:00Z\"}}";
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
