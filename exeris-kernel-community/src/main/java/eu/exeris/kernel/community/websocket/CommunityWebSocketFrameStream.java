/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.websocket;

import eu.exeris.kernel.core.websocket.WebSocketFrameHeader;
import eu.exeris.kernel.core.websocket.WebSocketFrameParser;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.transport.TransportStream;

import java.lang.foreign.MemorySegment;

/**
 * The bytes between the socket and the codec: accumulate, hand over whole frames, compact.
 *
 * <p>Extracted from the exchange because they are separate concerns and PMD was right to say so —
 * the exchange is about what a message means, this is about where the bytes are. Confined to one
 * connection's reading thread and not thread-safe, which is all it needs to be.
 *
 * <p>Off-heap for the reason stated on {@link CommunityWebSocketEgress}: {@code TransportStream}
 * documents both directions as operating on a {@link LoanedBuffer}'s segment, and the ingress side
 * is the same buffer the egress side is — one per connection, grown when a frame does not fit,
 * rather than one allocation per read.
 */
final class CommunityWebSocketFrameStream implements AutoCloseable {

    /** Largest RFC 6455 frame header: 2 fixed + 8 extended length + 4 mask. */
    private static final int MAX_FRAME_HEADER_BYTES = 14;

    private static final int READ_CHUNK_BYTES = 4096;
    private static final int INITIAL_BUFFER_BYTES = 4096;

    private final TransportStream stream;
    private final MemoryAllocator allocator;
    private final long frameCeilingBytes;

    private LoanedBuffer inbound;
    private int length;

    /* default */ CommunityWebSocketFrameStream(TransportStream stream, MemoryAllocator allocator,
                                                long maxMessageBytes) {
        this.stream = stream;
        this.allocator = allocator;
        // A single frame is bounded independently of the assembler's per-message limit, because the
        // assembler only sees a frame once it is COMPLETE. Without this, a peer declaring a 2 GiB
        // payload would be buffered in full before anything refused it — the limit enforced exactly
        // once the damage was done.
        this.frameCeilingBytes = maxMessageBytes + MAX_FRAME_HEADER_BYTES;
        this.inbound = allocator.allocateNetwork(INITIAL_BUFFER_BYTES);
    }

    /** @return the next whole frame's header, or {@code null} when the buffer holds no whole frame */
    /* default */ WebSocketFrameHeader peek() {
        return WebSocketFrameParser.parse(segment(), 0, length);
    }

    /* default */ MemorySegment segment() {
        return inbound.segment();
    }

    /** Discards the frame {@code peek()} returned, leaving any bytes that followed it. */
    /* default */ void consume(WebSocketFrameHeader header) {
        int consumed = Math.toIntExact(header.frameEnd());
        int remaining = length - consumed;
        if (remaining > 0) {
            MemorySegment.copy(inbound.segment(), consumed, inbound.segment(), 0, remaining);
        }
        length = remaining;
    }

    /** @return whether the buffer already holds more than any acceptable frame could need */
    /* default */ boolean overCeiling() {
        return length >= frameCeilingBytes;
    }

    /** @return {@code false} at end of stream */
    /* default */ boolean fill() {
        // Bounded by the ceiling, not just by the chunk: overCeiling() is checked before this runs,
        // so the request stays inside [length, ceiling] and grow() can clamp without the degenerate
        // case where the lower bound exceeds the upper one.
        grow(Math.min((long) length + READ_CHUNK_BYTES, frameCeilingBytes));
        int chunk = Math.toIntExact(Math.min(READ_CHUNK_BYTES, inbound.capacity() - length));
        int read = stream.read(inbound.segment().asSlice(length, chunk), chunk);
        if (read < 0) {
            return false;
        }
        length += read;
        return true;
    }

    // CloseResource suppressed, with the ownership stated rather than assumed: the buffer allocated
    // here REPLACES the field and is closed by close(), while the one it replaces is closed on the
    // line below. PMD sees a local that escapes the method and cannot follow either half. A
    // try-with-resources here would free the buffer the connection is about to write into.
    @SuppressWarnings("PMD.CloseResource")
    private void grow(long required) {
        if (inbound.capacity() >= required) {
            return;
        }
        long doubled = Math.max(required, inbound.capacity() * 2);
        int target = Math.toIntExact(Math.clamp(doubled, required, frameCeilingBytes));
        LoanedBuffer grown = allocator.allocateNetwork(target);
        MemorySegment.copy(inbound.segment(), 0, grown.segment(), 0, length);
        inbound.close();
        inbound = grown;
    }

    @Override
    public void close() {
        inbound.close();
    }
}
