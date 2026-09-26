/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NativeTcpConnection#close()} closes its stream even when the peer closed first.
 *
 * <p>The carrier marks a connection closed when it reads the peer's end of stream, without closing
 * the stream. A {@code close()} that returned early on that flag left the stream open — and its
 * socket, key and queues with it — until the idle reaper or the engine's own close came round.
 */
class NativeTcpConnectionCloseTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    private static final long REMOTE_CLOSE_WAIT_SECONDS = 5L;

    @AfterAll
    @SuppressWarnings("unused")
    static void releaseAllocator() {
        ALLOCATOR.close();
    }

    @Test
    void closeAfterTheRemoteClosedStillClosesTheStream() {
        CommunityTransportTestHarness.Pair pair = CommunityTransportTestHarness.openLoopbackPair(ALLOCATOR, false);
        try {
            TransportConnection connection = pair.clientConnection();
            NativeTcpStream stream = (NativeTcpStream) pair.clientStream();

            pair.serverConnection().close();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(REMOTE_CLOSE_WAIT_SECONDS);
            while (!stream.isRemoteClosed() && System.nanoTime() < deadline) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            }
            assertTrue(stream.isRemoteClosed(), "precondition: the client carrier must observe the peer's close");
            assertFalse(connection.isOpen(), "precondition: the peer's close marks the connection closed");
            assertFalse(stream.isClosed(), "precondition: the peer's close alone does not close the stream");

            connection.close();

            assertTrue(stream.isClosed(), "close() after a remote close must close the connection's stream");
            LoanedBuffer refused = ALLOCATOR.allocate(AllocationHint.MICRO);
            try (refused) {
                assertThrows(IllegalStateException.class, () -> stream.queueWrite(refused, Long.BYTES),
                        "a stream closed through its connection must refuse a write");
            }

            connection.close();
            assertTrue(stream.isClosed(), "a repeated close() leaves the stream closed");
        } finally {
            pair.closeConnections();
            pair.closeEngines();
        }
    }
}
