/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.community.persistence.RlsConnectionInterceptor;
import eu.exeris.kernel.core.persistence.PersistenceBootstrap;
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
        markRunning(persistenceEngine != null);
    }

    @Override
    public void stop() {
        markRunning(false);
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