/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link NativeTcpStream#offerIngress(LoanedBuffer)} immediately unparks a VT
 * blocked inside {@link NativeTcpStream#read} waiting on an empty inbound queue.
 */
class NativeTcpStreamIngressArrivalWakeupTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    @AfterAll
    @SuppressWarnings("unused")
    static void releaseAllocator() {
        ALLOCATOR.close();
    }

    @Test
    void offerIngressUnblocksReadingVtWithin50ms() throws Exception {
        try (ServerSocketChannel listener = ServerSocketChannel.open()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = ((InetSocketAddress) listener.getLocalAddress()).getPort();

            try (SocketChannel clientChannel = SocketChannel.open(new InetSocketAddress("127.0.0.1", port));
                 SocketChannel serverChannel = listener.accept()) {

                clientChannel.configureBlocking(false);

                NativeTcpConnection connection = new NativeTcpConnection(2L, "127.0.0.1", port);
                NativeTcpStream stream = new NativeTcpStream(
                        "test-engine",
                        2L,
                        clientChannel,
                        connection,
                        ALLOCATOR,
                        null,   // no TLS
                        () -> {},
                        () -> {}
                );
                connection.bindSingleStream(stream);

                AtomicLong readReturnedAt = new AtomicLong(-1L);
                AtomicLong bytesRead = new AtomicLong(-1L);

                try (LoanedBuffer sink = ALLOCATOR.allocateNetwork(64)) {
                    Thread vt = Thread.ofVirtual().start(() -> {
                        int n = stream.read(sink.segment(), 64);
                        bytesRead.set(n);
                        readReturnedAt.set(System.nanoTime());
                    });

                    // Spin until the VT has registered itself and entered park/parkNanos
                    long spinDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    while (vt.getState() != Thread.State.TIMED_WAITING
                            && vt.getState() != Thread.State.WAITING
                            && System.nanoTime() < spinDeadline) {
                        Thread.onSpinWait();
                    }

                    Thread.State stateAfterSpin = vt.getState();
                    assertTrue(stateAfterSpin == Thread.State.TIMED_WAITING || stateAfterSpin == Thread.State.WAITING,
                            "VT did not enter WAITING/TIMED_WAITING within 2s");

                    // Prepare a 4-byte ingress buffer
                    LoanedBuffer ingressBuffer = ALLOCATOR.allocateNetwork(4);
                    MemorySegment.copy(
                            MemorySegment.ofArray(new byte[]{1, 2, 3, 4}), 0,
                            ingressBuffer.segment(), 0, 4);
                    ingressBuffer.setSize(4);

                    long signalAt = System.nanoTime();
                    stream.offerIngress(ingressBuffer);

                    vt.join(1_000);

                    long returnedAt = readReturnedAt.get();
                    assertTrue(returnedAt >= 0, "VT did not complete read within 1s join timeout");
                    assertTrue(bytesRead.get() > 0, "Expected positive byte count from read()");

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
    void sequentialVtsReadingSameStreamAreBothUnparked() throws Exception {
        try (ServerSocketChannel listener = ServerSocketChannel.open()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = ((InetSocketAddress) listener.getLocalAddress()).getPort();

            try (SocketChannel clientChannel = SocketChannel.open(new InetSocketAddress("127.0.0.1", port));
                 SocketChannel serverChannel = listener.accept()) {

                clientChannel.configureBlocking(false);

                NativeTcpConnection connection = new NativeTcpConnection(3L, "127.0.0.1", port);
                NativeTcpStream stream = new NativeTcpStream(
                        "test-engine",
                        3L,
                        clientChannel,
                        connection,
                        ALLOCATOR,
                        null,
                        () -> {},
                        () -> {}
                );
                connection.bindSingleStream(stream);

                try {
                    // First VT
                    AtomicLong vt1Returned = new AtomicLong(-1L);
                    try (LoanedBuffer sink1 = ALLOCATOR.allocateNetwork(64)) {
                        Thread vt1 = Thread.ofVirtual().start(() -> {
                            stream.read(sink1.segment(), 64);
                            vt1Returned.set(System.nanoTime());
                        });
                        while (vt1.getState() != Thread.State.TIMED_WAITING
                                && vt1.getState() != Thread.State.WAITING) {
                            Thread.onSpinWait();
                        }
                        LoanedBuffer ingress1 = ALLOCATOR.allocateNetwork(4);
                        ingress1.setSize(4);
                        stream.offerIngress(ingress1);
                        vt1.join(1_000);
                        assertTrue(vt1Returned.get() > 0, "VT1 did not complete read");
                    }

                    // Second VT reading same stream (simulating pooled connection reuse)
                    AtomicLong vt2Returned = new AtomicLong(-1L);
                    try (LoanedBuffer sink2 = ALLOCATOR.allocateNetwork(64)) {
                        Thread vt2 = Thread.ofVirtual().start(() -> {
                            stream.read(sink2.segment(), 64);
                            vt2Returned.set(System.nanoTime());
                        });
                        while (vt2.getState() != Thread.State.TIMED_WAITING
                                && vt2.getState() != Thread.State.WAITING) {
                            Thread.onSpinWait();
                        }
                        LoanedBuffer ingress2 = ALLOCATOR.allocateNetwork(4);
                        ingress2.setSize(4);
                        stream.offerIngress(ingress2);
                        vt2.join(1_000);
                        assertTrue(vt2Returned.get() > 0, "VT2 on reused stream did not complete read");
                    }
                } finally {
                    stream.close();
                    serverChannel.close();
                }
            }
        }
    }
}
