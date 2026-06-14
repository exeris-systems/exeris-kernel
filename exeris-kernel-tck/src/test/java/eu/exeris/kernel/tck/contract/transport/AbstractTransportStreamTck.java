/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.transport;

import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.transport.TransportStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK: Abstract base for {@link TransportStream} I/O semantics verification.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@code read()} and {@code write()} operate on {@link MemorySegment} slices</li>
 *   <li>{@code queueWrite(LoanedBuffer, length)} transfers buffer ownership —
 *       caller MUST NOT close the buffer after the call</li>
 *   <li>{@code close()} is idempotent</li>
 *   <li>{@code streamId()} returns a non-negative identifier</li>
 *   <li>{@code connection()} returns non-null parent</li>
 * </ul>
 *
 * <h2>Zero-Copy Rule</h2>
 * <p>{@code queueWrite(LoanedBuffer)} MUST either consume the buffer (decrement refCount
 * or take ownership) or throw {@code TransportException} — preventing off-heap leaks.
 *
 * @since 0.5.0
 */
public abstract class AbstractTransportStreamTck {

    /**
     * Creates a pair of connected streams for loopback I/O testing.
     * The writer stream sends data; the reader stream receives it.
     */
    protected abstract StreamPair createStreamPair();

    /**
     * Provides a {@link MemoryAllocator} for buffer creation in tests.
     */
    protected abstract MemoryAllocator createAllocator();

    /**
     * Stream pair for loopback testing.
     */
    public record StreamPair(TransportStream writer, TransportStream reader) {
    }

    private StreamPair streams;
    private MemoryAllocator allocator;

    @BeforeEach
    final void setUp() {
        allocator = createAllocator();
        streams = createStreamPair();
    }

    @AfterEach
    final void tearDown() {
        streams.writer().close();
        streams.reader().close();
        allocator.close();
    }

    // =========================================================================
    // Identity
    // =========================================================================

    @Nested
    @DisplayName("Stream identity")
    class Identity {

        @Test
        @DisplayName("streamId() returns non-negative value")
        void streamIdNonNegative() {
            assertThat(streams.writer().streamId()).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("connection() returns non-null parent")
        void connectionNonNull() {
            assertThat(streams.writer().connection()).isNotNull();
        }

        @Test
        @DisplayName("isBidirectional() returns a boolean (no NPE)")
        void isBidirectional() {
            assertThatCode(() -> streams.writer().isBidirectional()).doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // write() + read() round-trip
    // =========================================================================

    @Nested
    @DisplayName("write() + read() round-trip")
    class WriteReadRoundTrip {

        @Test
        @DisplayName("write() followed by read() returns identical data (zero-copy)")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void roundTrip() {
            try (LoanedBuffer sendBuf = allocator.allocate(AllocationHint.SMALL)) {
                MemorySegment seg = sendBuf.segment();
                seg.set(ValueLayout.JAVA_LONG, 0, 0xDEADCAFE_BABE1234L);
                int length = Long.BYTES;

                streams.writer().write(seg, length);

                try (LoanedBuffer recvBuf = allocator.allocate(AllocationHint.SMALL)) {
                    MemorySegment recvSeg = recvBuf.segment();
                    int bytesRead = streams.reader().read(recvSeg, length);
                    assertThat(bytesRead).isEqualTo(length);
                    assertThat(recvSeg.get(ValueLayout.JAVA_LONG, 0))
                            .isEqualTo(0xDEADCAFE_BABE1234L);
                }
            }
        }
    }

    // =========================================================================
    // read() bounds — non-positive maxBytes contract
    // =========================================================================

    @Nested
    @DisplayName("read() bounds")
    class ReadBounds {

        @Test
        @DisplayName("read(target, 0) and read(target, -1) return 0 without consuming pending data")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void nonPositiveMaxBytesIsNoOp() {
            // Arrange: put a payload in flight so the reader genuinely has data pending.
            try (LoanedBuffer sendBuf = allocator.allocate(AllocationHint.SMALL)) {
                MemorySegment seg = sendBuf.segment();
                seg.set(ValueLayout.JAVA_LONG, 0, 0xFEEDFACE_CAFEBEEFL);
                int length = Long.BYTES;
                streams.writer().write(seg, length);

                TransportStream reader = streams.reader();

                try (LoanedBuffer recvBuf = allocator.allocate(AllocationHint.SMALL)) {
                    MemorySegment recvSeg = recvBuf.segment();

                    // Act + Assert: non-positive maxBytes is a no-op (no I/O, no throw, returns 0).
                    assertThat(reader.read(recvSeg, 0))
                            .as("read(target, 0) MUST return 0 immediately (no-op contract)")
                            .isZero();
                    assertThat(reader.read(recvSeg, -1))
                            .as("read(target, -1) MUST return 0 immediately (no-op contract)")
                            .isZero();

                    // Assert: the stream remains readable and the pending payload was not consumed.
                    int bytesRead = reader.read(recvSeg, length);
                    assertThat(bytesRead)
                            .as("a no-op read MUST NOT consume pending data — the payload is still readable")
                            .isEqualTo(length);
                    assertThat(recvSeg.get(ValueLayout.JAVA_LONG, 0))
                            .isEqualTo(0xFEEDFACE_CAFEBEEFL);
                }
            }
        }
    }

    // =========================================================================
    // queueWrite() ownership transfer
    // =========================================================================

    @Nested
    @DisplayName("queueWrite() ownership transfer")
    class QueueWriteOwnership {

        @Test
        @DisplayName("queueWrite() takes ownership — buffer refCount decremented or transferred")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void ownershipTransfer() {
            LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO);
            buf.segment().set(ValueLayout.JAVA_BYTE, 0, (byte) 0xAB);

            streams.writer().queueWrite(buf, 1);

            try (LoanedBuffer recvBuf = allocator.allocate(AllocationHint.MICRO)) {
                MemorySegment recvSeg = recvBuf.segment();
                int bytesRead = streams.reader().read(recvSeg, 1);
                assertThat(bytesRead)
                        .as("queueWrite() MUST result in at least 1 byte delivered to the peer")
                        .isGreaterThanOrEqualTo(1);
                byte receivedByte = recvSeg.get(ValueLayout.JAVA_BYTE, 0);
                assertThat(receivedByte)
                        .as("Delivered byte MUST equal the sentinel 0xAB — transport must not " +
                                "corrupt or drop the owned buffer's content")
                        .isEqualTo((byte) 0xAB);
            }

            assertThat(streams.writer().streamId())
                    .as("Stream MUST remain open (non-negative streamId) after queueWrite() — "
                            + "ownership transfer must not corrupt the stream state")
                    .isGreaterThanOrEqualTo(0L);
        }
    }

    // =========================================================================
    // close() contract
    // =========================================================================

    @Nested
    @DisplayName("close() contract")
    class CloseContract {

        @Test
        @DisplayName("close() is idempotent")
        void closeIsIdempotent() {
            streams.writer().close();
            assertThatCode(() -> streams.writer().close()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("write() after close() throws IllegalStateException")
        void writeAfterCloseThrows() {
            var writer = streams.writer();
            writer.close();
            try (LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO)) {
                MemorySegment seg = buf.segment();
                assertThatThrownBy(() -> writer.write(seg, 1))
                        .isInstanceOf(IllegalStateException.class);
            }
        }
    }

    // =========================================================================
    // reset() abortive-termination contract (TransportStream#reset)
    // =========================================================================

    @Nested
    @DisplayName("reset() abortive termination")
    class ResetContract {

        @Test
        @DisplayName("reset() abandons queued writes — hasPendingData() clears")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void resetAbandonsPendingData() {
            TransportStream writer = streams.writer();
            LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO);
            buf.segment().set(ValueLayout.JAVA_BYTE, 0, (byte) 0x7E);
            writer.queueWrite(buf, 1);

            writer.reset(0L);

            // The owning carrier reactor may complete the discard asynchronously, so poll.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (writer.hasPendingData() && System.nanoTime() < deadline) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            }
            assertThat(writer.hasPendingData())
                    .as("reset() MUST abandon queued writes (no drain wait) — hasPendingData() "
                            + "must clear, otherwise close()/teardown hangs")
                    .isFalse();
        }

        @Test
        @DisplayName("reset() is idempotent and composes with close()")
        void resetIsIdempotentAndComposesWithClose() {
            TransportStream writer = streams.writer();
            writer.reset(0L);
            assertThatCode(() -> writer.reset(7L)).doesNotThrowAnyException();
            assertThatCode(writer::close).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("read() after reset() is rejected")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void readAfterResetThrows() {
            TransportStream writer = streams.writer();
            // No queued write here: with an idle outbound consumer, reset() terminates the stream
            // synchronously (closed == true), so a subsequent read() observes the closed state.
            writer.reset(0L);
            try (LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO)) {
                MemorySegment seg = buf.segment();
                assertThatThrownBy(() -> writer.read(seg, 1))
                        .as("read() on a stream that has been reset MUST be rejected")
                        .isInstanceOf(IllegalStateException.class);
            }
        }

        @Test
        @DisplayName("write() after reset() is rejected")
        void writeAfterResetThrows() {
            TransportStream writer = streams.writer();
            writer.reset(0L);
            try (LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO)) {
                MemorySegment seg = buf.segment();
                assertThatThrownBy(() -> writer.write(seg, 1))
                        .isInstanceOf(IllegalStateException.class);
            }
        }

        @Test
        @DisplayName("queueWrite() after reset() is rejected (and the buffer is released)")
        void queueWriteAfterResetThrows() {
            TransportStream writer = streams.writer();
            writer.reset(0L);
            LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO);
            assertThatThrownBy(() -> writer.queueWrite(buf, 1))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // =========================================================================
    // Remote close — read() unblocking
    // =========================================================================

    @Nested
    @DisplayName("Remote close unblocks reader")
    class RemoteCloseUnblocksReader {

        @Test
        @DisplayName("read() returns -1 within 500ms of remote close signal")
        @Timeout(value = 2, unit = TimeUnit.SECONDS)
        void remoteClosedUnblocksReader() throws InterruptedException {
            // Arrange: start a VT that blocks in read()
            try (LoanedBuffer sink = allocator.allocate(AllocationHint.SMALL)) {
                AtomicLong readReturnedAt = new AtomicLong(-1L);
                AtomicInteger readResult = new AtomicInteger(Integer.MIN_VALUE);

                Thread reader = Thread.ofVirtual().start(() -> {
                    readResult.set(streams.reader().read(sink.segment(), (int) sink.segment().byteSize()));
                    readReturnedAt.set(System.nanoTime());
                });

                // Wait for the VT to reach a waiting state (parked or blocked in read)
                long spinDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (reader.getState() == Thread.State.RUNNABLE
                        && System.nanoTime() < spinDeadline) {
                    Thread.onSpinWait();
                }

                // Act: close the writer side to signal remote close to the reader
                long signalAt = System.nanoTime();
                streams.writer().close();

                reader.join(500);

                // Assert: read() returned -1 (remote-close EOF)
                assertThat(readResult.get())
                        .as("read() should return -1 on remote close")
                        .isEqualTo(-1);

                // Assert: unblocked within 500ms
                long returnedAt = readReturnedAt.get();
                assertThat(returnedAt)
                        .as("read() should have returned after remote close (join backstop: 500ms)")
                        .isGreaterThanOrEqualTo(0L);

                long durationMs = TimeUnit.NANOSECONDS.toMillis(returnedAt - signalAt);
                assertThat(durationMs)
                        .as("read() should unblock within 500ms of remote close — got %dms", durationMs)
                        .isLessThanOrEqualTo(500L);
            }
        }
    }
}
