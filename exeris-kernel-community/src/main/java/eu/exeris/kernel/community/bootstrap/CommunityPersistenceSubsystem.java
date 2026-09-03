/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.community.persistence.CommunityAdmissionConfig;
import eu.exeris.kernel.community.persistence.RlsConnectionInterceptor;
import eu.exeris.kernel.core.persistence.PersistenceBootstrap;
import eu.exeris.kernel.core.telemetry.JfrCommitGate;
import eu.exeris.kernel.core.telemetry.JfrEventCommitter;
import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.persistence.ConnectionInterceptor;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.PersistenceProvider;

import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;
import java.util.function.UnaryOperator;

final class CommunityPersistenceSubsystem extends AbstractCommunitySubsystem {

    private static final long DEFAULT_CONNECTION_TIMEOUT_MS = 250L;  // was 5_000L; fail-fast on pool exhaustion
    private static final long DEFAULT_IDLE_TIMEOUT_MS = 600_000L;
    private static final long DEFAULT_MAX_LIFETIME_MS = 1_800_000L;
    private static final int DEFAULT_MIN_IDLE_CONNECTIONS = 1;
    private static final int DEFAULT_MAX_TENANT_POOLS = 32;

    private PersistenceProvider persistenceProvider;
    private PersistenceEngine persistenceEngine;
    // ADR-035 / VT-JFR safety: commits admission + connection-acquire JFR events off the request
    // virtual thread (JDK 26+35 carrier-bound-buffer crash). Owned here because this subsystem owns
    // the admission path that emits them; lifetime bounded to RUNNING.
    private JfrEventCommitter jfrCommitter;

    @Override
    public String name() {
        return "persistence";
    }

    @Override
    public List<String> dependsOn() {
        return List.of("memory");
    }

    @Override
    public BootstrapPhase phase() {
        return BootstrapPhase.SERVICES;
    }

    @Override
    public void initialize() {
        ConfigProvider configProvider = KernelProviders.CURRENT_CONFIG.get();
        // ADR-035: resolve tunable admission thresholds once at bootstrap, then register a
        // hot-reload watch. Community's watch() is a no-op (startup-only); Enterprise swaps
        // CURRENT atomically on file change via the @Dynamic watcher. The admission controller
        // reads CommunityAdmissionConfig.CURRENT at each decision, so a swap takes effect on
        // the next call with no locks. seal() runs after all initialize() calls (KernelBootstrap),
        // so this registration is always before the registry is sealed.
        CommunityAdmissionConfig.CURRENT = CommunityAdmissionConfig.fromConfigProvider(configProvider);
        configProvider.watch(
                CommunityAdmissionConfig.CONFIG_FILE,
                CommunityAdmissionConfig.KEY_PREFIX,
                _ -> CommunityAdmissionConfig.CURRENT = CommunityAdmissionConfig.fromConfigProvider(configProvider));
        persistenceProvider = resolveProvider();
        PersistenceConfig config = CommunityPersistenceConfigResolver.buildPersistenceConfig(
                configProvider,
                DEFAULT_CONNECTION_TIMEOUT_MS,
                DEFAULT_IDLE_TIMEOUT_MS,
                DEFAULT_MAX_LIFETIME_MS,
                DEFAULT_MIN_IDLE_CONNECTIONS,
                DEFAULT_MAX_TENANT_POOLS);
        persistenceEngine = PersistenceBootstrap.load(persistenceProvider, config, interceptors(config));
        // Community-internal handoff: subsystems whose initialize() runs in a later phase
        // (e.g. flow during RUNTIME) cannot yet see PERSISTENCE_ENGINE via ScopedValue.
        // The bootstrap-services registry bridges that gap without exposing internals
        // across the SPI boundary. See ADR-022 §4 and CommunityBootstrapServices Javadoc.
        CommunityBootstrapServices.setSharedPersistenceEngine(persistenceEngine);
    }

    @Override
    public void start() {
        // Install the off-thread JFR committer before marking running, so the first admission
        // decision already routes its commit onto the platform thread.
        jfrCommitter = JfrEventCommitter.start();
        JfrCommitGate.install(jfrCommitter);
        markRunning(persistenceEngine != null);
    }

    @Override
    public void stop() {
        markRunning(false);
        // Clear the gate first (no new events enqueue), then drain in-flight on the platform thread.
        JfrCommitGate.clear();
        if (jfrCommitter != null) {
            jfrCommitter.close();
        }
        CommunityBootstrapServices.clearSharedPersistenceEngine();
        if (persistenceEngine != null) {
            persistenceEngine.close();
        }
    }

    @Override
    public UnaryOperator<ScopedValue.Carrier> providerBindings() {
        if (persistenceProvider == null || persistenceEngine == null) {
            return defaultProviderBindings();
        }
        return CommunityCarrierBindings.operator(
                CommunityCarrierBindings.binding(KernelProviders.PERSISTENCE_PROVIDER, persistenceProvider),
                CommunityCarrierBindings.binding(KernelProviders.PERSISTENCE_ENGINE, persistenceEngine)
        );
    }

    private static PersistenceProvider resolveProvider() {
        return ServiceLoader.load(PersistenceProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .max(Comparator.comparingInt(PersistenceProvider::priority)
                        .thenComparing(provider -> provider.getClass().getName()))
                .orElseThrow(() -> PersistenceProviderException
                        .noProviderAvailable("No PersistenceProvider found on classpath"));
    }

    private static List<ConnectionInterceptor> interceptors(PersistenceConfig config) {
        if (config.rlsEnabled()) {
            return List.of(RlsConnectionInterceptor.INSTANCE);
        }
        return List.of();
    }
}