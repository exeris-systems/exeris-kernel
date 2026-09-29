/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.crypto.CommunityKernelCryptoProvider;
import eu.exeris.kernel.community.crypto.PeerBinderCryptoProviders;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.memory.LeakDetectionMode;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.memory.MemoryStats;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportMode;
import eu.exeris.kernel.spi.transport.TransportStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A verifying client carrier stopped or closed while a connect is between its dial and its stream's
 * registration. Whichever wins, the carrier ends with no registered channel, a connect that
 * returned hands back a connection that is already closed, and every buffer the client allocator
 * lent is back.
 *
 * <p>The connect is held at the client engine's peer step, which runs inside {@code connect} after
 * the dial and the engine build and before the stream exists in the registry. No sleep decides an
 * outcome: each case releases the connect on an observed state of the stop.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@DisplayName("NativeTcpCarrier — a connect racing stop()/close() leaves nothing open")
class NativeTcpCarrierConnectRacesStopTest {

    /** A connect by this thread is held at the peer step, before its stream is registered. */
    private static final String PARKED = "connect-under-test";
    /** A connect by this thread is held just after its stream is registered. */
    private static final String REGISTERED = "connect-past-registration";
    private static final int IDLE_CONNECTIONS = 200;

    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicReference<Object> outcome = new AtomicReference<>();

    private TlsTestCertificate certificate;
    private MemoryAllocator serverAllocator;
    private MemoryAllocator clientAllocator;
    private TransportEngine server;
    private int port;

    @BeforeEach
    void startServer(@TempDir Path material) throws IOException {
        certificate = TlsTestCertificate.generateInto(material);
        serverAllocator = paranoidAllocator();
        clientAllocator = paranoidAllocator();
        port = freePort();
        CommunityKernelCryptoProvider crypto = new CommunityKernelCryptoProvider();
        server = ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, serverAllocator)
                .where(KernelProviders.CRYPTO_PROVIDER, crypto)
                .where(KernelProviders.CURRENT_CONFIG, new MapConfigProvider(Map.of(), Map.of()))
                .call(() -> new NativeTcpTransportProvider().createEngine(new TransportConfig(
                        TransportMode.SERVER, "127.0.0.1", port, 1,
                        certificate.certPath(), certificate.keyPath(), 1024, 30_000)));
        server.setStreamHandler(stream -> { });
        server.start();
    }

    @AfterEach
    void stopServer() {
        release.countDown();
        server.close();
        clientAllocator.close();
        serverAllocator.close();
    }

    @Test
    @DisplayName("a connect released after close() is refused and closes its stream")
    void connectReleasedAfterClose() throws InterruptedException {
        NativeTcpCarrier client = startClient();
        Thread connector = startParkedConnect(client);
        entered.await();

        client.close();
        release.countDown();
        connector.join();

        assertThat(outcome.get()).isInstanceOf(IllegalStateException.class);
        assertThat(client.registeredChannelCount()).as("registered channels after close()").isZero();
        assertEveryLoanReturned();
    }

    @Test
    @DisplayName("a connect released after stop() is refused, and a restart counts nothing for it")
    void connectReleasedAfterStop() throws InterruptedException {
        NativeTcpCarrier client = startClient();
        Thread connector = startParkedConnect(client);
        entered.await();

        client.stop();
        release.countDown();
        connector.join();

        assertThat(outcome.get()).isInstanceOf(IllegalStateException.class);
        assertThat(client.registeredChannelCount()).as("registered channels after stop()").isZero();
        client.start();
        try {
            TransportStats stats = client.stats();
            assertThat(stats.activeConnections()).as("active connections after restart").isZero();
            assertThat(stats.activeStreams()).as("active streams after restart").isZero();
        } finally {
            client.close();
        }
        assertEveryLoanReturned();
    }

    @Test
    @DisplayName("a connect released while stop() is closing its snapshot is swept or refused")
    void connectReleasedDuringSweep() throws InterruptedException {
        NativeTcpCarrier client = startClient();
        List<NativeTcpStream> idle = new ArrayList<>(IDLE_CONNECTIONS);
        for (int i = 0; i < IDLE_CONNECTIONS; i++) {
            idle.add((NativeTcpStream) client.connect("127.0.0.1", port).openStream());
        }
        Thread connector = startParkedConnect(client);
        entered.await();

        Thread stopper = Thread.ofPlatform().start(client::stop);
        boolean sweepObserved = false;
        while (!sweepObserved && stopper.isAlive()) {
            sweepObserved = idle.stream().anyMatch(NativeTcpStream::isClosed);
            Thread.onSpinWait();
        }
        release.countDown();
        connector.join();
        stopper.join();
        client.close();

        assertThat(sweepObserved).as("the connect was released while the sweep ran").isTrue();
        if (outcome.get() instanceof TransportConnection connection) {
            assertThat(connection.isOpen()).as("a connection the racing connect returned").isFalse();
        } else {
            assertThat(outcome.get()).isInstanceOf(IllegalStateException.class);
        }
        assertThat(idle).allMatch(NativeTcpStream::isClosed, "closed by stop()");
        assertThat(client.registeredChannelCount()).as("registered channels after close()").isZero();
        assertEveryLoanReturned();
    }

    @Test
    @DisplayName("a connect registered before stop() and failing after it leaves the counters at zero")
    void connectRegisteredBeforeStopFailsAfterIt() throws InterruptedException {
        NativeTcpCarrier client = startClient();
        CountDownLatch registered = new CountDownLatch(1);
        client.afterClientRegistration(() -> {
            if (REGISTERED.equals(Thread.currentThread().getName())) {
                registered.countDown();
                awaitQuietly(release);
            }
        });
        Thread connector = startConnect(client, REGISTERED);
        registered.await();

        // stop() closes the registered stream in its sweep and clears the reactors; the connect,
        // released after that, finds no reactor to register its channel with.
        client.stop();
        release.countDown();
        connector.join();

        assertThat(outcome.get()).isInstanceOf(IllegalStateException.class);
        assertThat(client.registeredChannelCount()).as("registered channels after stop()").isZero();
        client.start();
        try {
            TransportStats stats = client.stats();
            assertThat(stats.activeConnections()).as("active connections after restart").isZero();
            assertThat(stats.activeStreams()).as("active streams after restart").isZero();
        } finally {
            client.close();
        }
        assertEveryLoanReturned();
    }

    @Test
    @DisplayName("an accept whose registration stop() refuses closes its socket and releases its slot")
    void acceptRefusedByStopReleasesItsSlot() throws Exception {
        NativeTcpCarrier listener = (NativeTcpCarrier) server;
        CountDownLatch held = new CountDownLatch(1);
        AtomicReference<Thread> acceptor = new AtomicReference<>();
        listener.beforeAcceptedRegistration(() -> {
            if (acceptor.compareAndSet(null, Thread.currentThread())) {
                held.countDown();
                awaitQuietly(release);
            }
        });

        try (Socket peer = new Socket("127.0.0.1", port)) {
            held.await();
            // stop() waits out its acceptor join, then seals the registry the held accept registers in.
            listener.stop();
            release.countDown();
            acceptor.get().join();

            assertThat(closedByServer(peer)).as("the accepted socket after a refused registration").isTrue();
        }
        assertThat(listener.registeredChannelCount()).as("registered channels after stop()").isZero();
        listener.start();
        try {
            TransportStats stats = listener.stats();
            assertThat(stats.activeConnections()).as("active connections after restart").isZero();
            assertThat(stats.activeStreams()).as("active streams after restart").isZero();
        } finally {
            listener.close();
        }
        MemoryStats memory = serverAllocator.stats();
        assertThat(memory.releaseCount())
                .as("server allocator releases (allocations %d)", memory.allocationCount())
                .isEqualTo(memory.allocationCount());
    }

    @Test
    @DisplayName("control: a connect released before stop() connects and is closed by it")
    void connectReleasedBeforeStop() throws InterruptedException {
        NativeTcpCarrier client = startClient();
        Thread connector = startParkedConnect(client);
        entered.await();

        release.countDown();
        connector.join();
        assertThat(outcome.get()).isInstanceOf(TransportConnection.class);
        TransportConnection connection = (TransportConnection) outcome.get();
        assertThat(connection.isOpen()).isTrue();

        client.close();

        assertThat(connection.isOpen()).as("the connection after close()").isFalse();
        assertThat(client.registeredChannelCount()).as("registered channels after close()").isZero();
        assertEveryLoanReturned();
    }

    private NativeTcpCarrier startClient() {
        CommunityKernelCryptoProvider crypto = PeerBinderCryptoProviders.withPeerBinder((engine, peer) -> {
            if (PARKED.equals(Thread.currentThread().getName())) {
                entered.countDown();
                awaitQuietly(release);
            }
            engine.expectPeer(peer);
        });
        NativeTcpCarrier client = (NativeTcpCarrier) ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, clientAllocator)
                .where(KernelProviders.CRYPTO_PROVIDER, crypto)
                .where(KernelProviders.CURRENT_CONFIG, certificate.clientTrust())
                .call(() -> new NativeTcpTransportProvider().createEngine(new TransportConfig(
                        TransportMode.CLIENT, "127.0.0.1", 0, 1, null, null, 1024, 30_000)));
        client.start();
        return client;
    }

    private Thread startParkedConnect(NativeTcpCarrier client) {
        return startConnect(client, PARKED);
    }

    private Thread startConnect(NativeTcpCarrier client, String threadName) {
        return Thread.ofPlatform().name(threadName).start(() -> {
            try {
                outcome.set(client.connect("127.0.0.1", port));
            } catch (RuntimeException failure) {
                outcome.set(failure);
            }
        });
    }

    /**
     * Whether the server closed {@code peer}: a read sees end of stream or a reset. A read that times
     * out means the socket is still open, and fails the check rather than passing as an exception.
     */
    private static boolean closedByServer(Socket peer) throws IOException {
        peer.setSoTimeout(10_000);
        try {
            return peer.getInputStream().read() == -1;
        } catch (SocketTimeoutException stillOpen) {
            return false;
        } catch (IOException reset) {
            return true;
        }
    }

    private void assertEveryLoanReturned() {
        MemoryStats stats = clientAllocator.stats();
        assertThat(stats.releaseCount())
                .as("client allocator releases (allocations %d)", stats.allocationCount())
                .isEqualTo(stats.allocationCount());
    }

    private static MemoryAllocator paranoidAllocator() {
        return new CommunityMemoryProvider().createAllocator(
                MemoryProviderConfig.defaults().withLeakDetection(LeakDetectionMode.PARANOID));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
