/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.http2;

import java.lang.foreign.MemorySegment;

/**
 * RFC 7540 — HTTP/2 Frame Codec combining parsing and encoding.
 *
 * <h2>Responsibility</h2>
 * <p>Facade coordinating {@link Http2FrameParser} (wire → header) and
 * {@link Http2FrameEncoder} (header → wire) with connection-level
 * validation (frame size limits, stream ID rules).
 *
 * <p><b>Allocation:</b> {@link #parseAndValidate} allocates one
 * {@link Http2FrameParser.FrameHeader} record per call, via the delegated parse; the
 * {@code write*} methods delegate to {@link Http2FrameEncoder} and allocate nothing.
 * <p><b>Thread confinement:</b> owner thread — {@link #maxFrameSize} is read and mutated
 * without synchronization, so one instance must be owned by a single HTTP/2 connection
 * and driven from one thread at a time.
 * <p><b>Ownership:</b> the codec holds no buffers or native resources; every segment
 * passed to it remains owned by the caller.
 *
 * @since 0.5
 */
public final class Http2FrameCodec {

    /** RFC 7540 §4.2 — default maximum frame size. */
    public static final int DEFAULT_MAX_FRAME_SIZE = 16_384;

    /** RFC 7540 §4.2 — minimum allowed maximum frame size. */
    public static final int MIN_MAX_FRAME_SIZE = 16_384;

    /** RFC 7540 §4.2 — maximum allowed maximum frame size. */
    public static final int MAX_MAX_FRAME_SIZE = 16_777_215;

    private int maxFrameSize;

    /**
     * Creates a codec with the default maximum frame size.
     */
    public Http2FrameCodec() {
        this(DEFAULT_MAX_FRAME_SIZE);
    }

    /**
     * Creates a codec with the given maximum frame size.
     *
     * @param maxFrameSize maximum frame payload size (16384–16777215)
     * @throws Http2FrameEncoder.FrameEncodingException if {@code maxFrameSize} is outside the
     *         RFC 7540 §4.2 range ({@code EX-HTTP-4006})
     */
    public Http2FrameCodec(int maxFrameSize) {
        if (maxFrameSize < MIN_MAX_FRAME_SIZE || maxFrameSize > MAX_MAX_FRAME_SIZE) {
            throw new Http2FrameEncoder.FrameEncodingException(
                    "maxFrameSize must be in [{0}, {1}]; got {2}",
                    MIN_MAX_FRAME_SIZE, MAX_MAX_FRAME_SIZE, maxFrameSize);
        }
        this.maxFrameSize = maxFrameSize;
    }

    /**
     * Updates the maximum frame size (after SETTINGS negotiation).
     *
     * @param newMaxFrameSize new maximum (16384–16777215)
     * @throws Http2FrameEncoder.FrameEncodingException if {@code newMaxFrameSize} is outside the
     *         RFC 7540 §4.2 range ({@code EX-HTTP-4006})
     */
    public void setMaxFrameSize(int newMaxFrameSize) {
        if (newMaxFrameSize < MIN_MAX_FRAME_SIZE || newMaxFrameSize > MAX_MAX_FRAME_SIZE) {
            throw new Http2FrameEncoder.FrameEncodingException(
                    "maxFrameSize must be in [{0}, {1}]; got {2}",
                    MIN_MAX_FRAME_SIZE, MAX_MAX_FRAME_SIZE, newMaxFrameSize);
        }
        this.maxFrameSize = newMaxFrameSize;
    }

    /**
     * Returns the maximum frame payload size this codec currently enforces.
     *
     * @return the negotiated maximum frame size, in bytes
     */
    public int maxFrameSize() {
        return maxFrameSize;
    }

    /**
     * Parses a frame header and validates the payload length against the current
     * maximum frame size.
     *
     * @param seg    source segment
     * @param offset byte offset
     * @return parsed frame header
     * @throws Http2FrameEncoder.FrameEncodingException if the frame length exceeds the negotiated
     *         maximum ({@code EX-HTTP-4006})
     */
    public Http2FrameParser.FrameHeader parseAndValidate(MemorySegment seg, long offset) {
        Http2FrameParser.FrameHeader header = Http2FrameParser.parseHeaderBigEndian(seg, offset);
        if (header.length() > maxFrameSize) {
            throw new Http2FrameEncoder.FrameEncodingException(
                    "FRAME_SIZE_ERROR: payload length {0} exceeds negotiated maximum {1}",
                    header.length(), maxFrameSize);
        }
        return header;
    }

    /**
     * Writes a DATA frame header. The caller writes the payload.
     *
     * @param seg       destination segment
     * @param offset    byte offset
     * @param streamId  stream identifier (must be &gt; 0)
     * @param length    payload length
     * @param endStream {@code true} to set END_STREAM flag
     * @throws Http2FrameEncoder.FrameEncodingException if {@code streamId} is not greater than 0
     *         ({@code EX-HTTP-4006})
     */
    public static void writeDataHeader(MemorySegment seg, long offset,
                                       int streamId, int length, boolean endStream) {
        if (streamId <= 0) {
            throw new Http2FrameEncoder.FrameEncodingException(
                    "DATA frames are invalid on stream 0; streamId must be > 0, got: {0}", streamId);
        }
        int flags = endStream ? 0x01 : 0x00;
        Http2FrameEncoder.writeHeader(seg, offset, length,
                Http2FrameType.DATA.code(), flags, streamId);
    }

    /**
     * Writes a HEADERS frame header. The caller writes the HPACK payload.
     *
     * @param seg        destination segment
     * @param offset     byte offset
     * @param streamId   stream identifier
     * @param length     HPACK payload length
     * @param endStream  {@code true} to set END_STREAM flag
     * @param endHeaders {@code true} to set END_HEADERS flag
     * @throws Http2FrameEncoder.FrameEncodingException if {@code streamId} is not greater than 0
     *         ({@code EX-HTTP-4006})
     */
    public static void writeHeadersHeader(MemorySegment seg, long offset,
                                          int streamId, int length,
                                          boolean endStream, boolean endHeaders) {
        if (streamId <= 0) {
            throw new Http2FrameEncoder.FrameEncodingException(
                    "HEADERS frames are invalid on stream 0; streamId must be > 0, got: {0}", streamId);
        }
        int flags = 0;
        if (endStream) {
            flags |= 0x01;
        }
        if (endHeaders) {
            flags |= 0x04;
        }
        Http2FrameEncoder.writeHeader(seg, offset, length,
                Http2FrameType.HEADERS.code(), flags, streamId);
    }

}
