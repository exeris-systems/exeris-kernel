/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.StreamHandler;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportMode;
import eu.exeris.kernel.tck.contract.transport.AbstractTransportEngineTck;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;

@DisplayName("Community: NativeTcpCarrier TCK")
class CommunityNativeTcpCarrierTckTest extends AbstractTransportEngineTck {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    @AfterAll
    @SuppressWarnings("unused")
    static void releaseAllocator() {
        ALLOCATOR.close();
    }

    @Override
    protected TransportEngine createEngineWithConnectionCeiling(int maxConnections, int port) {
        TransportConfig config = new TransportConfig(
                TransportMode.SERVER, "127.0.0.1", port, 1, null, null, maxConnections, 30_000);
        TransportEngine[] holder = new TransportEngine[1];
        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR)
                .run(() -> holder[0] = new NativeTcpTransportProvider().createEngine(config));
        // Accepted connections are parked, never served: closing them here would free slots
        // underneath the ceiling and the refusal path would never be reached.
        holder[0].setStreamHandler(stream -> { });
        return holder[0];
    }

    @Override
    protected TransportEngine createEngineWithHandler(int port, StreamHandler handler) {
        TransportConfig config = new TransportConfig(
                TransportMode.SERVER, "127.0.0.1", port, 1, null, null, 16, 30_000);
        TransportEngine[] holder = new TransportEngine[1];
        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR)
                .run(() -> holder[0] = new NativeTcpTransportProvider().createEngine(config));
        holder[0].setStreamHandler(handler);
        return holder[0];
    }

    @Override
    protected TransportEngine createEngine() {
        NativeTcpTransportProvider provider = new NativeTcpTransportProvider();
        TransportConfig config = new TransportConfig(
                TransportMode.SERVER,
                "127.0.0.1",
                nextFreePort(),
                2,
                null,
                null,
                1024,
                30_000
        );

        TransportEngine[] holder = new TransportEngine[1];
        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR)
                .run(() -> holder[0] = provider.createEngine(config));

        holder[0].setStreamHandler(stream -> {
            stream.close();
        });
        return holder[0];
    }

    @Override
    protected TransportMode expectedMode() {
        return TransportMode.SERVER;
    }

    private static int nextFreePort() {
        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            return ((InetSocketAddress) server.getLocalAddress()).getPort();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to allocate free TCP port", e);
        }
    }
}
