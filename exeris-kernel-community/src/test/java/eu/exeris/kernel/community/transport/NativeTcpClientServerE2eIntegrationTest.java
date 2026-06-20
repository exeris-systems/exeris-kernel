/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.crypto.CommunityKernelCryptoProvider;
import eu.exeris.kernel.community.crypto.SocketChannelFdAccess;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.crypto.KernelCryptoProvider;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportMode;
import eu.exeris.kernel.spi.transport.TransportStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@DisplayName("Community TCP: client-server e2e")
class NativeTcpClientServerE2eIntegrationTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    @AfterAll
    @SuppressWarnings("unused")
    static void releaseAllocator() {
        ALLOCATOR.close();
    }

    @Test
    @DisplayName("client connect/openStream/write/read round-trip works end-to-end")
    void clientServerRoundTrip() throws Exception {
        int port = nextFreePort();
        byte[] payload = "kernel-e2e-roundtrip".getBytes(StandardCharsets.UTF_8);
        CountDownLatch handled = new CountDownLatch(1);

        NativeTcpTransportProvider provider = new NativeTcpTransportProvider();
        TransportEngine[] serverHolder = new TransportEngine[1];
        TransportEngine[] clientHolder = new TransportEngine[1];

        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR).run(() -> {
            serverHolder[0] = provider.createEngine(new TransportConfig(
                    TransportMode.SERVER,
                    "127.0.0.1",
                    port,
                    1,
                    null,
                    null,
                    1024,
                    30_000
            ));

            clientHolder[0] = provider.createEngine(new TransportConfig(
                    TransportMode.CLIENT,
                    "127.0.0.1",
                    0,
                    1,
                    null,
                    null,
                    1024,
                    30_000
            ));
        });

        TransportEngine server = serverHolder[0];
        TransportEngine client = clientHolder[0];

        server.setStreamHandler(stream -> {
            try (LoanedBuffer inbound = ALLOCATOR.allocateNetwork(payload.length);
                 LoanedBuffer outbound = ALLOCATOR.allocateNetwork(payload.length)) {
                int read = stream.read(inbound.segment(), payload.length);
                if (read > 0) {
                    inbound.setSize(read);
                    outbound.segment().asSlice(0, read).copyFrom(inbound.segment().asSlice(0, read));
                    outbound.setSize(read);
                    stream.write(outbound.segment(), read);
                }
            } finally {
                handled.countDown();
            }
        });

        try {
            server.start();
            client.start();

            TransportConnection connection = client.connect("127.0.0.1", port);
            try (TransportStream stream = connection.openStream();
                 LoanedBuffer outbound = ALLOCATOR.allocateNetwork(payload.length);
                 LoanedBuffer inbound = ALLOCATOR.allocateNetwork(payload.length)) {

                outbound.segment().asSlice(0, payload.length).asByteBuffer().put(payload);
                outbound.setSize(payload.length);
                stream.write(outbound.segment(), payload.length);

                int read = stream.read(inbound.segment(), payload.length);
                assertThat(read).isEqualTo(payload.length);

                byte[] echoed = new byte[read];
                inbound.segment().asSlice(0, read).asByteBuffer().get(echoed);
                assertThat(Arrays.equals(echoed, payload)).isTrue();
            }

            assertThat(handled.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            client.close();
            server.close();
        }
    }

    @Test
    @DisplayName("client-server TLS round-trip works end-to-end")
    void clientServerTlsRoundTrip() throws Exception {
        int port = nextFreePort();
        byte[] payload = "kernel-e2e-tls-roundtrip".getBytes(StandardCharsets.UTF_8);
        CountDownLatch handled = new CountDownLatch(1);

        assumeTrue(isSocketFdAccessible(),
            "SocketChannel FileDescriptor field access is unavailable without --add-opens java.base/sun.nio.ch=ALL-UNNAMED and --add-opens java.base/java.io=ALL-UNNAMED");

        Path certPath = Path.of("..", "native-libs", "certs", "server.crt").normalize();
        Path keyPath = Path.of("..", "native-libs", "certs", "server.key").normalize();
        assumeTrue(Files.isRegularFile(certPath) && Files.isRegularFile(keyPath),
                "TLS test cert/key not found — skipping test");

        KernelCryptoProvider cryptoProvider;
        try {
            cryptoProvider = new CommunityKernelCryptoProvider();
        } catch (RuntimeException ex) {
            assumeTrue(false, "OpenSSL runtime unavailable — skipping TLS E2E test");
            return;
        }

        NativeTcpTransportProvider provider = new NativeTcpTransportProvider();
        TransportEngine[] serverHolder = new TransportEngine[1];
        TransportEngine[] clientHolder = new TransportEngine[1];

        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR)
                .where(KernelProviders.CRYPTO_PROVIDER, cryptoProvider)
                .run(() -> {
                    serverHolder[0] = provider.createEngine(new TransportConfig(
                            TransportMode.SERVER,
                            "127.0.0.1",
                            port,
                            1,
                            certPath.toString(),
                            keyPath.toString(),
                            1024,
                            30_000
                    ));

                    clientHolder[0] = provider.createEngine(new TransportConfig(
                            TransportMode.CLIENT,
                            "127.0.0.1",
                            0,
                            1,
                            null,
                            null,
                            1024,
                            30_000
                    ));
                });

        TransportEngine server = serverHolder[0];
        TransportEngine client = clientHolder[0];

        server.setStreamHandler(stream -> {
            try (LoanedBuffer inbound = ALLOCATOR.allocateNetwork(payload.length);
                 LoanedBuffer outbound = ALLOCATOR.allocateNetwork(payload.length)) {
                int read = stream.read(inbound.segment(), payload.length);
                if (read > 0) {
                    inbound.setSize(read);
                    outbound.segment().asSlice(0, read).copyFrom(inbound.segment().asSlice(0, read));
                    outbound.setSize(read);
                    stream.write(outbound.segment(), read);
                }
            } finally {
                handled.countDown();
            }
        });

        try {
            server.start();
            client.start();

            TransportConnection connection = client.connect("127.0.0.1", port);
            try (TransportStream stream = connection.openStream();
                 LoanedBuffer outbound = ALLOCATOR.allocateNetwork(payload.length);
                 LoanedBuffer inbound = ALLOCATOR.allocateNetwork(payload.length)) {

                outbound.segment().asSlice(0, payload.length).asByteBuffer().put(payload);
                outbound.setSize(payload.length);
                stream.write(outbound.segment(), payload.length);

                int read = stream.read(inbound.segment(), payload.length);
                assertThat(read).isEqualTo(payload.length);

                byte[] echoed = new byte[read];
                inbound.segment().asSlice(0, read).asByteBuffer().get(echoed);
                assertThat(Arrays.equals(echoed, payload)).isTrue();
            }

            assertThat(handled.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            client.close();
            server.close();
        }
    }

    @Test
    @DisplayName("TLS ingress drains a burst of multiple records (loop-drain path, PERF-062)")
    void clientServerTlsRecordBurstDrains() throws Exception {
        int port = nextFreePort();
        int messages = 16;
        byte[] unit = "tls-burst-record!".getBytes(StandardCharsets.UTF_8);
        int total = unit.length * messages;
        CountDownLatch handled = new CountDownLatch(1);
        byte[][] received = new byte[1][];

        assumeTrue(isSocketFdAccessible(),
            "SocketChannel FileDescriptor field access is unavailable without --add-opens java.base/sun.nio.ch=ALL-UNNAMED and --add-opens java.base/java.io=ALL-UNNAMED");

        Path certPath = Path.of("..", "native-libs", "certs", "server.crt").normalize();
        Path keyPath = Path.of("..", "native-libs", "certs", "server.key").normalize();
        assumeTrue(Files.isRegularFile(certPath) && Files.isRegularFile(keyPath),
                "TLS test cert/key not found — skipping test");

        KernelCryptoProvider cryptoProvider;
        try {
            cryptoProvider = new CommunityKernelCryptoProvider();
        } catch (RuntimeException ex) {
            assumeTrue(false, "OpenSSL runtime unavailable — skipping TLS E2E test");
            return;
        }

        NativeTcpTransportProvider provider = new NativeTcpTransportProvider();
        TransportEngine[] serverHolder = new TransportEngine[1];
        TransportEngine[] clientHolder = new TransportEngine[1];

        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR)
                .where(KernelProviders.CRYPTO_PROVIDER, cryptoProvider)
                .run(() -> {
                    serverHolder[0] = provider.createEngine(new TransportConfig(
                            TransportMode.SERVER, "127.0.0.1", port, 1,
                            certPath.toString(), keyPath.toString(), 1024, 30_000));
                    clientHolder[0] = provider.createEngine(new TransportConfig(
                            TransportMode.CLIENT, "127.0.0.1", 0, 1, null, null, 1024, 30_000));
                });

        TransportEngine server = serverHolder[0];
        TransportEngine client = clientHolder[0];

        // Server accumulates every byte of the burst. The reactor's readIngress loop-drains all
        // TLS records buffered per readable event; the handler reads them out of the inbound queue.
        server.setStreamHandler(stream -> {
            try (LoanedBuffer inbound = ALLOCATOR.allocateNetwork(total)) {
                int got = 0;
                int idleSpins = 0;
                while (got < total && idleSpins < 100_000) {
                    int r = stream.read(inbound.segment().asSlice(got, total - got), total - got);
                    if (r > 0) {
                        got += r;
                        idleSpins = 0;
                    } else if (r < 0) {
                        break;
                    } else {
                        idleSpins++;
                    }
                }
                byte[] acc = new byte[got];
                inbound.segment().asSlice(0, got).asByteBuffer().get(acc);
                received[0] = acc;
            } finally {
                handled.countDown();
            }
        });

        try {
            server.start();
            client.start();

            TransportConnection connection = client.connect("127.0.0.1", port);
            try (TransportStream stream = connection.openStream();
                 LoanedBuffer outbound = ALLOCATOR.allocateNetwork(unit.length)) {
                // Burst: 16 back-to-back writes → 16 TLS records → the server reactor drains them
                // across one (or few) readable events, exercising multiple loop-drain iterations.
                for (int i = 0; i < messages; i++) {
                    outbound.segment().asSlice(0, unit.length).asByteBuffer().put(unit);
                    outbound.setSize(unit.length);
                    stream.write(outbound.segment(), unit.length);
                }
                assertThat(handled.await(10, TimeUnit.SECONDS)).isTrue();
            }

            assertThat(received[0]).hasSize(total);
            for (int i = 0; i < messages; i++) {
                byte[] slice = Arrays.copyOfRange(received[0], i * unit.length, (i + 1) * unit.length);
                assertThat(Arrays.equals(slice, unit)).as("record %d intact", i).isTrue();
            }
        } finally {
            client.close();
            server.close();
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

    private static boolean isSocketFdAccessible() {
        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = ((InetSocketAddress) server.getLocalAddress()).getPort();
            try (SocketChannel client = SocketChannel.open()) {
                client.connect(new InetSocketAddress("127.0.0.1", port));
                try (SocketChannel accepted = server.accept()) {
                    return SocketChannelFdAccess.canResolveFd(client)
                            && SocketChannelFdAccess.canResolveFd(accepted);
                }
            }
        } catch (IOException ex) {
            return false;
        }
    }
}
