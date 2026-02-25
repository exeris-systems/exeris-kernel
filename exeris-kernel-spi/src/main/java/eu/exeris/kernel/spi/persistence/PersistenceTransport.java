/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.persistence;

import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;

/**
 * SPI: Pluggable network transport for persistence connections.
 *
 * <h2>Tier Separation (The Wall)</h2>
 * <ul>
 *   <li><b>Community:</b> Blocking TCP via native socket — Virtual Thread friendly
 *       because blocking calls unmount from carrier threads.</li>
 *   <li><b>Enterprise:</b> High-performance async I/O with zero-copy buffer management.</li>
 * </ul>
 *
 * <h2>SPI Compliance</h2>
 * <p>This interface does NOT reference any implementation-specific I/O subsystem,
 * {@code CompletableFuture}, or {@code ExecutorService}. All I/O is synchronous
 * from the Virtual Thread perspective — implementations park the VT on a completion
 * callback internally.
 *
 * <h2>Zero-Copy Contract</h2>
 * <p>Data is read/written directly from/to {@link java.lang.foreign.MemorySegment}
 * instances. No intermediate heap copies.
 *
 * <h2>Thread Safety</h2>
 * <p>NOT thread-safe. One transport per connection per virtual thread.
 *
 * @since 0.5.0
 * @see TransportProvider
 */
public interface PersistenceTransport extends AutoCloseable {

    /**
     * Establishes a TCP connection to the database server.
     *
     * <p>This call blocks the virtual thread until the connection is established
     * or fails.
     *
     * @throws PersistenceProviderException if the connection cannot be established
     */
    void connect();

    /**
     * Writes exactly {@code length} bytes from {@code data} to the socket.
     *
     * <p>Implementations MUST either write exactly {@code length} bytes and return
     * {@code length}, OR throw {@link PersistenceProviderException}. Partial writes
     * are not permitted — the caller can rely on an all-or-nothing guarantee.
     *
     * @param data   off-heap buffer containing data to send
     * @param length exact number of bytes to write from {@code data}
     * @return {@code length} — the exact number of bytes written
     * @throws PersistenceProviderException on write failure, connection close, or
     *         if fewer than {@code length} bytes could be written
     */
    int write(LoanedBuffer data, int length);

    /**
     * Reads up to {@code maxLength} bytes from the socket into {@code buffer}.
     *
     * <p>Blocks until at least one byte is available or an error occurs.
     *
     * @param buffer    off-heap buffer to receive data into
     * @param maxLength maximum bytes to read
     * @return number of bytes actually read, or {@code -1} on EOF
     * @throws PersistenceProviderException on read failure
     */
    int read(LoanedBuffer buffer, int maxLength);

    /**
     * Returns {@code true} if the transport connection is established.
     *
     * @return connection state
     */
    boolean isConnected();

    /**
     * Returns the underlying socket file descriptor (for diagnostics/JFR only).
     *
     * @return file descriptor, or {@code -1} if not applicable
     */
    long fileDescriptor();

    /**
     * Closes the transport, releasing the socket and any registered buffers.
     *
     * <p>Idempotent — multiple calls are safe.
     */
    @Override
    void close();
}

