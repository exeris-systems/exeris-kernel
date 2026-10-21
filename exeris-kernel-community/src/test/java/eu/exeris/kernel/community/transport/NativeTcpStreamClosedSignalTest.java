/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.transport.TransportStreamClosedException;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A closed {@link NativeTcpStream} throws {@link TransportStreamClosedException}, and the carried code
 * says whether the remote end was observed ({@code EX-NET-4008}) or the stream was closed through this
 * endpoint ({@code EX-NET-4009}). A closed stream whose remote end was observed still reports end of
 * stream on a read.
 */
class NativeTcpStreamClosedSignalTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    private static final long WAIT_SECONDS = 5L;

    @AfterAll
    @SuppressWarnings("unused")
    static void releaseAllocator() {
        ALLOCATOR.close();
    }

    @Test
    void writeAfterLocalCloseCarriesTheLocalCode() {
        CommunityTransportTestHarness.Pair pair = CommunityTransportTestHarness.openLoopbackPair(ALLOCATOR, false);
        try (LoanedBuffer buffer = ALLOCATOR.allocate(AllocationHint.MICRO)) {
            NativeTcpStream stream = (NativeTcpStream) pair.clientStream();
            stream.close();

            TransportStreamClosedException signal = assertThrows(TransportStreamClosedException.class,
                    () -> stream.write(buffer.segment(), 1));

            assertEquals(KernelErrorCodes.EX_NET_4009, signal.errorCode());
            assertFalse(signal.isPeerClosed());
        } finally {
            pair.closeConnections();
            pair.closeEngines();
        }
    }

    @Test
    void readAfterLocalCloseCarriesTheLocalCode() {
        CommunityTransportTestHarness.Pair pair = CommunityTransportTestHarness.openLoopbackPair(ALLOCATOR, false);
        try (LoanedBuffer buffer = ALLOCATOR.allocate(AllocationHint.MICRO)) {
            NativeTcpStream stream = (NativeTcpStream) pair.clientStream();
            stream.close();

            TransportStreamClosedException signal = assertThrows(TransportStreamClosedException.class,
                    () -> stream.read(buffer.segment(), 1));

            assertEquals(KernelErrorCodes.EX_NET_4009, signal.errorCode());
            assertFalse(signal.isPeerClosed());
        } finally {
            pair.closeConnections();
            pair.closeEngines();
        }
    }

    @Test
    void writeAfterThePeerResetCarriesThePeerCode() {
        CommunityTransportTestHarness.Pair pair = CommunityTransportTestHarness.openLoopbackPair(ALLOCATOR, false);
        try (LoanedBuffer buffer = ALLOCATOR.allocate(AllocationHint.MICRO)) {
            NativeTcpStream server = awaitServerObservedReset(pair);

            TransportStreamClosedException signal = assertThrows(TransportStreamClosedException.class,
                    () -> server.write(buffer.segment(), 1));

            assertEquals(KernelErrorCodes.EX_NET_4008, signal.errorCode());
            assertTrue(signal.isPeerClosed());
        } finally {
            pair.closeConnections();
            pair.closeEngines();
        }
    }

    @Test
    void readAfterThePeerResetStillReportsEndOfStream() {
        CommunityTransportTestHarness.Pair pair = CommunityTransportTestHarness.openLoopbackPair(ALLOCATOR, false);
        try (LoanedBuffer buffer = ALLOCATOR.allocate(AllocationHint.MICRO)) {
            NativeTcpStream server = awaitServerObservedReset(pair);

            assertEquals(-1, server.read(buffer.segment(), 1));
        } finally {
            pair.closeConnections();
            pair.closeEngines();
        }
    }

    @Test
    void writeAfterALocalCloseRequestThenThePeerResetCarriesTheLocalCode() throws Exception {
        CommunityTransportTestHarness.Pair pair = CommunityTransportTestHarness.openLoopbackPair(ALLOCATOR, false);
        LoanedBuffer queued = ALLOCATOR.allocate(AllocationHint.MICRO);
        try (LoanedBuffer buffer = ALLOCATOR.allocate(AllocationHint.MICRO)) {
            NativeTcpStream client = (NativeTcpStream) pair.clientStream();
            try (AutoCloseable hold = CommunityTransportTestHarness.holdOutboundConsumer(client)) {
                queued.setSize(1);
                client.queueWrite(queued, 1);
                client.reset(0L);
                assertTrue(client.hasPendingData(), "precondition: the local close left a write pending");

                pair.serverStream().reset(0L);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
                while (!client.isRemoteClosed() && System.nanoTime() < deadline) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
                }
                assertTrue(client.isRemoteClosed(), "precondition: the client observed the peer's reset");

                TransportStreamClosedException signal = assertThrows(TransportStreamClosedException.class,
                        () -> client.write(buffer.segment(), 1));

                assertEquals(KernelErrorCodes.EX_NET_4009, signal.errorCode(),
                        "a stream this endpoint closed first reports the local code");
                assertFalse(signal.isPeerClosed());
            }
        } finally {
            queued.close();
            pair.closeConnections();
            pair.closeEngines();
        }
    }

    /** Resets the client stream and waits until the server stream is both closed and remote-closed. */
    private static NativeTcpStream awaitServerObservedReset(CommunityTransportTestHarness.Pair pair) {
        NativeTcpStream server = (NativeTcpStream) pair.serverStream();
        pair.clientStream().reset(0L);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!(server.isClosed() && server.isRemoteClosed()) && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
        assertTrue(server.isClosed(), "precondition: the server stream is closed after the peer's reset");
        assertTrue(server.isRemoteClosed(), "precondition: the server stream observed the peer's end");
        return server;
    }
}
