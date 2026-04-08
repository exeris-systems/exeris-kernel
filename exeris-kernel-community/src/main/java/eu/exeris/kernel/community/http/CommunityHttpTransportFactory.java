/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.http;

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
        TransportMode transportMode = switch (config.mode()) {
            case SERVER -> TransportMode.SERVER;
            case CLIENT -> TransportMode.CLIENT;
            case DUAL -> TransportMode.DUAL;
            case DISABLED -> TransportMode.DISABLED;
        };
        int transportPort = transportMode == TransportMode.CLIENT ? 0 : port;
        ConfigProvider configProvider = KernelProviders.CURRENT_CONFIG.isBound()
            ? KernelProviders.CURRENT_CONFIG.get()
            : null;
        int reactorCount = CommunityReactorCountResolver.resolve(configProvider);
        TransportConfig transportConfig = new TransportConfig(
                transportMode,
                config.bindHost(),
                transportPort,
                reactorCount,
            resolveTransportProperty(configProvider, "transport.certPath", "network.certPath"),
            resolveTransportProperty(configProvider, "transport.keyPath", "network.keyPath"),
                config.maxConnections(),
                config.idleTimeoutMillis());
        NativeTcpTransportProvider provider = new NativeTcpTransportProvider();
        if (KernelProviders.MEMORY_ALLOCATOR.isBound()) {
            return provider.createEngine(transportConfig);
        }
        return ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator)
                .call(() -> provider.createEngine(transportConfig));
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
