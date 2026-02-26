/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.transport;

import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.lang.foreign.MemorySegment;

/**
 * SPI: A bidirectional byte stream over a {@link TransportConnection}.
 *
 * <h2>Protocol Blindness (The Wall)</h2>
 * <p>Business logic reads and writes bytes via this interface without knowing
 * whether the underlying transport is:
 * <ul>
 *   <li><b>Community:</b> a single TCP connection (1 connection = 1 stream)</li>
 *   <li><b>Enterprise:</b> a multiplexed QUIC stream (N streams per connection)</li>
 * </ul>
 *
 * <h2>Zero-Copy Contract</h2>
 * <p>Both {@link #read} and {@link #write} operate on {@link MemorySegment} slices
 * within a {@link LoanedBuffer}. The transport implementation MUST NOT copy data
 * between heap and off-heap. Community implementations may read directly from the
 * underlying connection into the segment; Enterprise implementations use native
 * asynchronous I/O completions that write into pre-registered slab buffers.
 *
 * <h2>Threading Model</h2>
 * <p>Each stream is owned by exactly one virtual thread (the "1 VT per stream" model).
 * Calls to {@link #read} may block the virtual thread (which unmounts from the carrier),
 * but MUST NOT pin the carrier thread.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Implementations MUST avoid identity operations ({@code ==}, {@code synchronized})
 * on this interface. Future migration to a value-class-backed inline representation
 * is planned for hot-path stream metadata.
 *
 * @since 0.5.0
 * @see TransportConnection
 * @see StreamHandler
 */
public interface TransportStream extends AutoCloseable {

    /**
     * Reads up to {@code maxBytes} bytes from this stream into the given segment.
     *
     * <p>This call may block the calling virtual thread until data arrives.
     * Returns the number of bytes actually read, or {@code -1} if the stream
     * has been cleanly closed by the remote peer (EOF).
     *
     * <h2>Zero-Copy</h2>
     * <p>Enterprise implementations fill the target segment directly from the
     * native asynchronous I/O completion buffer (zero intermediate copy). Community
     * implementations read directly into the off-heap segment from the underlying
     * connection.
     *
     * @param target   off-heap segment to read into (from {@link LoanedBuffer#segment()})
     * @param maxBytes maximum number of bytes to read (must be ≤ {@code target.byteSize()})
     * @return number of bytes read, or {@code -1} on EOF
     * @throws eu.exeris.kernel.spi.exceptions.transport.TransportException on I/O failure
     * @throws IllegalStateException if this stream has been closed
     */
    int read(MemorySegment target, int maxBytes);

    /**
     * Writes {@code length} bytes from the given segment to this stream.
     *
     * <p>The caller retains ownership of the segment. The transport implementation
     * MUST NOT hold a reference to the segment after this call returns.
     *
     * <h2>Backpressure</h2>
     * <p>If the peer's receive window is full, this call blocks the virtual thread
     * until space becomes available or the stream is reset.
     *
     * @param source off-heap segment containing data to send
     * @param length number of bytes to write (must be ≤ {@code source.byteSize()})
     * @throws eu.exeris.kernel.spi.exceptions.transport.TransportException on I/O failure
     * @throws IllegalStateException if this stream has been closed
     */
    void write(MemorySegment source, int length);

    /**
     * Queues a {@link LoanedBuffer} for asynchronous transmission.
     *
     * <p><strong>Ownership transfer:</strong> the transport takes ownership of the buffer.
     * The caller MUST NOT close the buffer after this call — the transport will close it
     * after the write completes. If this method throws any exception (including
     * {@link IllegalStateException}), the buffer is closed by the transport before
     * the exception propagates. The caller must NOT close the buffer after an exception.
     *
     * <p>This method is non-blocking: data is queued and flushed by the carrier loop.
     *
     * @param buffer loaned buffer to send (ownership transferred)
     * @param length number of valid bytes in the buffer (from offset 0)
     * @throws eu.exeris.kernel.spi.exceptions.transport.TransportException on failure
     * @throws IllegalStateException if this stream has been closed
     */
    void queueWrite(LoanedBuffer buffer, int length);

    /**
     * Returns the stream identifier.
     *
     * <p>For QUIC, this is the RFC 9000 stream ID. For TCP, this is a synthetic
     * monotonic identifier assigned by the transport engine.
     *
     * @return stream ID (≥ 0)
     */
    long streamId();

    /**
     * Returns {@code true} if this is a bidirectional stream (read + write).
     *
     * <p>For TCP, always returns {@code true}. For QUIC, depends on stream type.
     *
     * @return {@code true} if bidirectional
     */
    boolean isBidirectional();

    /**
     * Returns {@code true} if this stream was initiated by the remote peer (client).
     *
     * @return {@code true} if client-initiated
     */
    boolean isClientInitiated();

    /**
     * Returns the parent connection that owns this stream.
     *
     * @return parent connection; never {@code null}
     */
    TransportConnection connection();

    /**
     * Returns {@code true} if this stream has pending outbound data queued
     * via {@link #queueWrite} that has not yet been flushed.
     *
     * @return {@code true} if data is pending
     */
    boolean hasPendingData();

    /**
     * Closes this stream, releasing any associated resources.
     *
     * <p>For QUIC, signals end-of-stream by sending a STREAM frame with the FIN flag set
     * (per RFC 9000). For TCP, half-closes the output side. Idempotent — multiple calls
     * are safe.
     */
    @Override
    void close();
}

