/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.tck.contract.transport;

import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.transport.TransportStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

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

    /** Provides a {@link MemoryAllocator} for buffer creation in tests. */
    protected abstract MemoryAllocator createAllocator();

    /** Stream pair for loopback testing. */
    protected record StreamPair(TransportStream writer, TransportStream reader) {}

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
            // Simply assert no exception — TCP always returns true, QUIC depends on stream type
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
        void roundTrip() {
            try (LoanedBuffer sendBuf = allocator.allocate(AllocationHint.SMALL)) {
                // Write a sentinel pattern
                MemorySegment seg = sendBuf.segment();
                seg.set(ValueLayout.JAVA_LONG, 0, 0xDEADCAFE_BABE1234L);
                int length = Long.BYTES;

                streams.writer().write(seg, length);

                // Read on the other side
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
    // queueWrite() ownership transfer
    // =========================================================================

    @Nested
    @DisplayName("queueWrite() ownership transfer")
    class QueueWriteOwnership {

        @Test
        @DisplayName("queueWrite() takes ownership — buffer refCount decremented or transferred")
        void ownershipTransfer() {
            LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO);
            buf.segment().set(ValueLayout.JAVA_BYTE, 0, (byte) 0xAB);

            // Ownership transfers to the transport — caller MUST NOT close
            streams.writer().queueWrite(buf, 1);

            // Buffer ownership transferred to transport; no safe assertions can be made
            // on buf.refCount() here. The SPI contract guarantees the transport either
            // consumes the buffer immediately (refCount → 0) or retains it for async
            // flush (refCount ≥ 1). Both are valid post-conditions — what matters is
            // that queueWrite() did not throw, proving the ownership transfer succeeded.

            // Verify the SPI contract: after queueWrite() the stream MUST report
            // hasPendingData() == true — at least one byte has been queued but not
            // yet flushed by the carrier loop.
            assertThat(streams.writer().hasPendingData())
                    .as("hasPendingData() MUST return true immediately after queueWrite() — "
                            + "data is queued for async flush, not yet sent")
                    .isTrue();
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
}


