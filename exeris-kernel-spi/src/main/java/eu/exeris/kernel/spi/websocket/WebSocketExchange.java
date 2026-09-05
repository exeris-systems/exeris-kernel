/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.websocket;

/**
 * SPI: one accepted duplex connection, from the handler's side.
 *
 * <p>A sibling of {@code HttpStreamExchange} rather than a mode on it, for the reason ADR-043
 * obligation 1 gives and ADR-084 §2 repeats: a mode flag conditions an invariant that is currently
 * unconditional. Respond-once stays respond-once and SSE stays one-directional.
 *
 * <h2>Threading</h2>
 * <p>The handler runs on its own virtual thread and drives the connection by blocking:
 * {@link #receive()} until a message arrives, {@link #send(String)} until there is credit. A
 * consumer that wants concurrent work per message dispatches it to further virtual threads; the
 * connection's ordering stays the handler's to decide, which is what LSP needs since it may answer
 * requests out of order.
 *
 * <p><strong>{@code send} is safe from more than one thread and serialises.</strong> RFC 6455
 * forbids interleaving the frames of two messages on one connection, so concurrent senders are
 * ordered rather than rejected — but they queue on each other, and a slow peer therefore blocks
 * every sender on that connection, not just the one that filled the window.
 *
 * <h2>Backpressure</h2>
 * <p>{@link #send(String)} parks the calling virtual thread until the egress window has credit. It
 * never queues on the heap — ADR-043 obligation 4, and the reason is sharper here than for a
 * response: on a connection held for the length of an editing session, a queue is unbounded. As in
 * ADR-043, a park deadline is deliberately not part of this contract; it is policy.
 *
 * <h2>Why the two directions end differently</h2>
 * <p>{@link #receive()} returns {@code null} when the connection has closed; {@link #send(String)}
 * throws. That asymmetry is deliberate: a closed connection is the <em>ordinary</em> end of a
 * receive loop, and the handler should fall out of it rather than catch its way out — while a send
 * that cannot happen means the handler had something to say and could not, which is a failure it
 * has to see.
 *
 * @since 0.12
 */
public interface WebSocketExchange {

    /**
     * Returns the identity of this connection.
     *
     * @return the session; never {@code null}, stable for the connection's lifetime
     */
    WebSocketSession session();

    /**
     * Blocks until the peer sends a text message, or the connection closes.
     *
     * <p>Fragmented messages are reassembled before they are returned: a peer that splits a large
     * message across continuation frames is speaking the protocol correctly, and the handler sees
     * one message. A message exceeding the configured limit is not returned — the connection closes
     * with {@link WebSocketCloseCode#MESSAGE_TOO_BIG}, because truncating it would hand the handler
     * something the peer never sent.
     *
     * @return the next text message, or {@code null} once the connection has closed
     */
    String receive();

    /**
     * Sends a text message, parking the calling virtual thread until the egress window has credit.
     *
     * @param message the text to send; must not be null
     * @throws WebSocketClosedException when the connection is already closed or the peer has gone
     */
    void send(String message);

    /**
     * Closes with {@link WebSocketCloseCode#NORMAL_CLOSURE} and no reason.
     *
     * <p>Idempotent: closing an already-closed connection does nothing rather than throwing, so a
     * handler's cleanup path does not have to know whether the peer closed first.
     */
    void close();

    /**
     * Closes with an explicit code and reason.
     *
     * @param code   the close code; must not be null and must be {@link WebSocketCloseCode#sendable()}
     * @param reason a short reason, carried in the close frame; must not be null, may be empty
     */
    void close(WebSocketCloseCode code, String reason);
}
