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
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportMode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class NativeTcpCarrierIngressIntegrationTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    @AfterAll
    @SuppressWarnings("unused")
    static void releaseAllocator() {
        ALLOCATOR.close();
    }

    @Test
    void ingressIsReadFromSlabAndHandledOnVirtualThread() throws Exception {
        int port = nextFreePort();
        CountDownLatch handled = new CountDownLatch(1);
        AtomicBoolean handledOnVirtualThread = new AtomicBoolean(false);

        NativeTcpTransportProvider provider = new NativeTcpTransportProvider();
        TransportEngine[] holder = new TransportEngine[1];

        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR)
                .run(() -> holder[0] = provider.createEngine(new TransportConfig(
                        TransportMode.SERVER,
                        "127.0.0.1",
                        port,
                        1,
                        null,
                        null,
                        1024,
                        30_000
                )));

        TransportEngine engine = holder[0];
        engine.setStreamHandler(stream -> {
            try (stream) {
                handledOnVirtualThread.set(Thread.currentThread().isVirtual());
            } finally {
                handled.countDown();
            }
        });

        try {
            engine.start();

            try (SocketChannel client = SocketChannel.open()) {
                client.configureBlocking(true);
                client.connect(new InetSocketAddress("127.0.0.1", port));
                client.write(ByteBuffer.wrap("ping".getBytes(StandardCharsets.UTF_8)));

                assertThat(handled.await(5, TimeUnit.SECONDS)).isTrue();
            }
            assertThat(handledOnVirtualThread.get()).isTrue();
        } finally {
            engine.close();
        }
    }

    @Test
    void serverModeCreatesAndUsesMultipleReactors() throws Exception {
        int port = nextFreePort();
        int reactorCount = 3;
        int connectionCount = 6;

        NativeTcpTransportProvider provider = new NativeTcpTransportProvider();
        TransportEngine[] holder = new TransportEngine[1];

        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR)
                .run(() -> holder[0] = provider.createEngine(new TransportConfig(
                        TransportMode.SERVER,
                        "127.0.0.1",
                        port,
                        reactorCount,
                        null,
                        null,
                        1024,
                        30_000
                )));

        TransportEngine engine = holder[0];
        engine.setStreamHandler(stream -> {
        });

        List<SocketChannel> clients = new ArrayList<>();
        try {
            engine.start();

            for (int i = 0; i < connectionCount; i++) {
                SocketChannel client = SocketChannel.open();
                client.configureBlocking(true);
                client.connect(new InetSocketAddress("127.0.0.1", port));
                clients.add(client);
            }

            NativeTcpCarrier carrier = (NativeTcpCarrier) engine;
            waitUntilChannelsAssigned(carrier, connectionCount, Duration.ofSeconds(5));

            List<?> reactors = readPrivateField(carrier, "reactors", List.class);
            Map<?, ?> ownerMap = readPrivateField(carrier, "channelOwner", Map.class);

            assertThat(reactors).hasSize(reactorCount);
            assertThat(ownerMap).hasSizeGreaterThanOrEqualTo(connectionCount);

            Set<?> distinctReactors = ownerMap.values().stream().collect(Collectors.toSet());
            assertThat(distinctReactors).hasSize(reactorCount);
        } finally {
            for (SocketChannel client : clients) {
                client.close();
            }
            engine.close();
        }
    }

    private static int nextFreePort() {
        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            return ((InetSocketAddress) server.getLocalAddress()).getPort();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to allocate free TCP port", e);
        }
    }

    private static void waitUntilChannelsAssigned(NativeTcpCarrier carrier,
                                                   int expectedConnections,
                                                   Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Map<?, ?> ownerMap = readPrivateField(carrier, "channelOwner", Map.class);
            if (ownerMap.size() >= expectedConnections) {
                return;
            }
            LockSupport.parkNanos(25_000_000L);
        }
        throw new AssertionError("Expected channel ownership assignments were not created before timeout");
    }

    private static <T> T readPrivateField(Object target, String name, Class<T> type) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (T) type.cast(field.get(target));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to read field: " + name, e);
        }
    }
}
