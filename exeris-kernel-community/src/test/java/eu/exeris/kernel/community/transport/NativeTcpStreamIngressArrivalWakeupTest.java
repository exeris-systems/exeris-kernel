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
    void offerIngressUnblocksReadingVtWithin5ms() throws Exception {
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
                    assertTrue(durationMs < 5,
                            "Expected wakeup within 5ms but took " + durationMs + "ms");
                } finally {
                    stream.close();
                    serverChannel.close();
                }
            }
        }
    }
}
