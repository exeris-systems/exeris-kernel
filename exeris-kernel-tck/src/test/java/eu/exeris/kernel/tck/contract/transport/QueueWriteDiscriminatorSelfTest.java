/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.transport;

import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Meta-test proving the {@link AbstractTransportStreamTck} non-waiting {@code queueWrite}
 * discriminator is not vacuously green: a binding whose {@code queueWrite} waits for the peer to
 * read MUST fail it, while a binding that queues and releases each loan as the peer reads MUST pass.
 *
 * <p>It drives {@link AbstractTransportStreamTck#runQueueWriteDoesNotWaitDiscriminator()} directly
 * against in-memory fakes, with no sockets. The fake binding is a {@code static} nested class so it
 * is not itself discovered as a runnable TCK suite.
 */
class QueueWriteDiscriminatorSelfTest {

    @Test
    @DisplayName("discriminator FAILS a binding whose queueWrite waits for the peer to read")
    void waitingBindingFailsDiscriminator() throws Exception {
        PipeBinding waiting = new PipeBinding(true);
        waiting.setUp();
        try {
            assertThatThrownBy(waiting::runQueueWriteDoesNotWaitDiscriminator)
                    .as("a queueWrite that blocks until the peer reads MUST fail the discriminator")
                    .isInstanceOf(AssertionError.class);
        } finally {
            waiting.tearDown();
        }
    }

    @Test
    @DisplayName("discriminator PASSES a binding that queues without waiting and releases loans as the peer reads")
    void queueingBindingPassesDiscriminator() throws Exception {
        PipeBinding queueing = new PipeBinding(false);
        queueing.setUp();
        try {
            assertThatCode(queueing::runQueueWriteDoesNotWaitDiscriminator).doesNotThrowAnyException();
        } finally {
            queueing.tearDown();
        }
    }

    /** Fake binding: one {@link PipeStream} is both writer and reader. */
    private static final class PipeBinding extends AbstractTransportStreamTck {

        private final boolean queueWriteWaits;

        private PipeBinding(boolean queueWriteWaits) {
            this.queueWriteWaits = queueWriteWaits;
        }

        @Override
        protected StreamPair createStreamPair() {
            PipeStream stream = new PipeStream(queueWriteWaits);
            return new StreamPair(stream, stream);
        }

        @Override
        protected MemoryAllocator createAllocator() {
            return new FakeAllocator();
        }
    }

    /**
     * In-memory stream. {@code queueWrite} either takes the loan at once, or first waits for window
     * space that only the peer's {@code read} frees; a loan is released when its last byte is read.
     */
    private static final class PipeStream implements TransportStream {

        private static final int WINDOW = 256 * 1024;

        private final boolean queueWriteWaits;
        private final Semaphore window = new Semaphore(WINDOW);
        private final LinkedBlockingQueue<Entry> queue = new LinkedBlockingQueue<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private Entry current;

        private PipeStream(boolean queueWriteWaits) {
            this.queueWriteWaits = queueWriteWaits;
        }

        @Override
        public int read(MemorySegment target, int maxBytes) {
            try {
                if (current == null) {
                    current = queue.take();
                }
                int bytes = Math.min(maxBytes, current.length - current.offset);
                MemorySegment.copy(current.loan.segment(), current.offset, target, 0, bytes);
                current.offset += bytes;
                if (current.offset == current.length) {
                    Entry done = current;
                    current = null;
                    done.loan.close();
                    window.release(done.length);
                }
                return bytes;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }

        @Override
        public void write(MemorySegment source, int length) {
            // not exercised by the discriminator
        }

        @Override
        public void queueWrite(LoanedBuffer buffer, int length) {
            if (queueWriteWaits) {
                window.acquireUninterruptibly(length);
            }
            queue.add(new Entry(buffer, length));
        }

        @Override
        public long streamId() {
            return 1L;
        }

        @Override
        public boolean isBidirectional() {
            return true;
        }

        @Override
        public boolean isClientInitiated() {
            return true;
        }

        @Override
        public TransportConnection connection() {
            return null; // not exercised by the discriminator
        }

        @Override
        public boolean hasPendingData() {
            return !queue.isEmpty() || current != null;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                window.release(Integer.MAX_VALUE / 2);
            }
        }

        private static final class Entry {

            private final LoanedBuffer loan;
            private final int length;
            private int offset;

            private Entry(LoanedBuffer loan, int length) {
                this.loan = loan;
                this.length = length;
            }
        }
    }

    /** Minimal {@link MemoryAllocator} handing out shared-arena buffers of the requested size. */
    private static final class FakeAllocator implements MemoryAllocator {

        @Override
        public LoanedBuffer allocate(AllocationHint hint) {
            return new FakeLoanedBuffer(512);
        }

        @Override
        public LoanedBuffer allocateNetwork(int estimatedBytes) {
            return new FakeLoanedBuffer(estimatedBytes);
        }

        @Override
        public LoanedBuffer allocateCarrierSlab(int carrierIndex) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LoanedBuffer allocateInfrastructure(long sizeBytes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MemoryStats stats() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
            // nothing pooled
        }
    }

    private static final class FakeLoanedBuffer implements LoanedBuffer {

        private final Arena arena = Arena.ofShared();
        private final MemorySegment segment;
        private final List<Runnable> closeActions = new ArrayList<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private long size;

        private FakeLoanedBuffer(int capacity) {
            this.segment = arena.allocate(capacity);
        }

        @Override
        public MemorySegment segment() {
            return segment;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public long capacity() {
            return segment.byteSize();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                closeActions.forEach(Runnable::run);
                arena.close();
            }
        }

        @Override
        public LoanedBuffer slice(long offset, long length) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LoanedBuffer view() {
            throw new UnsupportedOperationException();
        }

        @Override
        public LoanedBuffer peek(long offset, long length) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void retain() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int refCount() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setSize(long newSize) {
            this.size = newSize;
        }

        @Override
        public boolean isAlive() {
            return !closed.get();
        }

        @Override
        public void addCloseAction(Runnable action) {
            closeActions.add(action);
        }
    }
}
