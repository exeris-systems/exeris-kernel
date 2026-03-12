/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.http.http2;

import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * RFC 7540 §6.2 / §6.10 — HTTP/2 HPACK header block assembler.
 *
 * <h2>Responsibility</h2>
 * <p>Accumulates the HPACK fragment bytes from a HEADERS (or PUSH_PROMISE) frame and
 * zero or more subsequent CONTINUATION frames into a single contiguous
 * {@link MemorySegment}. The complete block is available via {@link #completeBlock()}
 * when {@link #isComplete()} returns {@code true}.
 *
 * <h2>RFC 7540 §6.10 Invariants Enforced</h2>
 * <ul>
 *   <li>Once a HEADERS frame without END_HEADERS arrives the connection enters
 *       <em>continuation mode</em>: the next frame received MUST be a CONTINUATION
 *       on the same stream. Any other frame type or stream ID triggers
 *       {@link ContinuationViolationException}.</li>
 *   <li>CONTINUATION frames on stream 0 are rejected with
 *       {@link ContinuationViolationException}.</li>
 *   <li>A CONTINUATION frame received outside continuation mode (i.e. when
 *       END_HEADERS was set on the last HEADERS frame) triggers
 *       {@link ContinuationViolationException}.</li>
 * </ul>
 *
 * <h2>Memory</h2>
 * <p>Fragment bytes are appended into a {@link LoanedBuffer} obtained from the
 * injected {@link MemoryAllocator}. The buffer is closed and replaced when the
 * assembled block exceeds the current allocation. The caller MUST call
 * {@link #reset()} after consuming the complete block to release the buffer.
 *
 * <h2>Thread Safety</h2>
 * <p>Not thread-safe. One instance per HTTP/2 connection.
 *
 * @since 0.5.0
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7540#section-6.10">RFC 7540 §6.10</a>
 */
@SuppressWarnings("PMD.CyclomaticComplexity")
public final class Http2HeaderBlockAssembler {

    private static final int INITIAL_BUFFER_SIZE = 1_024;
    private static final int MAX_HEADER_BLOCK_SIZE = 65_536;

    private final MemoryAllocator allocator;

    private LoanedBuffer buffer;
    private int written;
    private boolean awaitingContinuation;
    private int continuationStreamId;

    /**
     * Creates an assembler backed by the given allocator.
     *
     * @param allocator memory allocator for HPACK fragment buffers
     */
    public Http2HeaderBlockAssembler(MemoryAllocator allocator) {
        this.allocator = allocator;
    }

    /**
     * Returns {@code true} when the assembler is in continuation mode — i.e. a
     * HEADERS frame without END_HEADERS has been received and the next inbound
     * frame MUST be a CONTINUATION on the same stream.
     *
     * @return {@code true} if a CONTINUATION frame is expected
     */
    public boolean isAwaitingContinuation() {
        return awaitingContinuation;
    }

    /**
     * Returns {@code true} when the assembled HPACK block is complete and ready
     * to be passed to {@link eu.exeris.kernel.http.hpack.HpackDecoder}.
     *
     * @return {@code true} if END_HEADERS has been seen for the current header block
     */
    public boolean isComplete() {
        return !awaitingContinuation && continuationStreamId != 0;
    }

    /**
     * Returns the stream ID of the HEADERS frame that started the current header block.
     *
     * @return stream identifier, or {@code 0} if no block is in progress
     */
    public int currentStreamId() {
        return continuationStreamId;
    }

    /**
     * Begins a new header block from the payload of a HEADERS frame.
     *
     * <p>If {@code endHeaders} is {@code true} the block is immediately complete;
     * no CONTINUATION frames are required. If {@code false} the assembler enters
     * continuation mode.
     *
     * @param header     the parsed HEADERS frame header
     * @param payload    segment containing the full connection read buffer
     * @param dataOffset byte offset to the start of the HPACK payload within {@code payload}
     * @param dataLength byte length of the HPACK fragment (after stripping pad/priority)
     * @throws ContinuationViolationException if called while already in continuation mode
     */
    public void beginHeaders(Http2FrameParser.FrameHeader header,
                             MemorySegment payload, long dataOffset, int dataLength) {
        if (awaitingContinuation) {
            throw new ContinuationViolationException(
                    "HTTP/2 PROTOCOL_ERROR: received HEADERS while awaiting CONTINUATION "
                            + "on stream " + continuationStreamId);
        }
        if (header.streamId() <= 0) {
            throw new ContinuationViolationException(
                    "HTTP/2 PROTOCOL_ERROR: HEADERS frame on stream 0");
        }
        reset();
        continuationStreamId = header.streamId();
        append(payload, dataOffset, dataLength);
        awaitingContinuation = !header.isEndHeaders();
    }

    /**
     * Appends a CONTINUATION frame payload to the current header block.
     *
     * <p>The CONTINUATION frame MUST arrive on the same stream as the originating
     * HEADERS frame. Any violation causes {@link ContinuationViolationException}.
     *
     * @param header     the parsed CONTINUATION frame header
     * @param payload    segment containing the full connection read buffer
     * @param dataOffset byte offset to the HPACK fragment
     * @param dataLength byte length of the HPACK fragment
     * @throws ContinuationViolationException on stream ID mismatch, wrong frame type,
     *                                        or unexpected CONTINUATION
     */
    public void appendContinuation(Http2FrameParser.FrameHeader header,
                                   MemorySegment payload, long dataOffset, int dataLength) {
        if (!awaitingContinuation) {
            throw new ContinuationViolationException(
                    "HTTP/2 PROTOCOL_ERROR: received CONTINUATION outside header block");
        }
        if (header.type() != Http2FrameType.CONTINUATION.code()) {
            throw new ContinuationViolationException(
                    "HTTP/2 PROTOCOL_ERROR: expected CONTINUATION, got type " + header.type()
                            + " on stream " + header.streamId());
        }
        if (header.streamId() != continuationStreamId) {
            throw new ContinuationViolationException(
                    "HTTP/2 PROTOCOL_ERROR: CONTINUATION on stream " + header.streamId()
                            + " but expected stream " + continuationStreamId);
        }
        append(payload, dataOffset, dataLength);
        if (header.isEndHeaders()) {
            awaitingContinuation = false;
        }
    }

    /**
     * Validates that the given inbound frame is legal in continuation mode.
     *
     * <p>Must be called for every frame received while {@link #isAwaitingContinuation()}
     * is {@code true}. If the frame is not a CONTINUATION on the correct stream this
     * method throws {@link ContinuationViolationException} — the caller MUST send
     * GOAWAY(PROTOCOL_ERROR) and close the connection.
     *
     * @param header parsed inbound frame header
     * @throws ContinuationViolationException if the frame violates §6.10
     */
    public void validateContinuationMode(Http2FrameParser.FrameHeader header) {
        if (!awaitingContinuation) {
            return;
        }
        if (header.type() != Http2FrameType.CONTINUATION.code()) {
            throw new ContinuationViolationException(
                    "HTTP/2 PROTOCOL_ERROR: expected CONTINUATION on stream "
                            + continuationStreamId + ", got type " + header.type());
        }
        if (header.streamId() != continuationStreamId) {
            throw new ContinuationViolationException(
                    "HTTP/2 PROTOCOL_ERROR: expected CONTINUATION on stream "
                            + continuationStreamId + ", got stream " + header.streamId());
        }
    }

    /**
     * Returns a slice of the assembled HPACK header block.
     *
     * <p>Valid only when {@link #isComplete()} is {@code true}. The returned
     * segment is a view into the internal buffer — the caller MUST consume it
     * before calling {@link #reset()}.
     *
     * @return assembled HPACK block segment
     * @throws IllegalStateException if the block is not yet complete
     */
    public MemorySegment completeBlock() {
        if (awaitingContinuation || continuationStreamId == 0) {
            throw new IllegalStateException(
                    "HPACK header block is not yet complete");
        }
        return buffer.segment().asSlice(0, written);
    }

    /**
     * Releases the internal buffer and resets all assembler state.
     *
     * <p>Must be called after the complete block has been consumed by
     * {@link eu.exeris.kernel.http.hpack.HpackDecoder}.
     */
    @SuppressWarnings("PMD.NullAssignment")
    public void reset() {
        if (buffer != null) {
            buffer.close();
            buffer = null;
        }
        written = 0;
        awaitingContinuation = false;
        continuationStreamId = 0;
    }

    // =========================================================================
    // Internal
    // =========================================================================

    private void append(MemorySegment src, long srcOffset, int length) {
        if (length < 0) {
            throw new ContinuationViolationException(
                    "HTTP/2 PROTOCOL_ERROR: negative HPACK fragment length: " + length);
        }
        if (srcOffset < 0 || srcOffset + length > src.byteSize()) {
            throw new ContinuationViolationException(
                    "HTTP/2 PROTOCOL_ERROR: HPACK fragment out of bounds: offset=" + srcOffset
                            + ", length=" + length + ", segmentSize=" + src.byteSize());
        }
        long required = (long) written + length;
        if (required > MAX_HEADER_BLOCK_SIZE) {
            throw new ContinuationViolationException(
                    "HTTP/2 PROTOCOL_ERROR: header block exceeds limit ("
                            + required + " > " + MAX_HEADER_BLOCK_SIZE + ")");
        }
        ensureCapacity((int) required);
        MemorySegment.copy(src, ValueLayout.JAVA_BYTE, srcOffset,
                buffer.segment(), ValueLayout.JAVA_BYTE, written, length);
        written = (int) required;
    }

    @SuppressWarnings("PMD.CloseResource")
    private void ensureCapacity(int required) {
        if (buffer != null && buffer.segment().byteSize() >= required) {
            return;
        }
        int newCapacity = Math.max(INITIAL_BUFFER_SIZE, Integer.highestOneBit(required - 1) << 1);
        LoanedBuffer newBuffer = allocator.allocateNetwork(newCapacity);
        if (buffer != null) {
            try (LoanedBuffer old = buffer) {
                MemorySegment.copy(old.segment(), ValueLayout.JAVA_BYTE, 0,
                        newBuffer.segment(), ValueLayout.JAVA_BYTE, 0, written);
            }
        }
        buffer = newBuffer;
    }

    /**
     * Unchecked exception for RFC 7540 §6.10 CONTINUATION frame violations.
     *
     * <p>The caller MUST respond with GOAWAY(PROTOCOL_ERROR) and close the connection.
     *
     * @since 0.5.0
     */
    public static final class ContinuationViolationException extends RuntimeException {
        public ContinuationViolationException(String message) {
            super(message);
        }
    }
}
