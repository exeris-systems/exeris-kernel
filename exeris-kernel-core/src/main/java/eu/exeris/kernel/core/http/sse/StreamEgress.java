/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.sse;

import eu.exeris.kernel.spi.http.StreamEvent;
import eu.exeris.kernel.spi.transport.TransportStream;

/**
 * Core: the protocol side of a held-open event stream — how {@link HttpStreamEngine} writes the
 * response head, one framed event, the graceful end and the abortive end on a protocol other than
 * HTTP/1.1, where the transport stream is the whole connection and a stream is one multiplexed
 * exchange on it.
 *
 * <p>An implementation parks the calling virtual thread while the protocol's credit is short and never
 * queues an event on the heap (ADR-043 obligation 4); it ends a stream without closing the connection
 * that carries it.
 *
 * <p><b>Allocation:</b> up to the implementation; an event is framed once into a buffer handed to the
 * transport.
 * <p><b>Thread confinement:</b> the stream's own thread, one caller at a time; {@link #finish} and
 * {@link #abort} are called by that thread too.
 * <p><b>Ownership:</b> the egress does not own the transport stream, which belongs to the connection;
 * the event buffers it frames belong to the transport once handed over.
 *
 * @since 0.13
 */
public interface StreamEgress {

    /** What became of one event handed to {@link #writeEvent}. */
    enum Outcome {
        /** The event's bytes were handed to the transport. */
        WRITTEN,
        /** The stream or its connection ended first; nothing more can be written. */
        CLOSED,
        /** The authentication deadline passed while the event waited for credit. */
        DEADLINE
    }

    /**
     * Returns the transport stream that carries this stream.
     *
     * @return the transport stream, whose id keys the stream's JFR events
     */
    TransportStream transport();

    /**
     * Returns the protocol's id for this stream on its connection.
     *
     * @return the id, {@code 0} where the protocol has none
     */
    int protocolStreamId();

    /**
     * Writes the SSE response head.
     *
     * @return {@code false} if the stream had already ended, so no head was written
     */
    boolean writeHead();

    /**
     * Frames and sends one event, parking the caller until the protocol's credit allows.
     *
     * @param event                   the event to send
     * @param encodedLength           the number of bytes the event occupies in its SSE framing
     * @param authDeadlineEpochMillis epoch-millis deadline for the wait, {@code <= 0} for none
     * @param eventsEmitted           events already sent on this stream, for diagnostics
     * @return what became of the event
     */
    Outcome writeEvent(StreamEvent event, int encodedLength, long authDeadlineEpochMillis, long eventsEmitted);

    /** Ends the stream gracefully, leaving the connection open; idempotent. */
    void finish();

    /** Ends the stream abortively, leaving the connection open; idempotent. */
    void abort();
}
