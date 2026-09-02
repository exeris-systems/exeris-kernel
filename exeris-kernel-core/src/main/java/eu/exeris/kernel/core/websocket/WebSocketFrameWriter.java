/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.websocket;

import eu.exeris.kernel.spi.websocket.WebSocketCloseCode;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/**
 * Core: writes RFC 6455 frames into a {@link MemorySegment}.
 *
 * <p>Server-to-client only, and therefore never masked — RFC 6455 §5.1 forbids a server masking, so
 * there is no flag to get wrong. A client-side writer would need one and is not this class.
 */
public final class WebSocketFrameWriter {

    private static final int FIN_BIT = 0x80;
    private static final int LENGTH_16_BIT_MARKER = 126;
    private static final int LENGTH_64_BIT_MARKER = 127;
    private static final int MAX_SHORT_LENGTH = 125;
    private static final int MAX_16_BIT_LENGTH = 0xFFFF;

    /** RFC 6455 §5.5.1: a close frame's payload is a two-byte code plus an optional reason. */
    private static final int CLOSE_CODE_BYTES = 2;
    private static final int MIN_HEADER_BYTES = 2;
    private static final int EXTENDED_16_BYTES = 2;
    private static final int EXTENDED_64_BYTES = 8;

    private WebSocketFrameWriter() {
    }

    /**
     * Bytes a frame with {@code payloadLength} will occupy, so a caller can size a buffer before
     * committing to write into it.
     *
     * @param payloadLength the payload length
     * @return the total frame size in bytes
     */
    public static int frameSize(int payloadLength) {
        int headerSize = MIN_HEADER_BYTES;
        if (payloadLength > MAX_16_BIT_LENGTH) {
            headerSize += EXTENDED_64_BYTES;
        } else if (payloadLength > MAX_SHORT_LENGTH) {
            headerSize += EXTENDED_16_BYTES;
        }
        return headerSize + payloadLength;
    }

    /**
     * Writes one unfragmented frame.
     *
     * @param seg     destination segment
     * @param offset  where to start writing
     * @param opcode  the frame opcode
     * @param payload the payload bytes
     * @return the offset just past the frame written
     */
    public static long write(MemorySegment seg, long offset, WebSocketOpcode opcode,
                             byte[] payload) {
        long pos = offset;
        seg.set(ValueLayout.JAVA_BYTE, pos, (byte) (FIN_BIT | opcode.code()));
        pos++;

        int length = payload.length;
        if (length <= MAX_SHORT_LENGTH) {
            seg.set(ValueLayout.JAVA_BYTE, pos, (byte) length);
            pos++;
        } else if (length <= MAX_16_BIT_LENGTH) {
            seg.set(ValueLayout.JAVA_BYTE, pos, (byte) LENGTH_16_BIT_MARKER);
            pos = writeUnsigned(seg, pos + 1, length, EXTENDED_16_BYTES);
        } else {
            seg.set(ValueLayout.JAVA_BYTE, pos, (byte) LENGTH_64_BIT_MARKER);
            pos = writeUnsigned(seg, pos + 1, length, EXTENDED_64_BYTES);
        }
        if (length > 0) {
            MemorySegment.copy(payload, 0, seg, ValueLayout.JAVA_BYTE, pos, length);
        }
        return pos + length;
    }

    /**
     * Writes a text frame.
     *
     * @param seg     destination segment
     * @param offset  where to start writing
     * @param message the message; encoded UTF-8
     * @return the offset just past the frame written
     */
    public static long writeText(MemorySegment seg, long offset, String message) {
        return write(seg, offset, WebSocketOpcode.TEXT,
                message.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Writes a close frame.
     *
     * <p>The reason is truncated on a UTF-8 <em>character</em> boundary rather than a byte one: a
     * close frame's payload is capped at 125 bytes, and cutting mid-sequence would make the frame
     * itself invalid UTF-8 — a protocol violation committed while reporting one.
     *
     * @param seg    destination segment
     * @param offset where to start writing
     * @param code   the close code; must be sendable
     * @param reason a short reason, possibly empty
     * @return the offset just past the frame written
     */
    public static long writeClose(MemorySegment seg, long offset, WebSocketCloseCode code,
                                  String reason) {
        if (!code.sendable()) {
            throw new IllegalArgumentException(
                    "close code " + code.code() + " must never be sent on the wire");
        }
        byte[] reasonBytes = truncateToUtf8Limit(reason, MAX_SHORT_LENGTH - CLOSE_CODE_BYTES);
        byte[] payload = new byte[CLOSE_CODE_BYTES + reasonBytes.length];
        payload[0] = (byte) (code.code() >>> 8);
        payload[1] = (byte) code.code();
        System.arraycopy(reasonBytes, 0, payload, CLOSE_CODE_BYTES, reasonBytes.length);
        return write(seg, offset, WebSocketOpcode.CLOSE, payload);
    }

    private static byte[] truncateToUtf8Limit(String reason, int maxBytes) {
        byte[] encoded = reason.getBytes(StandardCharsets.UTF_8);
        if (encoded.length <= maxBytes) {
            return encoded;
        }
        // Walk back off any continuation byte (10xxxxxx) so the cut lands between characters.
        int end = maxBytes;
        while (end > 0 && (encoded[end] & 0xC0) == 0x80) {
            end--;
        }
        byte[] truncated = new byte[end];
        System.arraycopy(encoded, 0, truncated, 0, end);
        return truncated;
    }

    private static long writeUnsigned(MemorySegment seg, long offset, long value, int bytes) {
        for (int i = 0; i < bytes; i++) {
            int shift = (bytes - 1 - i) << 3;
            seg.set(ValueLayout.JAVA_BYTE, offset + i, (byte) (value >>> shift));
        }
        return offset + bytes;
    }
}
