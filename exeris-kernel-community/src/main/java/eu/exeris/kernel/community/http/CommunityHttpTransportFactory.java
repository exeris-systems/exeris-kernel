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
import eu.exeris.kernel.spi.http.HttpMode;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportMode;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * Package-private builder for the {@link TransportEngine} and {@link TransportConfig} that back a
 * Community HTTP server or client engine — the seam that translates {@link HttpConfig} plus the
 * bound {@link ConfigProvider} into the transport subsystem's own configuration shape.
 */
final class CommunityHttpTransportFactory {

    private CommunityHttpTransportFactory() {
    }

    /**
     * Which side of the wire the engine that owns the transport serves.
     *
     * <p>An HTTP engine is a server or a client, never both, whatever {@link HttpMode} the subsystem
     * runs in: {@code HttpMode.DUAL} builds one engine of each. Deriving the transport's mode from
     * the subsystem's would give the client engine a listener it never starts and hand its outbound
     * connections the listener's certificate material.
     */
    /* default */ enum Role {
        /** The engine accepts connections on its port and presents the listener's material. */
        SERVER,
        /** The engine only dials out and binds no port. */
        CLIENT
    }

    /**
     * The bound memory allocator, required.
     *
     * @return the allocator bound at {@link KernelProviders#MEMORY_ALLOCATOR}
     * @throws IllegalStateException if no allocator is bound
     */
    /* default */ static MemoryAllocator resolveAllocator() {
        if (!KernelProviders.MEMORY_ALLOCATOR.isBound()) {
            throw new IllegalStateException("KernelProviders.MEMORY_ALLOCATOR is not bound");
        }
        return KernelProviders.MEMORY_ALLOCATOR.get();
    }

    /**
     * Builds and returns a {@link TransportEngine} configured from {@code config}, binding
     * {@code allocator} to {@link KernelProviders#MEMORY_ALLOCATOR} for the call when nothing is
     * already bound there.
     *
     * @param config    the HTTP engine configuration to derive transport settings from
     * @param port      the port to bind, ignored for a client transport
     * @param allocator the allocator to bind if none is already bound
     * @param role      the side of the wire the owning engine serves
     * @return a transport engine ready to {@code start()}
     */
    /* default */ static TransportEngine buildTransport(HttpConfig config, int port, MemoryAllocator allocator,
                                                        Role role) {
        ConfigProvider configProvider = KernelProviders.CURRENT_CONFIG.isBound()
            ? KernelProviders.CURRENT_CONFIG.get()
            : null;
        TransportConfig transportConfig = buildTransportConfig(config, port, configProvider, role);
        NativeTcpTransportProvider provider = new NativeTcpTransportProvider();
        if (KernelProviders.MEMORY_ALLOCATOR.isBound()) {
            return provider.createEngine(transportConfig);
        }
        return ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator)
                .call(() -> provider.createEngine(transportConfig));
    }

    /**
     * Builds the transport configuration of one HTTP engine.
     *
     * <p>Separate from {@link #buildTransport} so the operator-facing values on it can be asserted
     * without opening a socket. The HTTP listener carries its own {@code TransportConfig} rather
     * than sharing the transport subsystem's, so every key both paths honour has to be read here
     * as well — one read in the subsystem alone would apply to a standalone carrier and silently
     * not to the server almost every deployment actually runs.
     *
     * <p>The transport's mode follows {@code role}, not {@link HttpConfig#mode()}: a server engine
     * gets a {@code SERVER} transport with the listener's port and certificate material, and a
     * client engine gets a {@code CLIENT} transport with port {@code 0} and no material. Only
     * {@link HttpMode#DISABLED} carries over, as a {@code DISABLED} transport.
     *
     * @param config         the HTTP engine configuration
     * @param port           the listener port, ignored for a client
     * @param configProvider the bound configuration, or {@code null}
     * @param role           the side of the wire the owning engine serves
     * @return the transport configuration
     */
    /* default */ static TransportConfig buildTransportConfig(HttpConfig config,
                                                              int port,
                                                              ConfigProvider configProvider,
                                                              Role role) {
        if (config.mode() == HttpMode.DISABLED) {
            return transportConfig(config, TransportMode.DISABLED, port, configProvider, null, null);
        }
        if (role == Role.CLIENT) {
            return transportConfig(config, TransportMode.CLIENT, 0, configProvider, null, null);
        }
        return transportConfig(config, TransportMode.SERVER, port, configProvider,
                resolveTransportProperty(configProvider, "transport.certPath", "network.certPath"),
                resolveTransportProperty(configProvider, "transport.keyPath", "network.keyPath"));
    }

    private static TransportConfig transportConfig(HttpConfig config,
                                                   TransportMode transportMode,
                                                   int port,
                                                   ConfigProvider configProvider,
                                                   String certPath,
                                                   String keyPath) {
        return new TransportConfig(
                transportMode,
                config.bindHost(),
                port,
                CommunityReactorCountResolver.resolve(configProvider),
                certPath,
                keyPath,
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

    /**
     * An ephemeral TCP port free at the moment of the call, obtained by binding and immediately
     * releasing an OS-assigned port. This is how a server engine configured with port {@code 0}
     * resolves the concrete port it will bind, since the transport configuration it builds carries
     * a definite port number rather than "any". Racy by nature — nothing reserves the port between
     * this call returning and the later bind — so a caller relies on it for port <em>selection</em>,
     * not for a guarantee that the port stays free until bound.
     *
     * @return a port number free at the time of the call
     * @throws IllegalStateException if no ephemeral port could be allocated
     */
    /* default */ static int nextFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to allocate free TCP port", e);
        }
    }
}
