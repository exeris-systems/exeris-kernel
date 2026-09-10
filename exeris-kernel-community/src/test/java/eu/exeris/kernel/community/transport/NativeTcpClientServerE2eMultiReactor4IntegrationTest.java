/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.crypto.CommunityKernelCryptoProvider;
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
import java.nio.file.Path;

import org.junit.jupiter.api.io.TempDir;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@DisplayName("Community TCP: client-server e2e (4 reactors)")
class NativeTcpClientServerE2eMultiReactor4IntegrationTest {

    @TempDir
    /* default */ static Path tlsMaterialDir;

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    @AfterAll
    @SuppressWarnings("unused")
    static void releaseAllocator() {
        ALLOCATOR.close();
    }

    @Test
    @DisplayName("client connect/openStream/write/read round-trip works end-to-end (4 reactors)")
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
                    4,
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
    @DisplayName("client-server TLS round-trip works end-to-end (4 reactors)")
    void clientServerTlsRoundTrip() throws Exception {
        int port = nextFreePort();
        byte[] payload = "kernel-e2e-tls-roundtrip".getBytes(StandardCharsets.UTF_8);
        CountDownLatch handled = new CountDownLatch(1);

        assumeTrue(CommunityTransportTestHarness.isSocketFdAccessible(),
            "SocketChannel FileDescriptor field access is unavailable without --add-opens java.base/sun.nio.ch=ALL-UNNAMED and --add-opens java.base/java.io=ALL-UNNAMED");

        // Generated per run rather than read from ../native-libs/certs, which is in no commit, no
        // .gitignore, no script and no workflow — so the old assumeTrue never held and this test
        // skipped in every build while the summary line read as a pass.
        TlsTestCertificate certificate = TlsTestCertificate.generateInto(tlsMaterialDir);

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
                            4,
                            certificate.certPath(),
                            certificate.keyPath(),
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

    private static int nextFreePort() {
        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            return ((InetSocketAddress) server.getLocalAddress()).getPort();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to allocate free TCP port", e);
        }
    }

}
