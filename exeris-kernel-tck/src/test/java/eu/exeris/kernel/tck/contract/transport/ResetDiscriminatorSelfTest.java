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
import java.lang.foreign.ValueLayout;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Meta-test proving the {@link AbstractTransportStreamTck} reset()-abandon discriminator
 * (issue #180) is <em>not vacuously green</em>: a binding that declares
 * {@link AbstractTransportStreamTck#expectsTrueReset() expectsTrueReset() = true} but actually
 * DRAINS queued data on {@code reset()} (i.e. degrades abort to graceful close) MUST fail the
 * discriminator, while a genuinely-abandoning binding MUST pass it.
 *
 * <p>It drives {@link AbstractTransportStreamTck#runResetAbandonsAtPeerDiscriminator()} directly
 * against in-memory fakes — no sockets, no JUnit-in-JUnit. The fake binding is a {@code static}
 * nested class so it is not itself discovered as a runnable TCK suite.
 */
class ResetDiscriminatorSelfTest {

    @Test
    @DisplayName("discriminator FAILS a draining binding that claims expectsTrueReset() (non-vacuous guard)")
    void drainingBindingFailsDiscriminator() throws Exception {
        SelfTestBinding draining = new SelfTestBinding(false); // drains queued data on reset()
        draining.setUp();
        try {
            assertThatThrownBy(draining::runResetAbandonsAtPeerDiscriminator)
                    .as("a binding that delivers queued data to the peer on reset() but declares "
                            + "expectsTrueReset()=true MUST fail the discriminator")
                    .isInstanceOf(AssertionError.class);
        } finally {
            draining.tearDown();
        }
    }

    @Test
    @DisplayName("discriminator PASSES a genuinely-abandoning binding")
    void abandoningBindingPassesDiscriminator() throws Exception {
        SelfTestBinding abandoning = new SelfTestBinding(true); // abandons queued data on reset()
        abandoning.setUp();
        try {
            assertThatCode(abandoning::runResetAbandonsAtPeerDiscriminator)
                    .doesNotThrowAnyException();
        } finally {
            abandoning.tearDown();
        }
    }

    /**
     * Fake binding: a single {@link DrainOrAbandonStream} instance serves as both writer and reader,
     * so writer-side {@code queueWrite}/{@code reset} and reader-side {@code read} share state.
     */
    private static final class SelfTestBinding extends AbstractTransportStreamTck {

        private final boolean abandonOnReset;

        private SelfTestBinding(boolean abandonOnReset) {
            this.abandonOnReset = abandonOnReset;
        }

        @Override
        protected boolean expectsTrueReset() {
            return true;
        }

        @Override
        protected StreamPair createStreamPair() {
            DrainOrAbandonStream stream = new DrainOrAbandonStream(abandonOnReset);
            return new StreamPair(stream, stream);
        }

        @Override
        protected MemoryAllocator createAllocator() {
            return new FakeAllocator();
        }
    }

    /**
     * In-memory stream holding a single queued byte. {@code reset()} either abandons it (true abort)
     * or delivers it to the read side (graceful-close degradation — the bug the discriminator catches).
     */
    private static final class DrainOrAbandonStream implements TransportStream {

        private final boolean abandonOnReset;
        private boolean pending;
        private byte pendingByte;
        private boolean delivered;
        private byte deliveredByte;

        private DrainOrAbandonStream(boolean abandonOnReset) {
            this.abandonOnReset = abandonOnReset;
        }

        @Override
        public int read(MemorySegment target, int maxBytes) {
            if (maxBytes <= 0) {
                return 0;
            }
            if (delivered) {
                target.set(ValueLayout.JAVA_BYTE, 0, deliveredByte);
                delivered = false;
                return 1;
            }
            return -1;
        }

        @Override
        public void write(MemorySegment source, int length) {
            // not exercised by the discriminator
        }

        @Override
        public void queueWrite(LoanedBuffer buffer, int length) {
            pendingByte = buffer.segment().get(ValueLayout.JAVA_BYTE, 0);
            pending = true;
            buffer.close(); // ownership transfer
        }

        @Override
        public void reset(long errorCode) {
            if (pending) {
                if (!abandonOnReset) {
                    deliveredByte = pendingByte; // drain: deliver to the peer (the degradation)
                    delivered = true;
                }
                pending = false;
            }
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
            return pending;
        }

        @Override
        public void close() {
            // idempotent no-op
        }
    }

    /**
     * Minimal {@link MemoryAllocator} backing each buffer with a confined {@link Arena}. Only
     * {@link #allocate(AllocationHint)} is exercised; the rest are unused by the discriminator.
     */
    private static final class FakeAllocator implements MemoryAllocator {

        private static final int CAPACITY = 512;

        @Override
        public LoanedBuffer allocate(AllocationHint hint) {
            return new FakeLoanedBuffer(CAPACITY);
        }

        @Override
        public LoanedBuffer allocateNetwork(int estimatedBytes) {
            throw new UnsupportedOperationException();
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

        private final Arena arena;
        private final MemorySegment segment;

        private FakeLoanedBuffer(int capacity) {
            this.arena = Arena.ofConfined();
            this.segment = arena.allocate(capacity);
        }

        @Override
        public MemorySegment segment() {
            return segment;
        }

        @Override
        public long size() {
            return segment.byteSize();
        }

        @Override
        public long capacity() {
            return segment.byteSize();
        }

        @Override
        public void close() {
            arena.close();
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
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isAlive() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addCloseAction(Runnable action) {
            throw new UnsupportedOperationException();
        }
    }
}
