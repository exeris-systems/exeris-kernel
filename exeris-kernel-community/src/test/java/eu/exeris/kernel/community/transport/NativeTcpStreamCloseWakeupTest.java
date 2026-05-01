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
import eu.exeris.kernel.core.transport.syscall.SyscallHandles;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link NativeTcpStream#markRemoteClosed()} immediately unparks a VT
 * blocked inside {@link NativeTcpStream#read}.
 */
class NativeTcpStreamCloseWakeupTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    @AfterAll
    @SuppressWarnings("unused")
    static void releaseAllocator() {
        ALLOCATOR.close();
    }

    @Test
    void markRemoteClosedUnblocksReadingVtWithin50ms() throws Exception {
        try (ServerSocketChannel listener = ServerSocketChannel.open()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = ((InetSocketAddress) listener.getLocalAddress()).getPort();

            try (SocketChannel clientChannel = SocketChannel.open(new InetSocketAddress("127.0.0.1", port));
                 SocketChannel serverChannel = listener.accept()) {

                clientChannel.configureBlocking(false);

                NativeTcpConnection connection = new NativeTcpConnection(1L, "127.0.0.1", port);
                NativeTcpStream stream = new NativeTcpStream(
                        "test-engine",
                        1L,
                        clientChannel,
                        connection,
                        ALLOCATOR,
                        null,   // no TLS
                        () -> {},
                        () -> {}
                );
                connection.bindSingleStream(stream);

                AtomicLong readReturnedAt = new AtomicLong(-1L);

                try (LoanedBuffer sink = ALLOCATOR.allocateNetwork(64)) {
                    Thread vt = Thread.ofVirtual().start(() -> {
                        stream.read(sink.segment(), 64);
                        readReturnedAt.set(System.nanoTime());
                    });

                    // Spin until the VT has registered itself and entered park/parkNanos
                    long spinDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    while (vt.getState() != Thread.State.TIMED_WAITING
                            && vt.getState() != Thread.State.WAITING
                            && System.nanoTime() < spinDeadline) {
                        Thread.onSpinWait();
                    }

                    long signalAt = System.nanoTime();
                    stream.markRemoteClosed();

                    vt.join(1_000);

                    long returnedAt = readReturnedAt.get();
                    assertTrue(returnedAt >= 0, "VT did not complete read within 1s join timeout");

                    long durationMs = TimeUnit.NANOSECONDS.toMillis(returnedAt - signalAt);
                    assertTrue(durationMs < 50,
                            "Expected wakeup within 50ms but took " + durationMs + "ms");
                } finally {
                    stream.close();
                    serverChannel.close();
                }
            }
        }
    }

    @Test
    void plainWritePrefersActiveSocketBackendBeforeQueueFallback() throws Exception {
        try (ServerSocketChannel listener = ServerSocketChannel.open()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = ((InetSocketAddress) listener.getLocalAddress()).getPort();

            try (SocketChannel clientChannel = SocketChannel.open(new InetSocketAddress("127.0.0.1", port));
                 SocketChannel serverChannel = listener.accept()) {

                clientChannel.configureBlocking(false);
                serverChannel.configureBlocking(false);

                NativeTcpConnection connection = new NativeTcpConnection(11L, "127.0.0.1", port);
                NativeTcpStream stream = new NativeTcpStream(
                        "test-engine",
                        11L,
                        clientChannel,
                        connection,
                        ALLOCATOR,
                        null,
                        () -> { },
                        () -> { }
                );
                connection.bindSingleStream(stream);
                stream.markRegistrationReady();

                byte[] payload = "direct-path".getBytes(StandardCharsets.UTF_8);
                stream.write(MemorySegment.ofArray(payload), payload.length);

                ByteBuffer received = ByteBuffer.allocate(payload.length);
                int read = readWithinDeadline(serverChannel, received, payload.length, 1_000L);

                assertEquals(payload.length, read, "Expected plain write to reach peer within the bounded deadline");
                assertFalse(stream.hasPendingData(), "Plain write should not stay queued on the active socket path");
                assertArrayEquals(payload, java.util.Arrays.copyOf(received.array(), read));

                stream.close();
            }
        }
    }

    @Test
    void queueWriteSignalsWriteInterestOnlyOnEmptyToNonEmptyTransition() throws Exception {
        try (ServerSocketChannel listener = ServerSocketChannel.open()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = ((InetSocketAddress) listener.getLocalAddress()).getPort();

            try (SocketChannel clientChannel = SocketChannel.open(new InetSocketAddress("127.0.0.1", port));
                 SocketChannel serverChannel = listener.accept()) {

                clientChannel.configureBlocking(false);
                serverChannel.configureBlocking(false);

                AtomicInteger writeInterestSignals = new AtomicInteger();
                NativeTcpConnection connection = new NativeTcpConnection(12L, "127.0.0.1", port);
                NativeTcpStream stream = new NativeTcpStream(
                        "test-engine",
                        12L,
                        clientChannel,
                        connection,
                        ALLOCATOR,
                        null,
                        writeInterestSignals::incrementAndGet,
                        () -> { }
                );
                connection.bindSingleStream(stream);
                stream.markRegistrationReady();

                int payloadBytes = 2 * 1024 * 1024;
                LoanedBuffer first = ALLOCATOR.allocateNetwork(payloadBytes);
                first.setSize(payloadBytes);
                LoanedBuffer second = ALLOCATOR.allocateNetwork(payloadBytes);
                second.setSize(payloadBytes);
                try {
                    stream.queueWrite(first, payloadBytes);
                    stream.queueWrite(second, payloadBytes);

                    assertTrue(stream.hasPendingData(), "Expected socket backpressure to leave outbound data pending");
                    assertEquals(1, writeInterestSignals.get(),
                            "Expected a single write-interest callback for the empty-to-non-empty transition");
                } finally {
                    stream.close();
                    serverChannel.close();
                }
            }
        }
    }

    @Test
    void queueWriteFastPathWritesImmediatelyWithoutLeavingBacklog() throws Exception {
        try (ServerSocketChannel listener = ServerSocketChannel.open()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = ((InetSocketAddress) listener.getLocalAddress()).getPort();

            try (SocketChannel clientChannel = SocketChannel.open(new InetSocketAddress("127.0.0.1", port));
                 SocketChannel serverChannel = listener.accept()) {

                clientChannel.configureBlocking(false);
                serverChannel.configureBlocking(false);

                AtomicInteger writeInterestSignals = new AtomicInteger();
                NativeTcpConnection connection = new NativeTcpConnection(13L, "127.0.0.1", port);
                NativeTcpStream stream = new NativeTcpStream(
                        "test-engine",
                        13L,
                        clientChannel,
                        connection,
                        ALLOCATOR,
                        null,
                        writeInterestSignals::incrementAndGet,
                        () -> { }
                );
                connection.bindSingleStream(stream);
                stream.markRegistrationReady();

                byte[] payload = "ownership-fast-path".getBytes(StandardCharsets.UTF_8);
                LoanedBuffer source = ALLOCATOR.allocateNetwork(payload.length);
                MemorySegment.copy(MemorySegment.ofArray(payload), 0, source.segment(), 0, payload.length);
                source.setSize(payload.length);

                stream.queueWrite(source, payload.length);

                ByteBuffer received = ByteBuffer.allocate(payload.length);
                int read = readWithinDeadline(serverChannel, received, payload.length, 1_000L);

                assertEquals(payload.length, read, "Expected ownership-transfer fast path to write within the bounded deadline");
                assertFalse(stream.hasPendingData(), "Fast path should not leave queued backlog after immediate write");
                assertEquals(0, writeInterestSignals.get(),
                        "Expected no write-interest callback when the immediate write drains fully");
                assertArrayEquals(payload, java.util.Arrays.copyOf(received.array(), read));
                assertTrue(source.refCount() == 0 || !source.isAlive(),
                        "Ownership-transfer fast path should release the transferred buffer once the write completes");

                stream.close();
            }
        }
    }

    @Test
    void windowsStyleSocketHandlesForceNioFallback() throws Exception {
        try (ServerSocketChannel listener = ServerSocketChannel.open()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = ((InetSocketAddress) listener.getLocalAddress()).getPort();

            try (SocketChannel clientChannel = SocketChannel.open(new InetSocketAddress("127.0.0.1", port));
                 SocketChannel serverChannel = listener.accept()) {

                clientChannel.configureBlocking(false);
                serverChannel.configureBlocking(false);

                MethodHandle handle = stubHandle();
                SyscallHandles windowsHandles = new SyscallHandles(
                        handle,
                        handle,
                        handle,
                        handle,
                        handle,
                        handle,
                        handle,
                        handle,
                        null,
                        handle,
                        handle,
                        handle
                );

                NativeTcpConnection connection = new NativeTcpConnection(14L, "127.0.0.1", port);
                NativeTcpStream stream = new NativeTcpStream(
                        "test-engine",
                        14L,
                        clientChannel,
                        connection,
                        ALLOCATOR,
                        null,
                        () -> { },
                        () -> { },
                        windowsHandles
                );
                connection.bindSingleStream(stream);

                try {
                    assertEquals("nio-fallback", stream.plainSocketBackendName(),
                            "Windows-style socket handles must not use the int-based Core socket seam");
                } finally {
                    stream.close();
                }
            }
        }
    }

    private static int readWithinDeadline(SocketChannel channel,
                                           ByteBuffer target,
                                           int expectedBytes,
                                           long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        int totalRead = 0;
        while (totalRead < expectedBytes && System.nanoTime() < deadline) {
            int read = channel.read(target);
            if (read > 0) {
                totalRead += read;
                continue;
            }
            if (read < 0) {
                break;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        return totalRead;
    }

    private static MethodHandle stubHandle() {
        try {
            return MethodHandles.lookup()
                    .findStatic(NativeTcpStreamCloseWakeupTest.class, "noOp", MethodType.methodType(void.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new AssertionError("Test setup failed", e);
        }
    }

    @SuppressWarnings("unused")
    private static void noOp() {
        // stub target — intentionally empty
    }

    @Test
    void closeDoesNotCompeteWithActiveOutboundConsumer() throws Exception {
        try (ServerSocketChannel listener = ServerSocketChannel.open()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = ((InetSocketAddress) listener.getLocalAddress()).getPort();

            try (SocketChannel clientChannel = SocketChannel.open(new InetSocketAddress("127.0.0.1", port));
                 SocketChannel serverChannel = listener.accept()) {

                clientChannel.configureBlocking(false);
                serverChannel.configureBlocking(false);

                NativeTcpConnection connection = new NativeTcpConnection(2L, "127.0.0.1", port);
                NativeTcpStream stream = new NativeTcpStream(
                        "test-engine",
                        2L,
                        clientChannel,
                        connection,
                        ALLOCATOR,
                        null,
                        () -> { },
                        () -> { }
                );
                connection.bindSingleStream(stream);

                AtomicReference<Throwable> failure = new AtomicReference<>();
                CountDownLatch flusherStarted = new CountDownLatch(1);

                try (LoanedBuffer source = ALLOCATOR.allocateNetwork(256 * 1024)) {
                    source.setSize((int) source.capacity());
                    stream.queueWrite(source, (int) source.capacity());

                    Thread flusher = Thread.ofVirtual().start(() -> {
                        flusherStarted.countDown();
                        try {
                            while (!stream.flushPendingWrites() && !stream.isClosed()) {
                                Thread.onSpinWait();
                            }
                        } catch (Throwable t) {
                            failure.set(t);
                        }
                    });

                    assertTrue(flusherStarted.await(1, TimeUnit.SECONDS),
                            "Outbound flusher did not start in time");

                    stream.close();
                    flusher.join(1_000L);

                    assertFalse(flusher.isAlive(), "Outbound flusher did not stop after stream close");
                    assertTrue(stream.isClosed(), "Stream should be closed after shutdown");
                    Throwable flushFailure = failure.get();
                    assertNull(flushFailure, "Unexpected outbound flush failure: " + flushFailure);
                } finally {
                    stream.close();
                    serverChannel.close();
                }
            }
        }
    }
}
