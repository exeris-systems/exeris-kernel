/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.websocket;

import eu.exeris.kernel.community.transport.CommunityAdmissionCeilingResolver;
import eu.exeris.kernel.community.transport.CommunityReactorCountResolver;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.community.transport.NativeTcpTransportProvider;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportMode;
import eu.exeris.kernel.spi.websocket.WebSocketConfig;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * Builds the TCP carrier a WebSocket engine listens on.
 *
 * <p>Deliberately the same shape as {@code CommunityHttpTransportFactory}, including the
 * ScopedValue dance around an unbound allocator: a duplex endpoint that a tool embeds without
 * booting the kernel is exactly the case where {@code MEMORY_ALLOCATOR} is not already bound.
 *
 * <p>Which is why {@link #resolveAllocator()} creates one rather than refusing. It used to throw,
 * and that made the sentence above describe a scenario the code rejected: ADR-084 §1 exists so the
 * platform can obtain an endpoint "without booting the kernel", from two public calls, and the
 * first of those two calls answered {@code IllegalStateException: KernelProviders.MEMORY_ALLOCATOR
 * is not bound}. The fallback mirrors {@code CommunityHttpClientEngine.resolveAllocator}, which had
 * already solved the identical problem for the embedded client.
 */
final class CommunityWebSocketTransportFactory {

    private CommunityWebSocketTransportFactory() {
    }

    /**
     * The ambient allocator when the caller has one, a private one when it does not.
     *
     * <p>{@link #allocatorIsOurs()} says which happened, because ownership follows: an allocator
     * this factory created must be closed by the engine that holds it, and an ambient one must not
     * be — closing another component's allocator is the failure the engine's own field comment
     * warns about.
     *
     * @return an allocator; never {@code null}
     */
    /* default */ static MemoryAllocator resolveAllocator() {
        if (KernelProviders.MEMORY_ALLOCATOR.isBound()) {
            return KernelProviders.MEMORY_ALLOCATOR.get();
        }
        return new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    }

    /** Whether {@link #resolveAllocator()} would create an allocator rather than borrow one. */
    /* default */ static boolean allocatorIsOurs() {
        return !KernelProviders.MEMORY_ALLOCATOR.isBound();
    }

    /* default */ static TransportEngine buildTransport(WebSocketConfig config, int port,
                                                        MemoryAllocator allocator) {
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

    /* default */ static TransportConfig buildTransportConfig(WebSocketConfig config, int port,
                                                              ConfigProvider configProvider) {
        // idleTimeoutMillis is carried through rather than dropped: it is what NativeTcpIdleReaper
        // enforces, and it is therefore this binding's ACTUAL dead-peer detection — see the note on
        // keepAliveIntervalMillis in CommunityWebSocketProvider.
        return new TransportConfig(
                TransportMode.SERVER,
                config.bindHost(),
                port,
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
        } catch (IOException unavailable) {
            throw new IllegalStateException("Unable to allocate free TCP port", unavailable);
        }
    }
}
