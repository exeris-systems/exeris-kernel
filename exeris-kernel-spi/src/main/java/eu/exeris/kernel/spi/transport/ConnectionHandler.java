/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
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
 * <p>Called on the carrier thread — implementations MUST be non-blocking.
 * Heavy initialization should be offloaded to a virtual thread.
 *
 * @since 0.5.0
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

