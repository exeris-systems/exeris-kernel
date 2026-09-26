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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.lang.foreign.ValueLayout;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A write whose enqueue straddles another producer's synchronous flush must still be written.
 *
 * <p>{@link NativeTcpStream#queueWrite} raises the outbound depth before it offers the write, and
 * only the producer that raised the depth from zero flushes. A second producer suspended between
 * its increment and its offer is therefore not a flusher, and the flusher's drain cannot see its
 * write. The flusher must hand such a stream to the reactor once it has released the consumer slot,
 * or the write stays queued with nobody to drain it.
 *
 * <p>The suspension is made deterministic without touching the production enqueue: the flusher's
 * own buffer carries a close action — run inside the drain, after its write has been polled — that
 * performs the second producer's increment; the offer follows once {@code queueWrite} has returned.
 * No producer calls {@code writeInterestCallback} after that offer, so only the flusher's handoff
 * can end the pending state within the reactor-driven wait below; the idle reaper that would
 * otherwise discard it runs on a 30 s timeout.
 */
class NativeTcpStreamQueuedWriteHandoffTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    private static final long DRAIN_WAIT_SECONDS = 5L;

    @AfterAll
    @SuppressWarnings("unused")
    static void releaseAllocator() {
        ALLOCATOR.close();
    }

    @Test
    void aWriteEnqueuedAcrossTheFlushIsDrainedByTheReactor() {
        CommunityTransportTestHarness.Pair pair = CommunityTransportTestHarness.openLoopbackPair(ALLOCATOR, true);
        try {
            NativeTcpStream stream = (NativeTcpStream) pair.clientStream();
            AtomicBoolean reservedInsideFlush = new AtomicBoolean();

            LoanedBuffer flusherWrite = sentinel();
            flusherWrite.addCloseAction(() -> {
                stream.reserveOutboundWriteForTest();
                reservedInsideFlush.set(true);
            });
            stream.queueWrite(flusherWrite, Long.BYTES);

            assertTrue(reservedInsideFlush.get(),
                    "precondition: the flusher must drain its own write synchronously, so the second "
                            + "producer's increment lands inside that flush");

            assertTrue(stream.publishReservedWriteForTest(sentinel(), Long.BYTES),
                    "precondition: the outbound queue accepts the second producer's write");

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DRAIN_WAIT_SECONDS);
            while (stream.hasPendingData() && System.nanoTime() < deadline) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            }
            assertFalse(stream.hasPendingData(),
                    "a write enqueued while another producer's flush was emptying the queue stayed "
                            + "pending for " + DRAIN_WAIT_SECONDS + " s: the flusher released the "
                            + "consumer slot without handing the remaining depth to the reactor");
        } finally {
            pair.closeConnections();
            pair.closeEngines();
        }
    }

    private static LoanedBuffer sentinel() {
        LoanedBuffer buffer = ALLOCATOR.allocate(AllocationHint.MICRO);
        buffer.segment().set(ValueLayout.JAVA_LONG, 0, 0xCAFEL);
        return buffer;
    }
}
