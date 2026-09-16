/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.websocket;

import eu.exeris.kernel.core.websocket.WebSocketFrameHeader;
import eu.exeris.kernel.core.websocket.WebSocketFrameParser;
import eu.exeris.kernel.core.websocket.WebSocketOpcode;
import eu.exeris.kernel.spi.websocket.WebSocketCloseCode;

import java.lang.foreign.MemorySegment;

/**
 * Ping, pong and close: the protocol's own traffic, kept apart from what a message means.
 *
 * <p>Stateless. The exchange owns the connection's state and applies whatever this reports, so the
 * mechanics of a control frame stay readable next to each other instead of interleaved with
 * reassembly.
 */
final class CommunityWebSocketControlFrames {

    private static final int CLOSE_CODE_BYTES = 2;

    private CommunityWebSocketControlFrames() {
    }

    /**
     * What the exchange must do next: nothing, or close having observed {@code closeCode} and echo
     * {@code echoCode}.
     *
     * <p>The echo is <em>reported</em> rather than sent here so the exchange can flip its state
     * first. Sending the close and then marking the connection closed lets a peer observe the echo
     * and still get a successful {@code send()} back — a race that a fast machine can mask
     * entirely.
     */
    /* default */ record Reaction(boolean closing, int closeCode, WebSocketCloseCode echoCode) {
        /* default */ static final Reaction NONE =
                new Reaction(false, 0, WebSocketCloseCode.NORMAL_CLOSURE);
    }

    /* default */ static Reaction handle(MemorySegment segment, WebSocketFrameHeader header,
                                         CommunityWebSocketEgress egress) {
        byte[] payload = new byte[Math.toIntExact(header.payloadLength())];
        WebSocketFrameParser.copyPayload(segment, header, payload, 0);
        return switch (header.opcode()) {
            case PING -> {
                // RFC 6455 §5.5.2: a pong carries the ping's payload back, unchanged.
                egress.writeFrame(WebSocketOpcode.PONG, payload);
                yield Reaction.NONE;
            }
            // A pong is an answer, and this binding asks no questions — see the note on
            // keepAliveIntervalMillis in CommunityWebSocketProvider.
            case PONG -> Reaction.NONE;
            case CLOSE -> {
                int observed = payload.length >= CLOSE_CODE_BYTES
                        ? ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF)
                        : WebSocketCloseCode.NORMAL_CLOSURE.code();
                // The code is ECHOED rather than flattened to NORMAL_CLOSURE, which is the whole
                // point of ADR-084 §8: answering a peer that said GOING_AWAY with "1000, fine"
                // destroys exactly the distinction a transport exists to carry, and leaves an
                // operator unable to tell a client that left from one that was pushed.
                yield new Reaction(true, observed, echoable(observed));
            }
            default -> throw new IllegalStateException(
                    "not a control opcode: " + header.opcode());
        };
    }

    /**
     * Maps a received close code to one that may be echoed.
     *
     * <p>A received code is not automatically a sendable one: 1005 and 1006 exist only to describe
     * what happened locally and must never reach the wire, and a peer may send a code outside the
     * enum entirely. Either way the answer is NORMAL_CLOSURE — the connection still closes, and the
     * code the peer actually sent is what the exchange records.
     */
    private static WebSocketCloseCode echoable(int received) {
        for (WebSocketCloseCode candidate : WebSocketCloseCode.values()) {
            if (candidate.code() == received) {
                return candidate.sendable() ? candidate : WebSocketCloseCode.NORMAL_CLOSURE;
            }
        }
        return WebSocketCloseCode.NORMAL_CLOSURE;
    }
}
