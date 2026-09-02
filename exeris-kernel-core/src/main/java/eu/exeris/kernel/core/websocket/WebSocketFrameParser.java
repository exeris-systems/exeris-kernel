/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.websocket;

import eu.exeris.kernel.spi.websocket.WebSocketCloseCode;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Core: reads RFC 6455 frame headers off a {@link MemorySegment}.
 *
 * <p>Driver-agnostic by construction — it sees bytes and offsets, never a socket. The payload is
 * left in the segment and described by offset and length, so a frame costs one
 * {@link WebSocketFrameHeader} and no copy.
 *
 * <p>Incomplete input is a {@code null} return, not an exception: a reader that has half a header
 * has read too little, which is the normal state of a stream and not a fault. Only a genuine
 * protocol violation raises {@link WebSocketProtocolException}, and it carries the close code the
 * violation maps to so the caller does not re-derive it.
 */
// Same suppression Http1RequestParser carries, for the same reason: a wire-format parser's branching
// is the format's, not the code's. Three payload-length encodings, masked and unmasked, and the
// control-frame restrictions are irreducible — the parse method was already split into named steps
// (validateControlFrame, readPayloadLength, extendedLengthBytes), and splitting further would create
// methods that exist to satisfy a counter rather than to be read.
@SuppressWarnings("PMD.CyclomaticComplexity")
public final class WebSocketFrameParser {

    private static final int FIN_BIT = 0x80;
    private static final int RSV_MASK = 0x70;
    private static final int OPCODE_MASK = 0x0F;
    private static final int MASK_BIT = 0x80;
    private static final int LENGTH_MASK = 0x7F;

    private static final int LENGTH_16_BIT_MARKER = 126;
    private static final int LENGTH_64_BIT_MARKER = 127;

    /** RFC 6455 §5.5: a control frame's payload must fit in one frame and stay small. */
    private static final int MAX_CONTROL_PAYLOAD = 125;

    private static final int MASK_KEY_BYTES = 4;
    private static final int MIN_HEADER_BYTES = 2;
    private static final int EXTENDED_16_BYTES = 2;
    private static final int EXTENDED_64_BYTES = 8;

    /** Returned by the length reader when the bytes present do not yet carry a whole length. */
    private static final long NEED_MORE = -1L;

    private WebSocketFrameParser() {
    }

    /**
     * Parses one frame header.
     *
     * @param seg    segment holding received bytes
     * @param offset where this frame starts
     * @param limit  one past the last readable byte
     * @return the header, or {@code null} when the bytes present do not yet contain a whole one
     * @throws WebSocketProtocolException on a violation the connection must close for
     */
    public static WebSocketFrameHeader parse(MemorySegment seg, long offset, long limit) {
        if (limit - offset < MIN_HEADER_BYTES) {
            return null;
        }
        int first = Byte.toUnsignedInt(seg.get(ValueLayout.JAVA_BYTE, offset));
        int second = Byte.toUnsignedInt(seg.get(ValueLayout.JAVA_BYTE, offset + 1));

        // RSV1..3 are zero unless an extension negotiated at the handshake defines them. None is
        // negotiated here, so a set bit means the peer is speaking a protocol we did not agree to —
        // a violation rather than something to ignore, since ignoring it would misread the payload
        // of whatever extension set it.
        if ((first & RSV_MASK) != 0) {
            throw new WebSocketProtocolException(WebSocketCloseCode.PROTOCOL_ERROR,
                    "reserved bit set with no extension negotiated");
        }
        WebSocketOpcode opcode = WebSocketOpcode.fromCode(first & OPCODE_MASK);
        if (opcode == null) {
            throw new WebSocketProtocolException(WebSocketCloseCode.PROTOCOL_ERROR,
                    "reserved opcode");
        }

        boolean fin = (first & FIN_BIT) != 0;
        boolean masked = (second & MASK_BIT) != 0;
        int lengthMarker = second & LENGTH_MASK;
        validateControlFrame(opcode, fin, lengthMarker);

        long cursor = offset + MIN_HEADER_BYTES;
        long payloadLength = readPayloadLength(seg, cursor, limit, lengthMarker);
        if (payloadLength == NEED_MORE) {
            return null;
        }
        cursor += extendedLengthBytes(lengthMarker);

        int maskingKey = 0;
        if (masked) {
            if (limit - cursor < MASK_KEY_BYTES) {
                return null;
            }
            maskingKey = (int) readUnsigned(seg, cursor, MASK_KEY_BYTES);
            cursor += MASK_KEY_BYTES;
        }
        if (limit - cursor < payloadLength) {
            return null;
        }
        return new WebSocketFrameHeader(opcode, fin, masked, maskingKey, cursor, payloadLength);
    }

    /**
     * RFC 6455 §5.5: a control frame is never fragmented and never exceeds 125 bytes. Checked before
     * the extended-length forms are read, because a control frame may not use them.
     */
    private static void validateControlFrame(WebSocketOpcode opcode, boolean fin, int lengthMarker) {
        if (!opcode.isControl()) {
            return;
        }
        if (!fin) {
            throw new WebSocketProtocolException(WebSocketCloseCode.PROTOCOL_ERROR,
                    "fragmented control frame");
        }
        if (lengthMarker > MAX_CONTROL_PAYLOAD) {
            throw new WebSocketProtocolException(WebSocketCloseCode.PROTOCOL_ERROR,
                    "control frame payload exceeds 125 bytes");
        }
    }

    /** @return the payload length, or {@link #NEED_MORE} when its bytes have not all arrived */
    private static long readPayloadLength(MemorySegment seg, long cursor, long limit,
                                          int lengthMarker) {
        if (lengthMarker < LENGTH_16_BIT_MARKER) {
            return lengthMarker;
        }
        int extended = extendedLengthBytes(lengthMarker);
        if (limit - cursor < extended) {
            return NEED_MORE;
        }
        long length = readUnsigned(seg, cursor, extended);
        // The 64-bit form carries a signed long on the wire and RFC 6455 §5.2 forbids the high bit.
        // Refused here rather than downstream, because a negative length would make every bounds
        // check that follows meaningless.
        if (length < 0) {
            throw new WebSocketProtocolException(WebSocketCloseCode.PROTOCOL_ERROR,
                    "payload length has its most significant bit set");
        }
        return length;
    }

    private static int extendedLengthBytes(int lengthMarker) {
        if (lengthMarker == LENGTH_64_BIT_MARKER) {
            return EXTENDED_64_BYTES;
        }
        return lengthMarker == LENGTH_16_BIT_MARKER ? EXTENDED_16_BYTES : 0;
    }

    /**
     * Copies a frame's payload out, unmasking as it goes.
     *
     * <p>Unmasking is XOR against the four-byte key cycling by position (RFC 6455 §5.3), applied
     * during the copy rather than in a second pass over the destination — one traversal, and the
     * masked bytes are never written anywhere.
     *
     * @param seg    segment holding the frame
     * @param header the parsed header
     * @param dest   destination array
     * @param destOffset where in {@code dest} to start writing
     */
    public static void copyPayload(MemorySegment seg, WebSocketFrameHeader header,
                                   byte[] dest, int destOffset) {
        long length = header.payloadLength();
        long from = header.payloadOffset();
        if (!header.masked()) {
            MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, from, dest, destOffset,
                    Math.toIntExact(length));
            return;
        }
        int key = header.maskingKey();
        for (long i = 0; i < length; i++) {
            byte raw = seg.get(ValueLayout.JAVA_BYTE, from + i);
            int shift = 24 - (int) ((i & 3) << 3);
            dest[destOffset + (int) i] = (byte) (raw ^ (byte) (key >>> shift));
        }
    }

    private static long readUnsigned(MemorySegment seg, long offset, int bytes) {
        long value = 0;
        for (int i = 0; i < bytes; i++) {
            value = (value << 8) | Byte.toUnsignedLong(seg.get(ValueLayout.JAVA_BYTE, offset + i));
        }
        return value;
    }
}
