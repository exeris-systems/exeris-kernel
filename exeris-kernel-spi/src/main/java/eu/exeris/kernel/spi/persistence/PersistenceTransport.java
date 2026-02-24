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
 *   <li><b>Enterprise:</b> io_uring-based async I/O with registered buffers,
 *       multishot recvmsg, and provided buffer groups.</li>
 * </ul>
 *
 * <h2>SPI Compliance</h2>
 * <p>This interface does NOT reference {@code io_uring}, {@code NativeSocket},
 * {@code CompletableFuture}, or {@code ExecutorService}. All I/O is synchronous
 * from the Virtual Thread perspective — the io_uring Enterprise implementation
 * parks the VT on a completion callback internally.
 *
 * <h2>Zero-Copy Contract</h2>
 * <p>Data is read/written directly from/to {@link java.lang.foreign.MemorySegment}
 * instances. No intermediate heap copies. Enterprise buffers may be io_uring-registered
 * for kernel-bypass DMA.
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
     * or fails. The implementation MAY use io_uring async connect internally.
     *
     * @throws PersistenceProviderException if the connection cannot be established
     */
    void connect();

    /**
     * Writes {@code length} bytes from {@code data} to the socket.
     *
     * <p>Blocks until all bytes are written or an error occurs.
     * Enterprise implementations may use io_uring SQE submission internally.
     *
     * @param data   off-heap buffer containing data to send
     * @param length number of bytes to write
     * @return number of bytes actually written
     * @throws PersistenceProviderException on write failure or connection close
     */
    int write(LoanedBuffer data, int length);

    /**
     * Reads up to {@code maxLength} bytes from the socket into {@code buffer}.
     *
     * <p>Blocks until at least one byte is available or an error occurs.
     * Enterprise implementations may use io_uring SQE submission internally.
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

