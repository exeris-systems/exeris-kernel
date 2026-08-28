/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Carrier ingress behaviour: registration, established-callback ordering, and channel ownership.
 *
 * <p><b>Known coverage gap, recorded rather than implied.</b> {@code fireEstablishedOnce}'s CAS
 * guard is reachable more than once only on the <em>TLS</em> path, where
 * {@code readTlsIngressFromFd()} calls it per ingress read; on plaintext it is reached from
 * {@code markRegistrationReady()}, once per connection. Every engine in this class is plaintext,
 * so removing the CAS leaves the whole class green — measured, not assumed. Nothing anywhere else
 * asserts on the established count either. <b>Closed by
 * {@link NativeTcpTlsEstablishedOnceIntegrationTest}</b>, which drives the TLS path and reddens
 * (expected 1, was 3) under exactly the mutation that leaves this class green.
 */
@Tag("integration")
@Timeout(30)
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

    @Test
    void immediateLargeServerWritesCompleteDuringRegistrationHandoff() throws Exception {
        int port = nextFreePort();
        int connectionCount = 8;
        int payloadSize = 256 * 1024;
        byte[] payload = new byte[payloadSize];
        Arrays.fill(payload, (byte) 'x');
        CountDownLatch handled = new CountDownLatch(connectionCount);

        NativeTcpTransportProvider provider = new NativeTcpTransportProvider();
        TransportEngine[] holder = new TransportEngine[1];

        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR)
                .run(() -> holder[0] = provider.createEngine(new TransportConfig(
                        TransportMode.SERVER,
                        "127.0.0.1",
                        port,
                        2,
                        null,
                        null,
                        1024,
                        30_000
                )));

        TransportEngine engine = holder[0];
        engine.setStreamHandler(stream -> {
            try (stream) {
                stream.write(MemorySegment.ofArray(payload), payload.length);
            } finally {
                handled.countDown();
            }
        });

        List<SocketChannel> clients = new ArrayList<>();
        try {
            engine.start();

            for (int i = 0; i < connectionCount; i++) {
                SocketChannel client = SocketChannel.open();
                client.configureBlocking(true);
                client.socket().setReceiveBufferSize(4 * 1024);
                client.connect(new InetSocketAddress("127.0.0.1", port));
                clients.add(client);
            }

            assertThat(handled.await(10, TimeUnit.SECONDS)).isTrue();

            for (SocketChannel client : clients) {
                assertThat(readFully(client, payloadSize, Duration.ofSeconds(5))).isEqualTo(payloadSize);
            }
        } finally {
            for (SocketChannel client : clients) {
                client.close();
            }
            engine.close();
        }
    }

    @Test
    void rapidConnectAndCloseDoesNotLeaveDanglingReactorOwnership() throws Exception {
        int port = nextFreePort();
        int connectionCount = 12;
        CountDownLatch handled = new CountDownLatch(connectionCount);

        NativeTcpTransportProvider provider = new NativeTcpTransportProvider();
        TransportEngine[] holder = new TransportEngine[1];

        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR)
                .run(() -> holder[0] = provider.createEngine(new TransportConfig(
                        TransportMode.SERVER,
                        "127.0.0.1",
                        port,
                        2,
                        null,
                        null,
                        1024,
                        30_000
                )));

        TransportEngine engine = holder[0];
        engine.setStreamHandler(stream -> {
            try (stream) {
                // close immediately to exercise register/write/cancel handoff ordering
            } finally {
                handled.countDown();
            }
        });

        try {
            engine.start();

            for (int i = 0; i < connectionCount; i++) {
                try (SocketChannel client = SocketChannel.open()) {
                    client.configureBlocking(true);
                    client.connect(new InetSocketAddress("127.0.0.1", port));
                }
            }

            assertThat(handled.await(10, TimeUnit.SECONDS)).isTrue();

            NativeTcpCarrier carrier = (NativeTcpCarrier) engine;
            waitUntilNoOwnedChannels(carrier, Duration.ofSeconds(5));
        } finally {
            engine.close();
        }
    }

    @Test
    void connectionEstablishedFiresExactlyOnceAndBeforeStreamDispatch() throws Exception {
        int port = nextFreePort();
        AtomicInteger establishedCount = new AtomicInteger(0);
        AtomicBoolean established = new AtomicBoolean(false);
        AtomicBoolean establishedBeforeDispatch = new AtomicBoolean(false);
        CountDownLatch handled = new CountDownLatch(1);
        // Released once all three client writes have returned. Without it the handler's
        // try-with-resources closes the stream on the FIRST dispatch, racing writes 2 and 3 — and
        // the race has two losing sides, not one: the client can take a broken pipe, or the writes
        // can be dropped and the test passes having driven a single ingress read, which is not the
        // premise it asserts on. Ordering the close after the writes removes both.
        CountDownLatch writesLanded = new CountDownLatch(1);

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
        engine.setConnectionHandler(connection -> {
            establishedCount.incrementAndGet();
            established.set(true);
        });
        engine.setStreamHandler(stream -> {
            try (stream) {
                // onConnectionEstablished must have fired before the stream is dispatched.
                establishedBeforeDispatch.set(established.get());
                // Hold the stream open until every write is in, so the ingress reads this test
                // exists to provoke actually happen. The handler runs on a virtual thread, so
                // parking here costs no carrier.
                if (!writesLanded.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("client writes did not land within 5s");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                handled.countDown();
            }
        });

        try {
            engine.start();

            try (SocketChannel client = SocketChannel.open()) {
                client.configureBlocking(true);
                client.connect(new InetSocketAddress("127.0.0.1", port));
                // Multiple writes drive multiple ingress reads. Note what that does and does not
                // prove HERE: this engine is plaintext (no certPath/keyPath), and on the plaintext
                // path fireEstablishedOnce() is reached from markRegistrationReady(), once per
                // connection — not per ingress read. So the CAS one-shot cannot be observed to
                // re-fire on this path, and breaking it leaves this test green (verified by
                // mutation). What this case actually pins is the ORDERING — established before
                // dispatch — plus the count on a path that only reaches the site once.
                // The CAS is load-bearing on the TLS path, where readTlsIngressFromFd() calls it
                // per ingress read; NativeTcpTlsEstablishedOnceIntegrationTest covers it there.
                client.write(ByteBuffer.wrap("a".getBytes(StandardCharsets.UTF_8)));
                client.write(ByteBuffer.wrap("b".getBytes(StandardCharsets.UTF_8)));
                client.write(ByteBuffer.wrap("c".getBytes(StandardCharsets.UTF_8)));
                writesLanded.countDown();

                assertThat(handled.await(5, TimeUnit.SECONDS)).isTrue();
            }

            // Poll for stability rather than a fixed sleep. Honest about its reach: on this
            // plaintext path a re-fire is not reachable at all, so this loop guards the ordering
            // and the single-fire invariant against a future change that moves the call site onto
            // the per-read path, rather than against today's CAS.
            Instant settleDeadline = Instant.now().plus(Duration.ofMillis(500));
            while (Instant.now().isBefore(settleDeadline)) {
                assertThat(establishedCount.get())
                        .as("onConnectionEstablished must never fire more than once")
                        .isLessThanOrEqualTo(1);
                LockSupport.parkNanos(20_000_000L);
            }

            assertThat(establishedCount.get())
                    .as("onConnectionEstablished must fire exactly once per connection")
                    .isEqualTo(1);
            assertThat(establishedBeforeDispatch.get())
                    .as("onConnectionEstablished must precede the first stream dispatch")
                    .isTrue();
        } finally {
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
                                                   Duration timeout) {
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

    private static void waitUntilNoOwnedChannels(NativeTcpCarrier carrier, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Map<?, ?> ownerMap = readPrivateField(carrier, "channelOwner", Map.class);
            Map<?, ?> streamMap = readPrivateField(carrier, "streamByChannel", Map.class);
            if (ownerMap.isEmpty() && streamMap.isEmpty()) {
                return;
            }
            LockSupport.parkNanos(25_000_000L);
        }
        throw new AssertionError("Expected channel ownership and stream tracking to drain before timeout");
    }

    private static int readFully(SocketChannel client, int expectedBytes, Duration timeout) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(expectedBytes);
        Instant deadline = Instant.now().plus(timeout);
        while (buffer.hasRemaining() && Instant.now().isBefore(deadline)) {
            int read = client.read(buffer);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                LockSupport.parkNanos(1_000_000L);
            }
        }
        return buffer.position();
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
