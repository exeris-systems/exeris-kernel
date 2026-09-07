/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.websocket;

/**
 * Core: one parsed RFC 6455 frame header, with the payload left where it is.
 *
 * <p>Carries offsets rather than bytes on purpose. The payload stays in the caller's segment, so a
 * frame costs this record and nothing else — the copy happens once, when a complete message is
 * turned into a {@code String}, rather than once per frame.
 *
 * @param opcode        the frame's opcode; never {@code null}
 * @param fin           whether this frame completes its message
 * @param masked        whether the payload is masked. A client-to-server frame MUST be
 *                      (RFC 6455 §5.3) and a server-to-client frame MUST NOT be; the parser reports
 *                      what it saw and the caller decides what that means for its direction
 * @param maskingKey    the four-byte key as a big-endian int, meaningless when {@code masked} is false
 * @param payloadOffset absolute offset of the payload's first byte in the parsed segment
 * @param payloadLength payload length in bytes
 */
public record WebSocketFrameHeader(
        WebSocketOpcode opcode,
        boolean fin,
        boolean masked,
        int maskingKey,
        long payloadOffset,
        long payloadLength
) {

    /**
     * Locates where the next frame begins in the same segment, immediately after this frame's
     * payload.
     *
     * @return the offset just past this frame
     */
    public long frameEnd() {
        return payloadOffset + payloadLength;
    }
}
