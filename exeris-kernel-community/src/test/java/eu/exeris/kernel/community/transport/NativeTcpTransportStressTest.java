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
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportMode;
import eu.exeris.kernel.spi.transport.TransportStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Community TCP: multi-carrier stress & concurrency")
class NativeTcpTransportStressTest {

    private static MemoryAllocator ALLOCATOR;

    private static final int SERVER_REACTOR_COUNT = 4;
    private static final int NUM_CLIENTS = 10;
    private static final int MESSAGES_PER_CLIENT = 5;
    private static final int MESSAGE_SIZE = 512;

    @BeforeAll
    static void setupAllocator() {
        ALLOCATOR = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    }

    @AfterAll
    static void releaseAllocator() {
        ALLOCATOR.close();
    }

    @Test
    @Tag("stress")
    @DisplayName("10 concurrent clients × 4 reactors: round-trip messaging")
    void stressTest10ClientsWith4Reactors() throws Exception {
        int port = nextFreePort();
        byte[] testPayload = new byte[MESSAGE_SIZE];
        Arrays.fill(testPayload, (byte) 42);

        NativeTcpTransportProvider provider = new NativeTcpTransportProvider();
        try (TransportEngine serverEngine = createServerEngine(provider, port, SERVER_REACTOR_COUNT)) {
            AtomicInteger messageCount = new AtomicInteger(0);
            serverEngine.setStreamHandler(stream -> echoUntilStreamCloses(stream, messageCount));
            serverEngine.start();

            List<Future<?>> futures = new ArrayList<>();
            try (ExecutorService executor = new ForkJoinPool(NUM_CLIENTS)) {
                for (int clientId = 0; clientId < NUM_CLIENTS; clientId++) {
                    futures.add(executor.submit(() -> {
                        TransportEngine clientEngine = null;
                        TransportConnection connection = null;
                        TransportStream stream = null;
                        try {
                            clientEngine = createClientEngine(provider, "127.0.0.1");
                            clientEngine.start();

                            connection = clientEngine.connect("127.0.0.1", port);
                            stream = connection.openStream();

                            for (int msg = 0; msg < MESSAGES_PER_CLIENT; msg++) {
                                try (LoanedBuffer buf = ALLOCATOR.allocateNetwork(MESSAGE_SIZE)) {
                                    buf.segment().asSlice(0, MESSAGE_SIZE).copyFrom(
                                        MemorySegment.ofArray(testPayload)
                                    );
                                    buf.setSize(MESSAGE_SIZE);

                                    stream.write(buf.segment(), MESSAGE_SIZE);

                                    try (LoanedBuffer response = ALLOCATOR.allocateNetwork(MESSAGE_SIZE)) {
                                        int read = stream.read(response.segment(), MESSAGE_SIZE);
                                        assertThat(read).isEqualTo(MESSAGE_SIZE);

                                        byte[] echoed = new byte[MESSAGE_SIZE];
                                        response.segment().asSlice(0, MESSAGE_SIZE).asByteBuffer().get(echoed);
                                        assertThat(Arrays.equals(echoed, testPayload)).isTrue();
                                    }
                                }
                            }
                        } catch (Exception ex) {
                            throw new RuntimeException("Client failed", ex);
                        } finally {
                            if (stream != null) {
                                stream.close();
                            }
                            if (connection != null) {
                                connection.close();
                            }
                            if (clientEngine != null) {
                                clientEngine.close();
                            }
                        }
                    }));
                }

                boolean allDone = true;
                for (Future<?> future : futures) {
                    try {
                        future.get(2, TimeUnit.MINUTES);
                    } catch (TimeoutException _) {
                        allDone = false;
                        future.cancel(true);
                    }
                }

                assertThat(allDone).withFailMessage("Not all clients completed in time").isTrue();
            }

            int expectedTotal = NUM_CLIENTS * MESSAGES_PER_CLIENT;
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
            while (messageCount.get() < expectedTotal && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(messageCount.get())
                .withFailMessage("Server did not receive expected message count")
                .isEqualTo(expectedTotal);
        }
    }

    @Test
    @DisplayName("2 concurrent clients × 3 reactors: verify channel owner tracking")
    void stressTest2ClientsWithMultiReactor() {
        int port = nextFreePort();
        int reactorCount = 3;
        byte[] testPayload = new byte[MESSAGE_SIZE];
        Arrays.fill(testPayload, (byte) 99);

        NativeTcpTransportProvider provider = new NativeTcpTransportProvider();
        try (TransportEngine server = createServerEngine(provider, port, reactorCount)) {
            AtomicInteger messageCount = new AtomicInteger(0);
            server.setStreamHandler(stream -> echoUntilStreamCloses(stream, messageCount));
            server.start();

            // Connect 2 clients and send messages
            for (int i = 0; i < 2; i++) {
                TransportEngine client = null;
                TransportConnection conn = null;
                TransportStream stream = null;
                try {
                    client = createClientEngine(provider, "127.0.0.1");
                    client.start();
                    conn = client.connect("127.0.0.1", port);
                    stream = conn.openStream();

                    // Send 3 messages from each client
                    for (int msg = 0; msg < 3; msg++) {
                        try (LoanedBuffer buf = ALLOCATOR.allocateNetwork(MESSAGE_SIZE)) {
                            buf.segment().asSlice(0, MESSAGE_SIZE).copyFrom(MemorySegment.ofArray(testPayload));
                            buf.setSize(MESSAGE_SIZE);
                            stream.write(buf.segment(), MESSAGE_SIZE);

                            try (LoanedBuffer response = ALLOCATOR.allocateNetwork(MESSAGE_SIZE)) {
                                int read = stream.read(response.segment(), MESSAGE_SIZE);
                                assertThat(read).isEqualTo(MESSAGE_SIZE);
                            }
                        }
                    }
                } finally {
                    if (stream != null) {
                        stream.close();
                    }
                    if (conn != null) {
                        conn.close();
                    }
                    if (client != null) {
                        client.close();
                    }
                }
            }

            // Verify server received all messages (6 total: 2 clients × 3 messages)
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
            while (messageCount.get() < 6 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(messageCount.get())
                .withFailMessage("Server did not receive expected message count")
                .isEqualTo(6);
        }
    }

    private TransportEngine createServerEngine(NativeTcpTransportProvider provider, int port, int reactorCount) {
        TransportEngine[] holder = new TransportEngine[1];
        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR)
            .run(() -> holder[0] = provider.createEngine(new TransportConfig(
                TransportMode.SERVER,
                "127.0.0.1",
                port,
                reactorCount,
                null, null,
                1024,
                30_000
            )));
        return holder[0];
    }

    private TransportEngine createClientEngine(NativeTcpTransportProvider provider, String host) {
        TransportEngine[] holder = new TransportEngine[1];
        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR)
            .run(() -> holder[0] = provider.createEngine(new TransportConfig(
                TransportMode.CLIENT,
                host,
                0,
                1,
                null, null,
                1024,
                30_000
            )));
        return holder[0];
    }

    private void echoUntilStreamCloses(TransportStream stream, AtomicInteger messageCount) {
        try (stream;
             LoanedBuffer inbound = ALLOCATOR.allocateNetwork(MESSAGE_SIZE + 1);
             LoanedBuffer outbound = ALLOCATOR.allocateNetwork(MESSAGE_SIZE + 1)) {
            while (true) {
                int read = stream.read(inbound.segment(), MESSAGE_SIZE);
                if (read < 0) {
                    return;
                }
                if (read == 0) {
                    Thread.onSpinWait();
                    continue;
                }
                inbound.setSize(read);
                outbound.segment().asSlice(0, read).copyFrom(inbound.segment().asSlice(0, read));
                outbound.setSize(read);
                stream.write(outbound.segment(), read);
                messageCount.incrementAndGet();
            }
        } catch (RuntimeException _) {
            // Stream can fail during shutdown; treat as terminal for this handler.
        }
    }

    private static int nextFreePort() {
        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            return ((InetSocketAddress) server.getLocalAddress()).getPort();
        } catch (IOException ioException) {
            throw new IllegalStateException("Unable to allocate free TCP port", ioException);
        }
    }

}
