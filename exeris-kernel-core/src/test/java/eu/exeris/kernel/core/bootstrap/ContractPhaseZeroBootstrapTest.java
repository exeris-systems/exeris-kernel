/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.bootstrap;

import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.spi.bootstrap.SubsystemProvider;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.contract.EntitlementRequirement;
import eu.exeris.kernel.spi.contract.ExecutionContract;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.contract.ContractBreachException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 0 of {@link KernelBootstrap}: the contract gate runs before any subsystem is initialized, and a
 * breach leaves every subsystem untouched (ADR-088 obligation 4, ADR-089 obligation 2).
 */
@DisplayName("KernelBootstrap — Phase 0 contract gate")
class ContractPhaseZeroBootstrapTest {

    private static final AtomicInteger INITIALIZED = new AtomicInteger();
    private static final Map<String, String> CONFIG = new HashMap<>();

    @TempDir
    Path services;

    @BeforeEach
    void reset() {
        INITIALIZED.set(0);
        CONFIG.clear();
        assertThat(Path.of("license-manifest.json"))
                .as("a license-manifest.json in the working directory would be read by every case here")
                .doesNotExist();
    }

    @Test
    @DisplayName("Production, a declared requirement, no manifest: boot fails with EX-LIC-0005 and no subsystem initializes")
    void productionRequirementWithoutManifestHaltsBeforeSubsystems() throws Exception {
        CONFIG.put("environment", "production");

        assertThatThrownBy(() -> bootstrap(true).boot(() -> { }))
                .isExactlyInstanceOf(ContractBreachException.class)
                .satisfies(e -> assertThat(((ContractBreachException) e).errorCode())
                        .isEqualTo(KernelErrorCodes.EX_LIC_0005));
        assertThat(INITIALIZED).hasValue(0);
    }

    @Test
    @DisplayName("Production, a declared requirement, an invalid configured manifest: EX-LIC-0001, no subsystem initializes")
    void invalidManifestHaltsBeforeSubsystems(@TempDir Path dir) throws Exception {
        Path manifest = Files.writeString(dir.resolve("license-manifest.json"), "{\"$schema\":\"tampered\"}");
        CONFIG.put("environment", "production");
        CONFIG.put("license.manifest.path", manifest.toString());

        assertThatThrownBy(() -> bootstrap(true).boot(() -> { }))
                .isExactlyInstanceOf(ContractBreachException.class)
                .satisfies(e -> assertThat(((ContractBreachException) e).errorCode())
                        .isEqualTo(KernelErrorCodes.EX_LIC_0001));
        assertThat(INITIALIZED).hasValue(0);
    }

    @Test
    @DisplayName("Production with no requirement on the classpath boots under the Community contract, bound in scope")
    void communityBootsInProduction() throws Exception {
        CONFIG.put("environment", "production");
        AtomicReference<ExecutionContract> bound = new AtomicReference<>();

        bootstrap(false).boot(() -> bound.set(KernelProviders.executionContract()));

        assertThat(bound.get().edition()).isEqualTo("community");
        assertThat(INITIALIZED).hasValue(1);
        assertThat(KernelProviders.EXECUTION_CONTRACT.isBound()).isFalse();
    }

    @Test
    @DisplayName("Development with a declared requirement and no manifest boots under the Community contract")
    void developmentIsNotGated() throws Exception {
        AtomicReference<ExecutionContract> bound = new AtomicReference<>();

        bootstrap(true).boot(() -> bound.set(KernelProviders.executionContract()));

        assertThat(bound.get().edition()).isEqualTo("community");
        assertThat(INITIALIZED).hasValue(1);
    }

    @Test
    @DisplayName("The kernel profile does not make an environment production")
    void prodProfileAloneIsNotProduction() throws Exception {
        CONFIG.put("kernel.profile", "prod");

        bootstrap(true).boot(() -> { });

        assertThat(INITIALIZED).hasValue(1);
    }

    @Test
    @DisplayName("inspect() initializes nothing, so it does not run the gate and binds no contract")
    void inspectSkipsTheGate() throws Exception {
        CONFIG.put("environment", "production");
        AtomicReference<Boolean> bound = new AtomicReference<>();

        bootstrap(true).inspect(() -> bound.set(KernelProviders.EXECUTION_CONTRACT.isBound()));

        assertThat(bound.get()).isFalse();
        assertThat(INITIALIZED).hasValue(0);
    }

    @Test
    @DisplayName("The contract bound in the kernel scope is the very contract the gate returned")
    void boundContractIsTheGatesContract() throws Exception {
        ExecutionContract decided = new ExecutionContract("EXR-SENTINEL", "OEM", "enterprise", "SUBSCRIPTION",
                "exeris-sku-sentinel", Set.of("gateway.core"), Set.of("production"), 1, null, null, null, 0, null);
        AtomicReference<ExecutionContract> bound = new AtomicReference<>();

        builder(false).contractGate((config, loader) -> decided).build()
                .boot(() -> bound.set(KernelProviders.executionContract()));

        assertThat(bound.get()).isSameAs(decided);
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private KernelBootstrap bootstrap(boolean declareRequirement) throws IOException {
        return builder(declareRequirement).build();
    }

    private KernelBootstrap.Builder builder(boolean declareRequirement) throws IOException {
        Map<String, String> registrations = new HashMap<>();
        registrations.put(ConfigProvider.class.getName(), MapConfigProvider.class.getName());
        registrations.put(SubsystemProvider.class.getName(), ProbeSubsystemProvider.class.getName());
        registrations.put(EntitlementRequirement.class.getName(),
                declareRequirement ? GatewayRequirement.class.getName() : null);
        return KernelBootstrap.builder()
                .selector(BootstrapSelector.all())
                .failurePolicy(SubsystemOrchestrator.FailurePolicy.FAIL_FAST)
                .classLoader(new IsolatingLoader(getClass().getClassLoader(), services, registrations));
    }

    /**
     * Serves exactly the given ServiceLoader registrations (a {@code null} value hides the service) and no
     * classpath {@code license-manifest.json}, whatever the parent loader carries.
     */
    private static final class IsolatingLoader extends ClassLoader {
        private final Map<String, URL> serviceFiles = new HashMap<>();

        IsolatingLoader(ClassLoader parent, Path dir, Map<String, String> registrations) throws IOException {
            super(parent);
            for (Map.Entry<String, String> entry : registrations.entrySet()) {
                String resource = "META-INF/services/" + entry.getKey();
                URL url = null;
                if (entry.getValue() != null) {
                    url = Files.writeString(dir.resolve(entry.getKey()), entry.getValue() + "\n").toUri().toURL();
                }
                serviceFiles.put(resource, url);
            }
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            if (serviceFiles.containsKey(name)) {
                URL url = serviceFiles.get(name);
                return url == null ? Collections.emptyEnumeration() : Collections.enumeration(List.of(url));
            }
            return super.getResources(name);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            return "license-manifest.json".equals(name) ? null : super.getResourceAsStream(name);
        }
    }

    /** Configuration served from {@link #CONFIG}. */
    public static final class MapConfigProvider implements ConfigProvider {
        /** ServiceLoader constructor. */
        public MapConfigProvider() {
            // values come from the test's static map
        }

        @Override public Supplier<KernelSettings> kernelSettings() { return KernelSettings::defaults; }
        @Override public Optional<String> getString(String key) { return Optional.ofNullable(CONFIG.get(key)); }
        @Override public Optional<Integer> getInt(String key) { return Optional.empty(); }
        @Override public Optional<Long> getLong(String key) { return Optional.empty(); }
        @Override public Optional<Boolean> getBoolean(String key) { return Optional.empty(); }
        @Override public <T> Optional<T> get(String key, Class<T> type) { return Optional.empty(); }
        @Override public void watch(String file, String key, Consumer<Object> callback) {
            // no hot reload in this fixture
        }
        @Override public String providerName() { return "MapConfigProvider"; }
    }

    /** One FOUNDATION subsystem that counts its initializations. */
    public static final class ProbeSubsystemProvider implements SubsystemProvider {
        /** ServiceLoader constructor. */
        public ProbeSubsystemProvider() {
            // stateless
        }

        @Override
        public List<Subsystem> getSubsystems(ConfigProvider config) {
            return List.of(new Subsystem() {
                @Override public String name() { return "probe"; }
                @Override public BootstrapPhase phase() { return BootstrapPhase.FOUNDATION; }
                @Override public List<String> dependsOn() { return List.of(); }
                @Override public void initialize() { INITIALIZED.incrementAndGet(); }
                @Override public void start() { /* nothing to start */ }
                @Override public void stop() { /* nothing to stop */ }
            });
        }

        @Override public String moduleName() { return "ContractPhaseZeroProbe"; }
    }

    /** Declares one entitlement-requiring capability. */
    public static final class GatewayRequirement implements EntitlementRequirement {
        @Override
        public Set<String> requiredCapabilities() {
            return Set.of("gateway.core");
        }
    }
}
