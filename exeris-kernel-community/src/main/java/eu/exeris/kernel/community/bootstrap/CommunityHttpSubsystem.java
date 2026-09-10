/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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

/**
 * Boots the Community HTTP subsystem: a server engine, a client engine, or both, depending on
 * {@link HttpConfig#mode()}.
 *
 * <p>{@link CommunityHttpConfigResolver} decides the mode before this class sees it — an explicit
 * {@code http.port}/{@code network.port} without {@code http.mode} is read as {@link HttpMode#SERVER};
 * with neither, the subsystem builds no engine and {@link #providerBindings()} binds nothing.
 *
 * <p>Server and client engines are wrapped in {@link DeferredHttpServerEngine} /
 * {@link DeferredHttpClientEngine} at {@code initialize()} but built only at {@code start()}, for the
 * same reason {@link DeferredWebSocketServerEngine} exists: {@code KernelProviders.MEMORY_ALLOCATOR}
 * is not yet bound while a subsystem is initializing, and a real engine resolves its allocator at
 * construction.
 *
 * <p>A server engine with no application-supplied {@code HTTP_SERVER_HANDLER} falls back to
 * {@link CommunityHttpHealthRoutes#healthHandler}, so a kernel boots with a working
 * {@code /health}/{@code /health/ready} surface even before the application installs its own router.
 * When a server engine is built, this subsystem also publishes whatever request body decoder
 * registry the discovered {@link HttpProvider} offers into {@code HTTP_REQUEST_BODY_DECODER_REGISTRY}
 * — optional, since a provider may offer none — so a generated handler can resolve it without DI
 * (ADR-036).
 */
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
            // ADR-036: bind the server-side request body decoder registry into the kernel
            // scope so generated request handlers can resolve it via
            // HttpKernelProviders.httpRequestBodyDecoderRegistry(). Unlike the response
            // encoder (constructor-threaded into the engine), the generated handler has no
            // kernel-provided construction seam and must read the ScopedValue slot — this
            // carrier enricher is the natural server-scope seam (same channel that binds
            // HTTP_SERVER_ENGINE; inherited by every per-request dispatch virtual thread).
            provider.requestBodyDecoderRegistry().ifPresent(registry -> bindings.add(
                    CommunityCarrierBindings.binding(
                            HttpKernelProviders.HTTP_REQUEST_BODY_DECODER_REGISTRY, registry)));
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
