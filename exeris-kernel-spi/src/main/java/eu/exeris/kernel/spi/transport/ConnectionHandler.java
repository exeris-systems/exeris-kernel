/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.transport;

/**
 * SPI: Callback for newly established {@link TransportConnection} instances.
 *
 * <h2>Purpose</h2>
 * <p>Invoked by the {@link TransportEngine} immediately after a connection's
 * handshake completes (TLS negotiation finished, connection ready for streams).
 * This is the hook for protocol-level session initialization:
 * <ul>
 *   <li><b>Enterprise (QUIC/HTTP/3):</b> create server control streams,
 *       send SETTINGS frame, initialize QPACK encoder/decoder streams.</li>
 *   <li><b>Community (TCP/HTTP/2):</b> send connection preface, exchange SETTINGS.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * <p>Called on the carrier thread of the virtual-thread scheduler — implementations
 * MUST be effectively non-blocking.
 *
 * <p><strong>Allowed on the carrier thread:</strong> very short, constant-time, in-memory
 * operations only, such as: updating cheap in-memory state (e.g., setting an attachment,
 * incrementing a counter), or scheduling follow-up work on a virtual thread via
 * {@code StructuredTaskScope}.
 *
 * <p><strong>Must NOT be done on the carrier thread:</strong> any operation that may
 * block, perform I/O, or take non-trivial time — including network or disk I/O,
 * CPU-heavy / allocation-heavy initialisation (parsers, cryptographic primitives,
 * large object graphs), or interaction with external systems (databases, service
 * discovery, etc.). Blocking the carrier thread can starve many virtual threads and
 * degrade overall kernel throughput.
 *
 * @since 0.5
 * @see TransportEngine#setConnectionHandler(ConnectionHandler)
 * @see TransportConnection
 */
@FunctionalInterface
public interface ConnectionHandler {

    /**
     * Handles a newly established connection.
     *
     * @param connection the ready connection (handshake complete)
     */
    void onConnectionEstablished(TransportConnection connection);
}
