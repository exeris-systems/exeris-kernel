/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.websocket;

import eu.exeris.kernel.core.websocket.WebSocketFrameHeader;
import eu.exeris.kernel.core.websocket.WebSocketFrameParser;
import eu.exeris.kernel.spi.transport.TransportStream;

import java.lang.foreign.MemorySegment;

/**
 * The bytes between the socket and the codec: accumulate, hand over whole frames, compact.
 *
 * <p>Extracted from the exchange because they are separate concerns and PMD was right to say so —
 * the exchange is about what a message means, this is about where the bytes are. Confined to one
 * connection's reading thread and not thread-safe, which is all it needs to be.
 */
final class CommunityWebSocketFrameStream {

    /** Largest RFC 6455 frame header: 2 fixed + 8 extended length + 4 mask. */
    private static final int MAX_FRAME_HEADER_BYTES = 14;

    private static final int READ_CHUNK_BYTES = 4096;
    private static final int INITIAL_BUFFER_BYTES = 1024;

    private final TransportStream stream;
    private final long frameCeilingBytes;

    private byte[] buffer = new byte[INITIAL_BUFFER_BYTES];
    private int length;

    /* default */ CommunityWebSocketFrameStream(TransportStream stream, long maxMessageBytes) {
        this.stream = stream;
        // A single frame is bounded independently of the assembler's per-message limit, because the
        // assembler only sees a frame once it is COMPLETE. Without this, a peer declaring a 2 GiB
        // payload would be buffered in full before anything refused it — the limit enforced exactly
        // once the damage was done.
        this.frameCeilingBytes = maxMessageBytes + MAX_FRAME_HEADER_BYTES;
    }

    /** @return the next whole frame's header, or {@code null} when the buffer holds no whole frame */
    /* default */ WebSocketFrameHeader peek() {
        return WebSocketFrameParser.parse(segment(), 0, length);
    }

    /* default */ MemorySegment segment() {
        return MemorySegment.ofArray(buffer);
    }

    /** Discards the frame {@code peek()} returned, leaving any bytes that followed it. */
    /* default */ void consume(WebSocketFrameHeader header) {
        int consumed = Math.toIntExact(header.frameEnd());
        int remaining = length - consumed;
        if (remaining > 0) {
            System.arraycopy(buffer, consumed, buffer, 0, remaining);
        }
        length = remaining;
    }

    /** @return whether the buffer already holds more than any acceptable frame could need */
    /* default */ boolean overCeiling() {
        return length >= frameCeilingBytes;
    }

    /** @return {@code false} at end of stream */
    /* default */ boolean fill() {
        grow(length + READ_CHUNK_BYTES);
        int chunk = Math.min(READ_CHUNK_BYTES, buffer.length - length);
        int read = stream.read(segment().asSlice(length, chunk), chunk);
        if (read < 0) {
            return false;
        }
        length += read;
        return true;
    }

    private void grow(long required) {
        if (buffer.length >= required) {
            return;
        }
        long doubled = Math.max(required, (long) buffer.length * 2);
        byte[] grown = new byte[Math.toIntExact(Math.max(Math.min(doubled, frameCeilingBytes),
                required))];
        System.arraycopy(buffer, 0, grown, 0, length);
        buffer = grown;
    }
}
