/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportMode;
import eu.exeris.kernel.spi.transport.TransportStream;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.lang.reflect.Field;
import java.net.StandardProtocolFamily;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class CommunityTransportTestHarness {

    private static final long CONNECT_TIMEOUT_SECONDS = 5L;

    private CommunityTransportTestHarness() {
    }

    /**
     * Whether {@code SocketChannel}'s {@code fd} field is reachable, which the FFM socket seam
     * needs. Gated by {@code --add-opens java.base/sun.nio.ch} and {@code java.base/java.io} — a
     * real environment capability, unlike the certificate assumption this module used to carry.
     *
     * <p>Lives here because four suites were about to hold four identical copies; the fourth is
     * what made it worth moving rather than repeating.
     *
     * @return {@code true} when the field can be read reflectively
     */
    static boolean isSocketFdAccessible() {
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.INET)) {
            Field field = channel.getClass().getDeclaredField("fd");
            field.setAccessible(true);
            return field.get(channel) != null;
        } catch (IOException | ReflectiveOperationException | RuntimeException probeFailure) {
            return false;
        }
    }

    static Pair openLoopbackPair(MemoryAllocator allocator, boolean drainingServerHandler) {
        return openLoopbackPair(allocator, drainingServerHandler, 1);
    }

    static Pair openLoopbackPair(MemoryAllocator allocator, boolean drainingServerHandler, int reactorCount) {
        int port = nextFreePort();
        NativeTcpTransportProvider provider = new NativeTcpTransportProvider();

        TransportEngine[] serverHolder = new TransportEngine[1];
        TransportEngine[] clientHolder = new TransportEngine[1];

        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator).run(() -> {
            serverHolder[0] = provider.createEngine(new TransportConfig(
                    TransportMode.SERVER,
                    "127.0.0.1",
                    port,
                    reactorCount,
                    null,
                    null,
                    1024,
                    30_000
            ));

            clientHolder[0] = provider.createEngine(new TransportConfig(
                    TransportMode.CLIENT,
                    "127.0.0.1",
                    0,
                    reactorCount,
                    null,
                    null,
                    1024,
                    30_000
            ));
        });

        TransportEngine serverEngine = serverHolder[0];
        TransportEngine clientEngine = clientHolder[0];

        AtomicReference<TransportConnection> serverConnectionRef = new AtomicReference<>();
        CountDownLatch serverConnectionReady = new CountDownLatch(1);

        serverEngine.setConnectionHandler(connection -> {
            serverConnectionRef.set(connection);
            serverConnectionReady.countDown();
        });

        if (drainingServerHandler) {
            serverEngine.setStreamHandler(stream -> drainStream(stream, allocator));
        } else {
            serverEngine.setStreamHandler(stream -> {
            });
        }

        serverEngine.start();
        clientEngine.start();

        TransportConnection clientConnection = clientEngine.connect("127.0.0.1", port);
        try {
            if (!serverConnectionReady.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Server did not accept connection within timeout");
            }
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for server connection", interruptedException);
        }

        TransportConnection serverConnection = Objects.requireNonNull(
                serverConnectionRef.get(),
                "Server connection not captured"
        );

        TransportStream clientStream = clientConnection.openStream();
        TransportStream serverStream = serverConnection.openStream();

        return new Pair(
            serverEngine,
                clientEngine,
                serverConnection,
                clientConnection,
                serverStream,
                clientStream
        );
    }

    /**
     * Test-only egress hold for the {@code reset()}-abandon discriminator (issue #180). Pins the
     * {@code writer}'s outbound consumer slot from a dedicated virtual thread so a subsequent
     * {@link TransportStream#queueWrite} leaves its payload genuinely pending (no synchronous
     * flush). The returned handle, on {@code close()}, completes the already-requested abortive
     * {@code reset()} on the slot-owning thread — discarding the pending write before any carrier
     * reactor can flush it — and then releases the slot.
     */
    static AutoCloseable holdOutboundConsumer(TransportStream writer) {
        NativeTcpStream stream = (NativeTcpStream) writer;
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofVirtual().name("tck-egress-hold").start(() -> {
            Thread self = Thread.currentThread();
            while (!stream.tryPinOutboundConsumerForTest(self)) {
                Thread.yield();
            }
            acquired.countDown();
            awaitUninterruptibly(release);
            stream.completeAbortAndUnpinOutboundConsumerForTest(self);
        });
        awaitUninterruptibly(acquired);
        return () -> {
            release.countDown();
            try {
                holder.join(TimeUnit.SECONDS.toMillis(2));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            // Surface a hung abort as a test failure rather than silently entering the
            // peer-read loop before the queued write was discarded.
            if (holder.isAlive()) {
                throw new AssertionError("tck-egress-hold VT did not complete the abort within 2s");
            }
        };
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    latch.await();
                    return;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static int nextFreePort() {
        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            return ((InetSocketAddress) server.getLocalAddress()).getPort();
        } catch (IOException ioException) {
            throw new IllegalStateException("Unable to allocate free TCP port", ioException);
        }
    }

    private static void drainStream(TransportStream stream, MemoryAllocator allocator) {
        try (LoanedBuffer sink = allocator.allocateNetwork(64)) {
            while (true) {
                int read = stream.read(sink.segment(), 64);
                if (read <= 0) {
                    return;
                }
            }
        } catch (RuntimeException _) {
            // best-effort drain in test harness
        }
    }

    static final class Pair {

        private final TransportEngine serverEngine;
        private final TransportEngine clientEngine;
        private final TransportConnection serverConnection;
        private final TransportConnection clientConnection;
        private final TransportStream serverStream;
        private final TransportStream clientStream;

        private Pair(TransportEngine serverEngine,
                     TransportEngine clientEngine,
                     TransportConnection serverConnection,
                     TransportConnection clientConnection,
                     TransportStream serverStream,
                     TransportStream clientStream) {
            this.serverEngine = serverEngine;
            this.clientEngine = clientEngine;
            this.serverConnection = serverConnection;
            this.clientConnection = clientConnection;
            this.serverStream = serverStream;
            this.clientStream = clientStream;
        }

        TransportEngine serverEngine() {
            return serverEngine;
        }

        TransportEngine clientEngine() {
            return clientEngine;
        }

        TransportConnection serverConnection() {
            return serverConnection;
        }

        TransportConnection clientConnection() {
            return clientConnection;
        }

        TransportStream serverStream() {
            return serverStream;
        }

        TransportStream clientStream() {
            return clientStream;
        }

        void closeConnections() {
            try {
                serverConnection.close();
            } catch (RuntimeException _) {
                // best effort
            }
            try {
                clientConnection.close();
            } catch (RuntimeException _) {
                // best effort
            }
        }

        void closeEngines() {
            try {
                clientEngine.close();
            } catch (RuntimeException _) {
                // best effort
            }
            try {
                serverEngine.close();
            } catch (RuntimeException _) {
                // best effort
            }
        }
    }
}