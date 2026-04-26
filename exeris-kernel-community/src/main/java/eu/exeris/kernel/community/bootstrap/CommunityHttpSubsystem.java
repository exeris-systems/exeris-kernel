/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpKernelProviders;
import eu.exeris.kernel.spi.http.HttpMode;
import eu.exeris.kernel.spi.http.HttpProvider;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;
import java.util.function.UnaryOperator;

final class CommunityHttpSubsystem implements Subsystem {

    private HttpProvider provider;
    private HttpConfig httpConfig;
    private DeferredHttpServerEngine serverEngine;
    private DeferredHttpClientEngine clientEngine;
    private boolean running;

    @Override
    public String name() {
        return "http";
    }

    @Override
    public List<String> dependsOn() {
        return List.of("memory");
    }

    @Override
    public BootstrapPhase phase() {
        return BootstrapPhase.RUNTIME;
    }

    @Override
    public void initialize() {
        ConfigProvider configProvider = KernelProviders.CURRENT_CONFIG.get();
        httpConfig = CommunityHttpConfigResolver.buildHttpConfig(configProvider);
        if (httpConfig.mode() == HttpMode.DISABLED) {
            return;
        }

        provider = ServiceLoader.load(HttpProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .max(Comparator.comparingInt(HttpProvider::priority))
                .orElseThrow(() -> new IllegalStateException("No HttpProvider found on classpath"));

        if (httpConfig.mode() == HttpMode.SERVER || httpConfig.mode() == HttpMode.DUAL) {
            serverEngine = new DeferredHttpServerEngine(provider, httpConfig);
        }
        if (httpConfig.mode() == HttpMode.CLIENT || httpConfig.mode() == HttpMode.DUAL) {
            clientEngine = new DeferredHttpClientEngine(provider, httpConfig);
        }
    }

    @Override
    @SuppressWarnings("PMD.CloseResource")
    public void start() {
        if (provider == null || httpConfig == null || httpConfig.mode() == HttpMode.DISABLED) {
            return;
        }

        if (serverEngine != null) {
            PersistenceEngine persistenceEngine = KernelProviders.PERSISTENCE_ENGINE.isBound()
                    ? KernelProviders.persistenceEngine()
                    : null;
            HttpHandler handler = HttpKernelProviders.httpServerHandler()
                .orElseGet(() -> CommunityHttpHealthRoutes.healthHandler(this::readinessStatus, persistenceEngine));
            serverEngine.setHandler(handler);
            serverEngine.start();
        }
        if (clientEngine != null) {
            clientEngine.start();
        }
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        if (clientEngine != null) {
            clientEngine.close();
        }
        if (serverEngine != null) {
            serverEngine.close();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public UnaryOperator<ScopedValue.Carrier> providerBindings() {
        if (provider == null) {
            return Subsystem.super.providerBindings();
        }
        List<CommunityCarrierBindings.Binding<?>> bindings = new ArrayList<>();
        bindings.add(CommunityCarrierBindings.binding(HttpKernelProviders.HTTP_PROVIDER, provider));
        if (serverEngine != null) {
            bindings.add(CommunityCarrierBindings.binding(HttpKernelProviders.HTTP_SERVER_ENGINE, serverEngine));
        }
        if (clientEngine != null) {
            bindings.add(CommunityCarrierBindings.binding(HttpKernelProviders.HTTP_CLIENT_ENGINE, clientEngine));
        }
        return CommunityCarrierBindings.operator(bindings);
    }

    private HttpStatus readinessStatus() {
        if (!running) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        if (serverEngine != null && !serverEngine.isRunning()) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        return HttpStatus.OK;
    }
}
