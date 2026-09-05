/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.transport;

/**
 * SPI: A protocol-blind network connection that can carry one or more
 * {@link TransportStream} instances.
 *
 * <h2>Protocol Blindness (The Wall)</h2>
 * <ul>
 *   <li><b>Community (TCP):</b> 1 connection = 1 stream. {@link #openStream()} returns
 *       the single bidirectional stream backed by the underlying socket connection.</li>
 *   <li><b>Enterprise (QUIC):</b> 1 connection = N multiplexed streams. Each
 *       {@link #openStream()} call creates a new QUIC stream (RFC 9000 §2).</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * <pre>
 *  accept() / connect()  → OPEN
 *  openStream()           → creates TransportStream(s)
 *  close()                → CLOSED (all child streams are closed)
 * </pre>
 *
 * <h2>Attachment Pattern</h2>
 * <p>The {@link #attachment()} / {@link #setAttachment(Object)} pair provides a
 * protocol-agnostic slot for higher-layer state (e.g., HTTP/3 session resources,
 * QPACK tables). The transport layer never inspects the attachment — it is opaque.
 *
 * @see TransportStream
 * @see TransportEngine
 * @since 0.5
 */
public interface TransportConnection extends AutoCloseable {

    /**
     * Creates and returns a new outbound stream on this connection.
     *
    * <p>For TCP connections, there is one bidirectional stream for the connection.
    * Implementations may return that same stream on repeated {@code openStream()}
    * calls while the connection remains open.
     *
     * <p>For QUIC connections, each call creates a new multiplexed stream.
     *
     * @return the stream for this connection; for TCP, always the same instance while the connection is open;
     *         for QUIC, a new multiplexed stream on each call
     * @throws eu.exeris.kernel.spi.exceptions.transport.TransportException on failure
    * @throws IllegalStateException if this connection is closed or a stream is unavailable
     */
    TransportStream openStream();

    /**
     * Creates and returns a new unidirectional (send-only) stream.
     *
     * <p>Community (TCP) does not support unidirectional streams — this method
     * throws {@link UnsupportedOperationException}.
     *
     * <p>Enterprise (QUIC) creates a locally initiated unidirectional stream
     * (RFC 9000 §2.1). Typically used for HTTP/3 control streams and QPACK,
     * regardless of whether the caller is a client or server endpoint.
     *
     * @return a new unidirectional stream
     * @throws UnsupportedOperationException if not supported (Community/TCP)
     * @throws eu.exeris.kernel.spi.exceptions.transport.TransportException on failure
     */
    TransportStream openUnidirectionalStream();

    /**
     * Returns the remote peer's address as a string (e.g., {@code "192.168.1.1"}).
     *
     * @return remote address; never {@code null}
     */
    String remoteAddress();

    /**
     * Returns the remote peer's port number.
     *
     * <p>For standard IP transports this is normally in the range {@code 1–65535}.
     * Implementations may use {@code 0} or other sentinel values for special cases
     * (for example, before a connection handshake has completed or for local-only
     * transports).
     *
     * @return the remote peer's port number; semantics for special cases are
     * implementation-specific
     */
    int remotePort();

    /**
     * Returns {@code true} if this connection is still open and usable.
     *
     * @return {@code true} if open
     */
    boolean isOpen();

    /**
     * Returns the opaque attachment previously set via {@link #setAttachment(Object)},
     * or {@code null} if none has been set.
     *
     * <p>Used by higher layers (HTTP/3, application handlers) to associate per-connection
     * state without the transport layer knowing the type.
     *
     * <h3>Thread-Safety Contract</h3>
     * <p>Implementations MUST provide safe-publication semantics equivalent to a
     * {@code volatile} read: a value written by {@link #setAttachment(Object)} on one
     * virtual thread MUST be visible to any subsequent {@code attachment()} call on any
     * other virtual thread. Concurrent writes are permitted but callers are responsible
     * for coordinating if ordering between writers matters.
     *
     * @return the attachment, or {@code null}
     */
    Object attachment();

    /**
     * Stores an opaque attachment on this connection.
     *
     * <p>Implementations MUST provide safe-publication semantics equivalent to a
     * {@code volatile} write — visibility to all subsequent {@link #attachment()}
     * readers is guaranteed. Passing {@code null} clears the attachment.
     *
     * @param attachment object to attach (may be {@code null} to clear)
     */
    void setAttachment(Object attachment);

    /**
     * Drives the connection's internal state machine forward.
     *
     * <p>For QUIC, this triggers internal connection maintenance (processing
     * acknowledgments, retransmissions). For TCP, this is typically a no-op.
     *
     * @return {@code true} if the tick produced work (data flushed, acks processed)
     */
    boolean tick();

    /**
     * Closes this connection and all child streams.
     *
     * <p>For QUIC, sends a CONNECTION_CLOSE frame. For TCP, closes the socket.
     * Idempotent — multiple calls are safe.
     */
    @Override
    void close();
}
