/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.http.http2;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * RFC 7540 §4 — HTTP/2 Frame Encoder (structured frame → wire).
 *
 * <h2>Zero-Copy</h2>
 * <p>Writes the 9-byte frame header directly into a {@link MemorySegment}.
 * The caller is responsible for writing the frame payload after the header.
 *
 * @since 0.5.0
 */
@SuppressWarnings("PMD.CyclomaticComplexity")
public final class Http2FrameEncoder {

    private Http2FrameEncoder() {
    }

    /**
     * Writes a 9-byte frame header into the given segment at the specified offset.
     *
     * @param seg      destination segment
     * @param offset   byte offset to start writing
     * @param length   payload length (24-bit)
     * @param type     frame type code
     * @param flags    frame flags byte
     * @param streamId stream identifier (31-bit)
     */
    public static void writeHeader(MemorySegment seg, long offset,
                                   int length, int type, int flags, int streamId) {
        if (length < 0 || length > 0x00FF_FFFF) {
            throw new FrameEncodingException(
                    "Frame payload length must be in [0, 0x00FFFFFF], got: " + length);
        }
        if (streamId < 0) {
            throw new FrameEncodingException(
                    "Stream ID must be in [0, 0x7FFFFFFF], got: " + streamId);
        }
        seg.set(ValueLayout.JAVA_BYTE, offset, (byte) ((length >> 16) & 0xFF));
        seg.set(ValueLayout.JAVA_BYTE, offset + 1, (byte) ((length >> 8) & 0xFF));
        seg.set(ValueLayout.JAVA_BYTE, offset + 2, (byte) (length & 0xFF));
        seg.set(ValueLayout.JAVA_BYTE, offset + 3, (byte) type);
        seg.set(ValueLayout.JAVA_BYTE, offset + 4, (byte) flags);
        seg.set(ValueLayout.JAVA_BYTE, offset + 5, (byte) ((streamId >> 24) & 0x7F));
        seg.set(ValueLayout.JAVA_BYTE, offset + 6, (byte) ((streamId >> 16) & 0xFF));
        seg.set(ValueLayout.JAVA_BYTE, offset + 7, (byte) ((streamId >> 8) & 0xFF));
        seg.set(ValueLayout.JAVA_BYTE, offset + 8, (byte) (streamId & 0xFF));
    }

    /**
     * Writes a SETTINGS frame (type 0x04) with the given key-value pairs.
     *
     * @param seg      destination segment
     * @param offset   byte offset
     * @param streamId stream identifier (must be 0 for SETTINGS)
     * @param ack      {@code true} for ACK frame (empty payload)
     * @param params   settings parameters as {@code [id, value, id, value, ...]}
     * @return number of bytes written (header + payload)
     */
    public static long writeSettings(MemorySegment seg, long offset, int streamId,
                                     boolean ack, int... params) {
        validateSettingsArgs(streamId, ack, params);
        int flags = ack ? 0x01 : 0x00;
        int payloadLen = ack ? 0 : params.length / 2 * 6;
        writeHeader(seg, offset, payloadLen, Http2FrameType.SETTINGS.code(), flags, streamId);
        long pos = offset + Http2FrameParser.FRAME_HEADER_SIZE;
        if (!ack) {
            int numPairs = params.length / 2;
            for (int pair = 0; pair < numPairs; pair++) {
                int base = pair * 2;
                seg.set(ValueLayout.JAVA_BYTE, pos, (byte) ((params[base] >> 8) & 0xFF));
                seg.set(ValueLayout.JAVA_BYTE, pos + 1, (byte) (params[base] & 0xFF));
                int val = params[base + 1];
                seg.set(ValueLayout.JAVA_BYTE, pos + 2, (byte) ((val >> 24) & 0xFF));
                seg.set(ValueLayout.JAVA_BYTE, pos + 3, (byte) ((val >> 16) & 0xFF));
                seg.set(ValueLayout.JAVA_BYTE, pos + 4, (byte) ((val >> 8) & 0xFF));
                seg.set(ValueLayout.JAVA_BYTE, pos + 5, (byte) (val & 0xFF));
                pos += 6;
            }
        }
        return pos - offset;
    }

    private static void validateSettingsArgs(int streamId, boolean ack, int... params) {
        if (streamId != 0) {
            throw new FrameEncodingException("SETTINGS frame must use stream 0");
        }
        if (ack && params.length != 0) {
            throw new FrameEncodingException(
                    "SETTINGS ACK frame must carry no parameters; got: " + params.length);
        }
        if (!ack && params.length % 2 != 0) {
            throw new FrameEncodingException(
                    "SETTINGS params must be pairs of [id, value]; odd length: " + params.length);
        }
    }

    /**
     * Writes a WINDOW_UPDATE frame (type 0x08).
     *
     * @param seg            destination segment
     * @param offset         byte offset
     * @param streamId       stream identifier (0 for connection-level)
     * @param windowIncrement window size increment (31-bit, must be &gt; 0)
     * @return number of bytes written (9 + 4 = 13)
     */
    public static long writeWindowUpdate(MemorySegment seg, long offset,
                                         int streamId, int windowIncrement) {
        if (windowIncrement <= 0 || windowIncrement > 0x7FFFFFFF) {
            throw new FrameEncodingException(
                    "WINDOW_UPDATE increment must be in [1, 2^31-1], got: " + windowIncrement);
        }
        writeHeader(seg, offset, 4, Http2FrameType.WINDOW_UPDATE.code(), 0, streamId);
        long pos = offset + Http2FrameParser.FRAME_HEADER_SIZE;
        seg.set(ValueLayout.JAVA_BYTE, pos, (byte) ((windowIncrement >> 24) & 0x7F));
        seg.set(ValueLayout.JAVA_BYTE, pos + 1, (byte) ((windowIncrement >> 16) & 0xFF));
        seg.set(ValueLayout.JAVA_BYTE, pos + 2, (byte) ((windowIncrement >> 8) & 0xFF));
        seg.set(ValueLayout.JAVA_BYTE, pos + 3, (byte) (windowIncrement & 0xFF));
        return 13;
    }

    /**
     * Writes a RST_STREAM frame (type 0x03) — RFC 7540 §6.4.
     *
     * <p>RST_STREAM terminates a stream immediately. The caller MUST NOT send or
     * process further frames on the given stream after writing this frame.
     *
     * @param seg       destination segment (must have at least 13 bytes from {@code offset})
     * @param offset    byte offset
     * @param streamId  stream identifier (must be &gt; 0)
     * @param errorCode 32-bit error code ({@link Http2ErrorCode#code()})
     * @return number of bytes written (9 + 4 = 13)
     */
    public static long writeRstStream(MemorySegment seg, long offset,
                                      int streamId, int errorCode) {
        if (streamId <= 0) {
            throw new FrameEncodingException(
                    "RST_STREAM stream ID must be > 0, got: " + streamId);
        }
        writeHeader(seg, offset, 4, Http2FrameType.RST_STREAM.code(), 0, streamId);
        long pos = offset + Http2FrameParser.FRAME_HEADER_SIZE;
        seg.set(ValueLayout.JAVA_BYTE, pos,     (byte) ((errorCode >> 24) & 0xFF));
        seg.set(ValueLayout.JAVA_BYTE, pos + 1, (byte) ((errorCode >> 16) & 0xFF));
        seg.set(ValueLayout.JAVA_BYTE, pos + 2, (byte) ((errorCode >> 8)  & 0xFF));
        seg.set(ValueLayout.JAVA_BYTE, pos + 3, (byte) (errorCode         & 0xFF));
        return 13;
    }

    /**
     * Writes a GOAWAY frame (type 0x07) with no debug data — RFC 7540 §6.8.
     *
     * <p>GOAWAY initiates graceful shutdown of the connection. After writing this frame
     * the sender MUST NOT open new streams. The receiver MUST NOT rely on any streams
     * beyond {@code lastStreamId} having been processed.
     *
     * @param seg          destination segment (must have at least 17 bytes from {@code offset})
     * @param offset       byte offset
     * @param lastStreamId highest-numbered stream the sender will process (31-bit)
     * @param errorCode    32-bit error code ({@link Http2ErrorCode#code()})
     * @return number of bytes written (9 + 8 = 17)
     */
    public static long writeGoAway(MemorySegment seg, long offset,
                                   int lastStreamId, int errorCode) {
        if (lastStreamId < 0) {
            throw new FrameEncodingException(
                    "GOAWAY lastStreamId must be in [0, 0x7FFFFFFF], got: " + lastStreamId);
        }
        writeHeader(seg, offset, 8, Http2FrameType.GOAWAY.code(), 0, 0);
        long pos = offset + Http2FrameParser.FRAME_HEADER_SIZE;
        seg.set(ValueLayout.JAVA_BYTE, pos,     (byte) ((lastStreamId >> 24) & 0x7F));
        seg.set(ValueLayout.JAVA_BYTE, pos + 1, (byte) ((lastStreamId >> 16) & 0xFF));
        seg.set(ValueLayout.JAVA_BYTE, pos + 2, (byte) ((lastStreamId >> 8)  & 0xFF));
        seg.set(ValueLayout.JAVA_BYTE, pos + 3, (byte) (lastStreamId         & 0xFF));
        seg.set(ValueLayout.JAVA_BYTE, pos + 4, (byte) ((errorCode >> 24)    & 0xFF));
        seg.set(ValueLayout.JAVA_BYTE, pos + 5, (byte) ((errorCode >> 16)    & 0xFF));
        seg.set(ValueLayout.JAVA_BYTE, pos + 6, (byte) ((errorCode >> 8)     & 0xFF));
        seg.set(ValueLayout.JAVA_BYTE, pos + 7, (byte) (errorCode            & 0xFF));
        return 17;
    }

    /**
     * Writes a CONTINUATION frame header (type 0x09) — RFC 7540 §6.10.
     *
     * <p>CONTINUATION frames carry HPACK fragments that complete a header block
     * started by a HEADERS (or PUSH_PROMISE) frame that did not set END_HEADERS.
     * The caller writes the HPACK payload after the 9-byte header.
     *
     * <p>The last CONTINUATION frame in a sequence MUST set {@code endHeaders = true},
     * which sets the END_HEADERS flag (0x04).
     *
     * @param seg        destination segment
     * @param offset     byte offset
     * @param streamId   stream identifier — MUST match the originating HEADERS stream
     * @param length     HPACK fragment payload length
     * @param endHeaders {@code true} to set END_HEADERS flag, completing the block
     * @return number of bytes written (always 9 — header only; caller writes payload)
     */
    public static long writeContinuation(MemorySegment seg, long offset,
                                         int streamId, int length, boolean endHeaders) {
        if (streamId <= 0) {
            throw new FrameEncodingException(
                    "CONTINUATION frames are invalid on stream 0; streamId must be > 0, got: " + streamId);
        }
        int flags = endHeaders ? 0x04 : 0x00;
        writeHeader(seg, offset, length, Http2FrameType.CONTINUATION.code(), flags, streamId);
        return Http2FrameParser.FRAME_HEADER_SIZE;
    }

    /**
     * Unchecked exception for HTTP/2 Frame encoding violations (RFC 7540 §4 / §6).
     *
     * @since 0.5.0
     */
    public static class FrameEncodingException extends ExerisKernelException {

        private static final String ERROR_CODE = KernelErrorCodes.EX_HTTP_4006;
        private static final String MESSAGE_TEMPLATE = "HTTP/2 frame encoding violation";

        public FrameEncodingException(String detail) {
            super(ERROR_CODE, MESSAGE_TEMPLATE, detail);
        }
    }
}
