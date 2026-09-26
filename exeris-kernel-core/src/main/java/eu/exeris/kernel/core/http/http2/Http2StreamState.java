/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.http2;

/**
 * RFC 7540 §5.1 — HTTP/2 Stream State Machine.
 *
 * @since 0.5
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7540#section-5.1">RFC 7540 §5.1</a>
 */
public enum Http2StreamState {

    /**
     * The state every stream starts in, before a {@code HEADERS} frame has been sent or
     * received on it. Only {@code HEADERS} and {@code PRIORITY} frames are valid here.
     */
    IDLE,

    /**
     * Promoted from {@link #IDLE} by this endpoint sending a {@code PUSH_PROMISE}. From here
     * this endpoint may send a {@code HEADERS} frame, which moves the stream to
     * {@link #HALF_CLOSED_REMOTE}, a {@code PRIORITY} frame to reprioritize the reservation, or
     * a {@code RST_STREAM} frame, which closes it.
     */
    RESERVED_LOCAL,

    /**
     * Promoted from {@link #IDLE} by the peer sending a {@code PUSH_PROMISE}. Receiving the
     * promised {@code HEADERS} frame moves the stream to {@link #HALF_CLOSED_LOCAL}; either
     * side may instead send {@code RST_STREAM} to close it, or {@code PRIORITY} to
     * reprioritize the reservation.
     */
    RESERVED_REMOTE,

    /**
     * The state in which both peers may send frames of any type. Sending or receiving a frame
     * with the {@code END_STREAM} flag set moves the stream to {@link #HALF_CLOSED_LOCAL} or
     * {@link #HALF_CLOSED_REMOTE} respectively; either side sending {@code RST_STREAM} instead
     * moves it directly to {@link #CLOSED}.
     */
    OPEN,

    /**
     * This endpoint has sent its last frame and may send only {@code WINDOW_UPDATE},
     * {@code PRIORITY}, or {@code RST_STREAM} from here on; the peer may still send. The stream
     * moves to {@link #CLOSED} once the peer also sends {@code END_STREAM} or either side sends
     * {@code RST_STREAM}.
     */
    HALF_CLOSED_LOCAL,

    /**
     * The peer has sent its last frame and is expected to send only {@code WINDOW_UPDATE},
     * {@code PRIORITY}, or {@code RST_STREAM} from here on; this endpoint may still send. The
     * stream moves to {@link #CLOSED} once this endpoint also sends {@code END_STREAM} or
     * either side sends {@code RST_STREAM}.
     */
    HALF_CLOSED_REMOTE,

    /**
     * The terminal state. No further frames may be sent on the stream, other than
     * {@code PRIORITY}.
     */
    CLOSED
}
