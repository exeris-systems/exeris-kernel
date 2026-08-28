/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.transport.CommunityAdmissionCeilingResolver;
import eu.exeris.kernel.community.transport.CommunityReactorCountResolver;
import eu.exeris.kernel.community.transport.NativeTcpTransportProvider;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportMode;

import java.io.IOException;
import java.net.ServerSocket;

final class CommunityHttpTransportFactory {

    private CommunityHttpTransportFactory() {
    }

    /* default */ static MemoryAllocator resolveAllocator() {
        if (!KernelProviders.MEMORY_ALLOCATOR.isBound()) {
            throw new IllegalStateException("KernelProviders.MEMORY_ALLOCATOR is not bound");
        }
        return KernelProviders.MEMORY_ALLOCATOR.get();
    }

    /* default */ static TransportEngine buildTransport(HttpConfig config, int port, MemoryAllocator allocator) {
        ConfigProvider configProvider = KernelProviders.CURRENT_CONFIG.isBound()
            ? KernelProviders.CURRENT_CONFIG.get()
            : null;
        TransportConfig transportConfig = buildTransportConfig(config, port, configProvider);
        NativeTcpTransportProvider provider = new NativeTcpTransportProvider();
        if (KernelProviders.MEMORY_ALLOCATOR.isBound()) {
            return provider.createEngine(transportConfig);
        }
        return ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator)
                .call(() -> provider.createEngine(transportConfig));
    }

    /**
     * Builds the listener's transport configuration.
     *
     * <p>Separate from {@link #buildTransport} so the operator-facing values on it can be asserted
     * without opening a socket. The HTTP listener carries its own {@code TransportConfig} rather
     * than sharing the transport subsystem's, so every key both paths honour has to be read here
     * as well — one read in the subsystem alone would apply to a standalone carrier and silently
     * not to the server almost every deployment actually runs.
     */
    /* default */ static TransportConfig buildTransportConfig(HttpConfig config,
                                                              int port,
                                                              ConfigProvider configProvider) {
        TransportMode transportMode = switch (config.mode()) {
            case SERVER -> TransportMode.SERVER;
            case CLIENT -> TransportMode.CLIENT;
            case DUAL -> TransportMode.DUAL;
            case DISABLED -> TransportMode.DISABLED;
        };
        int transportPort = transportMode == TransportMode.CLIENT ? 0 : port;
        return new TransportConfig(
                transportMode,
                config.bindHost(),
                transportPort,
                CommunityReactorCountResolver.resolve(configProvider),
                resolveTransportProperty(configProvider, "transport.certPath", "network.certPath"),
                resolveTransportProperty(configProvider, "transport.keyPath", "network.keyPath"),
                config.maxConnections(),
                config.idleTimeoutMillis(),
                CommunityAdmissionCeilingResolver.resolve(configProvider));
    }

    private static String resolveTransportProperty(ConfigProvider configProvider,
                                                   String primaryKey,
                                                   String fallbackKey) {
        if (configProvider == null) {
            return null;
        }
        return configProvider.getString(primaryKey)
                .orElse(configProvider.getString(fallbackKey).orElse(null));
    }

    /* default */ static int nextFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to allocate free TCP port", e);
        }
    }
}
