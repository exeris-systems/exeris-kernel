/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.community.transport.CommunityReactorCountResolver;
import eu.exeris.kernel.core.bootstrap.BootstrapProviderSelector;
import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportMode;
import eu.exeris.kernel.spi.transport.TransportProvider;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

final class CommunityTransportSubsystem extends AbstractCommunitySubsystem {

    private static final System.Logger LOG = System.getLogger(CommunityTransportSubsystem.class.getName());

    private TransportProvider transportProvider;
    private TransportEngine transportEngine;
    private TransportConfig transportConfig;

    @Override
    public String name() {
        return "transport";
    }

    @Override
    public List<String> dependsOn() {
        return List.of("memory", "crypto");
    }

    @Override
    public BootstrapPhase phase() {
        return BootstrapPhase.SERVICES;
    }

    @Override
    public void initialize() {
        ConfigProvider configProvider = KernelProviders.CURRENT_CONFIG.get();
        transportProvider = BootstrapProviderSelector.loadHighestPriority(
                        TransportProvider.class,
                        Comparator.comparingInt(TransportProvider::priority),
                        TransportProvider::isAvailable)
                .orElse(null);

        if (transportProvider == null) {
            return;
        }
        transportConfig = buildTransportConfig(configProvider);
        if (transportConfig.mode() == TransportMode.DISABLED) {
            return;
        }
        transportEngine = transportProvider.createEngine(transportConfig);
        if (transportEngine.mode() == TransportMode.SERVER || transportEngine.mode() == TransportMode.DUAL) {
            transportEngine.setStreamHandler(eu.exeris.kernel.spi.transport.TransportStream::close);
            LOG.log(System.Logger.Level.WARNING,
                    "CommunityTransportSubsystem: transport running with default stream handler "
                    + "(streams will be closed immediately). No stream handler was configured "
                    + "during bootstrap; inbound streams will be dropped until a handler is installed.");
        }
        transportEngine.setConnectionHandler(connection -> {
        });
    }

    @Override
    public void start() {
        if (transportEngine == null) {
            return;
        }
        transportEngine.start();
        markRunning(true);
    }

    @Override
    public void stop() {
        markRunning(false);
        if (transportEngine != null) {
            transportEngine.close();
        }
    }

    @Override
    public UnaryOperator<ScopedValue.Carrier> providerBindings() {
        if (transportProvider == null || transportEngine == null
                || transportConfig == null || transportConfig.mode() == TransportMode.DISABLED) {
            return defaultProviderBindings();
        }
        return CommunityCarrierBindings.operator(
            CommunityCarrierBindings.binding(KernelProviders.TRANSPORT_PROVIDER, transportProvider),
            CommunityCarrierBindings.binding(KernelProviders.TRANSPORT_ENGINE, transportEngine)
        );
    }

    private static TransportConfig buildTransportConfig(ConfigProvider configProvider) {
        ConfigProvider.KernelSettings settings = configProvider.kernelSettings().get();
        ConfigProvider.NetworkSettings network = settings.network();

        TransportMode mode = resolveMode(configProvider);
        if (mode == TransportMode.DISABLED) {
            return TransportConfig.disabled();
        }

        int reactors = CommunityReactorCountResolver.resolve(configProvider);

        String bindAddress = configProvider.getString("transport.bindAddress")
            .orElse(TransportConfig.DEFAULT_BIND_ADDRESS);

        int port = configProvider.getInt("transport.port")
            .orElse(network.port());

        int maxConnections = configProvider.getInt("transport.maxConnections")
            .orElse(4096);

        long idleTimeoutMillis = configProvider.getLong("transport.idleTimeoutMillis")
            .orElse(30_000L);

        String certPath = configProvider.getString("transport.certPath")
                .orElse(configProvider.getString("network.certPath").orElse(null));
        String keyPath = configProvider.getString("transport.keyPath")
                .orElse(configProvider.getString("network.keyPath").orElse(null));

        return new TransportConfig(
                mode,
            bindAddress,
            port,
                reactors,
                certPath,
                keyPath,
            maxConnections,
            idleTimeoutMillis
        );
    }

    private static TransportMode resolveMode(ConfigProvider configProvider) {
        String configuredMode = configProvider.getString("transport.mode")
                .orElse(configProvider.getString("network.transportMode").orElse(null));

        if (configuredMode == null || configuredMode.isBlank()) {
            return TransportMode.DISABLED;
        }

        return switch (configuredMode.strip().toUpperCase(Locale.ROOT)) {
            case "SERVER" -> TransportMode.SERVER;
            case "CLIENT" -> TransportMode.CLIENT;
            case "DUAL" -> TransportMode.DUAL;
            case "DISABLED" -> TransportMode.DISABLED;
            default -> throw new IllegalArgumentException("Unsupported transport.mode: " + configuredMode);
        };
    }
}
