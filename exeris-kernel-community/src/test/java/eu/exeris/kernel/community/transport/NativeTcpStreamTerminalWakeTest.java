/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.transport.TransportException;
import eu.exeris.kernel.spi.exceptions.transport.TransportStreamClosedException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every transition that ends a {@link NativeTcpStream} wakes the threads parked on it, whichever
 * caller reached the transition and in whatever order the terminal paths ran. Each case drives a
 * real loopback channel; waiters are either a real virtual-thread reader or a probe thread that
 * is registered as the stream's reader and counts each wake it receives.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class NativeTcpStreamTerminalWakeTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    private static final long AWAIT_SECONDS = 5L;
    private static final int PENDING_BYTES = 16;

    @AfterAll
    @SuppressWarnings("unused")
    static void releaseAllocator() {
        ALLOCATOR.close();
    }

    @Test
    @DisplayName("close with the outbound slot held elsewhere wakes a parked reader with the locally-closed signal")
    void closeWithSlotHeldElsewhereWakesParkedReader() throws Exception {
        try (Rig rig = new Rig()) {
            Thread slotHolder = Thread.ofPlatform().unstarted(() -> { });
            assertTrue(rig.stream.tryPinOutboundConsumerForTest(slotHolder));
            ReaderRun reader = rig.startReader();
            reader.awaitParked();

            rig.stream.close();

            assertLocallyClosed(reader.outcome());
            assertEquals(0, rig.closeCalls.get(), "the finish waits for the slot holder");

            rig.stream.unpinOutboundConsumerForTest(slotHolder);
            rig.stream.close();
            assertEquals(1, rig.closeCalls.get());
        }
    }

    @Test
    @DisplayName("a read issued after a deferred close reports the closed stream instead of parking")
    void readAfterDeferredCloseDoesNotPark() throws Exception {
        try (Rig rig = new Rig()) {
            Thread slotHolder = Thread.ofPlatform().unstarted(() -> { });
            assertTrue(rig.stream.tryPinOutboundConsumerForTest(slotHolder));
            rig.stream.close();

            ReaderRun reader = rig.startReader();

            assertLocallyClosed(reader.outcome());
            rig.stream.unpinOutboundConsumerForTest(slotHolder);
        }
    }

    @Test
    @DisplayName("a second close after an abort wakes the registered reader")
    void secondCloseAfterAbortWakesReader() throws Exception {
        try (Rig rig = new Rig(); ParkProbe probe = new ParkProbe()) {
            rig.abortOnFlush();
            rig.stream.registerReaderWaiterForTest(probe.thread());
            probe.awaitParked();

            rig.stream.close();

            assertTrue(probe.awaitWake(), "the already-requested branch of close() must wake the waiter");
        }
    }

    @Test
    @DisplayName("a second reset wakes the registered reader and asks the reactor to finish")
    void secondResetWakesReaderAndRearmsWriteInterest() throws Exception {
        try (Rig rig = new Rig(); ParkProbe probe = new ParkProbe()) {
            Thread slotHolder = Thread.ofPlatform().unstarted(() -> { });
            assertTrue(rig.stream.tryPinOutboundConsumerForTest(slotHolder));
            rig.stream.reset(0L);
            int interestAfterFirst = rig.interestCalls.get();
            rig.stream.registerReaderWaiterForTest(probe.thread());
            probe.awaitParked();

            rig.stream.reset(1L);

            assertTrue(probe.awaitWake(), "the already-requested branch of reset() must wake the waiter");
            assertEquals(interestAfterFirst + 1, rig.interestCalls.get(),
                    "an unfinished reset hands the finish to the reactor again");
            assertEquals(0, rig.closeCalls.get());
            rig.stream.unpinOutboundConsumerForTest(slotHolder);
            rig.stream.close();
            assertEquals(1, rig.closeCalls.get());
        }
    }

    @Test
    @DisplayName("the finish that completes a deferred close wakes the registration again")
    void finishCompletingLaterWakesAgain() throws Exception {
        try (Rig rig = new Rig(); ParkProbe probe = new ParkProbe()) {
            Thread slotHolder = Thread.ofPlatform().unstarted(() -> { });
            assertTrue(rig.stream.tryPinOutboundConsumerForTest(slotHolder));
            rig.stream.registerReaderWaiterForTest(probe.thread());
            probe.awaitParked();

            rig.stream.close();
            assertTrue(probe.awaitWake(), "close() wakes the waiter");
            assertEquals(0, rig.closeCalls.get());
            probe.awaitParked();

            rig.stream.unpinOutboundConsumerForTest(slotHolder);
            assertTrue(rig.stream.flushPendingWrites());

            assertEquals(1, rig.closeCalls.get());
            assertTrue(probe.awaitWake(),
                    "the registration survives the first wake and the closing transition wakes it again");
        }
    }

    @Test
    @DisplayName("the peer's end wakes a registered reader that is not flagged as parked")
    void remoteCloseWakesRegisteredReaderWithoutParkedHint() throws Exception {
        try (Rig rig = new Rig(); ParkProbe probe = new ParkProbe()) {
            rig.stream.registerReaderWaiterForTest(probe.thread());
            probe.awaitParked();

            rig.stream.markRemoteClosed();

            assertTrue(probe.awaitWake());
        }
    }

    @Test
    @DisplayName("an abort under the slot finishes once the slot is released, running the close callback once")
    void abortUnderSlotFinishesAfterRelease() throws Exception {
        try (Rig rig = new Rig()) {
            rig.enqueueUnflushed();
            rig.clientChannel.close();

            assertThrows(TransportException.class, rig.stream::flushPendingWrites);

            assertEquals(1, rig.closeCalls.get(), "the abort finishes after its own slot is released");
            assertFalse(rig.stream.hasPendingData());
            Thread other = Thread.ofPlatform().unstarted(() -> { });
            assertTrue(rig.stream.tryPinOutboundConsumerForTest(other), "the slot is free after the abort");
            rig.stream.unpinOutboundConsumerForTest(other);

            rig.stream.close();
            rig.stream.flushPendingWrites();
            assertEquals(1, rig.closeCalls.get(), "later closes and flushes do not finish twice");
        }
    }

    @Test
    @DisplayName("an abort in a flush nested under the slot holder wakes the reader and leaves the finish to the holder")
    void nestedAbortWakesReaderAndKeepsOuterSlot() throws Exception {
        try (Rig rig = new Rig()) {
            Thread self = Thread.currentThread();
            assertTrue(rig.stream.tryPinOutboundConsumerForTest(self));
            ReaderRun reader = rig.startReader();
            reader.awaitParked();
            rig.enqueueUnflushed();
            rig.clientChannel.close();

            assertThrows(TransportException.class, rig.stream::flushPendingWrites);

            assertLocallyClosed(reader.outcome());
            assertEquals(0, rig.closeCalls.get(), "the outer holder finishes, not the nested flush");
            Thread other = Thread.ofPlatform().unstarted(() -> { });
            assertFalse(rig.stream.tryPinOutboundConsumerForTest(other),
                    "a nested flush never releases the slot its caller holds");

            rig.stream.unpinOutboundConsumerForTest(self);
            assertTrue(rig.stream.flushPendingWrites());
            assertEquals(1, rig.closeCalls.get());
        }
    }

    @Test
    @DisplayName("finishing a close under the slot leaves the slot with its holder")
    void finishUnderSlotLeavesSlotHeld() throws Exception {
        try (Rig rig = new Rig()) {
            Thread self = Thread.currentThread();
            assertTrue(rig.stream.tryPinOutboundConsumerForTest(self));

            rig.stream.close();

            assertEquals(1, rig.closeCalls.get());
            Thread other = Thread.ofPlatform().unstarted(() -> { });
            assertFalse(rig.stream.tryPinOutboundConsumerForTest(other),
                    "the cleanup nested in the finish must not free the holder's slot");
            rig.stream.unpinOutboundConsumerForTest(self);
        }
    }

    @Test
    @DisplayName("a nested gate entry reports that an outer frame owns the release")
    void nestedGateEntryDoesNotReleaseOuterSlot() throws Exception {
        AtomicReference<Thread> slot = new AtomicReference<>();
        Thread self = Thread.currentThread();
        Thread other = Thread.ofPlatform().unstarted(() -> { });

        int outer = NativeTcpStreamConsumerGate.enterSingleConsumer(slot, self);
        int inner = NativeTcpStreamConsumerGate.enterSingleConsumer(slot, self);
        assertEquals(NativeTcpStreamConsumerGate.ACQUIRED, outer);
        assertEquals(NativeTcpStreamConsumerGate.ALREADY_HELD, inner);
        assertEquals(NativeTcpStreamConsumerGate.NOT_ACQUIRED,
                NativeTcpStreamConsumerGate.enterSingleConsumer(slot, other));

        NativeTcpStreamConsumerGate.exitSingleConsumer(slot, self, inner);
        assertEquals(self, slot.get(), "the nested exit leaves the outer holder in place");

        NativeTcpStreamConsumerGate.exitSingleConsumer(slot, self, outer);
        assertNull(slot.get(), "the acquiring frame's exit releases");
    }

    @Test
    @DisplayName("bytes the peer sent before it ended the stream stay readable after a send-failure abort")
    void abortAfterPeerEndKeepsBufferedInboundReadable() throws Exception {
        try (Rig rig = new Rig()) {
            LoanedBuffer first = rig.offerInbound(new byte[] {1, 2, 3});
            LoanedBuffer second = rig.offerInbound(new byte[] {4, 5});
            rig.stream.markRemoteClosed();
            rig.abortOnFlush();
            assertEquals(1, rig.closeCalls.get(), "the abort finished the teardown");

            try (LoanedBuffer sink = ALLOCATOR.allocateNetwork(64)) {
                byte[] received = new byte[5];
                int total = 0;
                int read;
                while (total < received.length && (read = rig.stream.read(sink.segment(), 64)) > 0) {
                    sink.segment().asSlice(0, read).asByteBuffer().get(received, total, read);
                    total += read;
                }
                assertEquals(5, total);
                assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, received);
                assertEquals(-1, rig.stream.read(sink.segment(), 64), "end of stream only after the bytes");
            }
            assertFalse(first.isAlive());
            assertFalse(second.isAlive());
        }
    }

    @Test
    @DisplayName("a local close discards buffered inbound bytes and reports end of stream")
    void localCloseDiscardsBufferedInbound() throws Exception {
        try (Rig rig = new Rig()) {
            LoanedBuffer buffered = rig.offerInbound(new byte[] {1, 2, 3});
            rig.stream.markRemoteClosed();

            rig.stream.close();

            try (LoanedBuffer sink = ALLOCATOR.allocateNetwork(64)) {
                assertEquals(-1, rig.stream.read(sink.segment(), 64));
            }
            assertFalse(buffered.isAlive());
        }
    }

    @Test
    @DisplayName("a local close after an abort ends the survival of buffered inbound bytes and releases them")
    void localCloseAfterAbortReleasesBufferedInbound() throws Exception {
        try (Rig rig = new Rig()) {
            LoanedBuffer buffered = rig.offerInbound(new byte[] {1, 2, 3});
            rig.stream.markRemoteClosed();
            rig.abortOnFlush();
            assertTrue(buffered.isAlive(), "the abort keeps the bytes for the reader");

            rig.stream.close();

            assertFalse(buffered.isAlive(), "the application's close releases what was kept");
            try (LoanedBuffer sink = ALLOCATOR.allocateNetwork(64)) {
                assertEquals(-1, rig.stream.read(sink.segment(), 64));
            }
        }
    }

    private static void assertLocallyClosed(Throwable failure) {
        TransportStreamClosedException closed = assertInstanceOf(TransportStreamClosedException.class, failure);
        assertEquals(KernelErrorCodes.EX_NET_4009, closed.errorCode());
    }

    /** One stream over a real loopback connection, with counters on its two carrier callbacks. */
    private static final class Rig implements AutoCloseable {

        private final ServerSocketChannel listener;
        private final SocketChannel clientChannel;
        private final SocketChannel serverChannel;
        private final NativeTcpStream stream;
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final AtomicInteger interestCalls = new AtomicInteger();

        private Rig() throws IOException {
            listener = ServerSocketChannel.open();
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = ((InetSocketAddress) listener.getLocalAddress()).getPort();
            clientChannel = SocketChannel.open(new InetSocketAddress("127.0.0.1", port));
            serverChannel = listener.accept();
            clientChannel.configureBlocking(false);
            NativeTcpConnection connection = new NativeTcpConnection(1L, "127.0.0.1", port);
            stream = new NativeTcpStream("test-engine", 1L, clientChannel, connection, ALLOCATOR, null,
                    interestCalls::incrementAndGet, closeCalls::incrementAndGet);
            connection.bindSingleStream(stream);
        }

        /** Queues a write that no flush has seen yet, so the next flush meets it. */
        private void enqueueUnflushed() {
            stream.reserveOutboundWriteForTest();
            LoanedBuffer buffer = ALLOCATOR.allocateNetwork(PENDING_BYTES);
            buffer.setSize(PENDING_BYTES);
            assertTrue(stream.publishReservedWriteForTest(buffer, PENDING_BYTES));
        }

        /** Hands the stream a received buffer, as the carrier's reactor does. */
        private LoanedBuffer offerInbound(byte[] bytes) {
            LoanedBuffer buffer = ALLOCATOR.allocateNetwork(bytes.length);
            buffer.segment().asSlice(0, bytes.length).copyFrom(MemorySegment.ofArray(bytes));
            buffer.setSize(bytes.length);
            stream.offerIngress(buffer);
            return buffer;
        }

        /** Runs an abort: the queued write fails on a channel closed underneath the stream. */
        private void abortOnFlush() throws IOException {
            enqueueUnflushed();
            clientChannel.close();
            assertThrows(TransportException.class, stream::flushPendingWrites);
        }

        private ReaderRun startReader() {
            return new ReaderRun(stream);
        }

        @Override
        public void close() throws IOException {
            try {
                stream.close();
            } finally {
                serverChannel.close();
                clientChannel.close();
                listener.close();
            }
        }
    }

    /** A virtual thread reading the stream, with the failure its read ended with. */
    private static final class ReaderRun {

        private final AtomicReference<Throwable> outcome = new AtomicReference<>();
        private final Thread thread;

        private ReaderRun(NativeTcpStream stream) {
            thread = Thread.ofVirtual().unstarted(() -> {
                try (LoanedBuffer sink = ALLOCATOR.allocateNetwork(64)) {
                    stream.read(sink.segment(), 64);
                } catch (Throwable failure) {
                    outcome.set(failure);
                }
            });
            thread.start();
        }

        /** Spins on the thread state, the transport suite's idiom for "the thread is parked". */
        private void awaitParked() {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
            while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(Thread.State.WAITING, thread.getState(), "reader did not park");
        }

        /** The failure the read ended with, or {@code null} when it returned normally. */
        private Throwable outcome() throws InterruptedException {
            thread.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
            assertFalse(thread.isAlive(), "reader was not released");
            return outcome.get();
        }
    }

    /** A platform thread that parks repeatedly and counts every wake it receives. */
    private static final class ParkProbe implements AutoCloseable {

        private final Semaphore wakes = new Semaphore(0);
        private final AtomicBoolean stop = new AtomicBoolean();
        private final Thread thread = Thread.ofPlatform().daemon().unstarted(this::run);

        private ParkProbe() {
            thread.start();
        }

        private void run() {
            while (!stop.get()) {
                LockSupport.park(this);
                if (Thread.interrupted()) {
                    return;
                }
                wakes.release();
            }
        }

        private Thread thread() {
            return thread;
        }

        private void awaitParked() {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
            while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(Thread.State.WAITING, thread.getState(), "probe did not park");
        }

        private boolean awaitWake() throws InterruptedException {
            return wakes.tryAcquire(AWAIT_SECONDS, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws InterruptedException {
            stop.set(true);
            LockSupport.unpark(thread);
            thread.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
        }
    }
}
