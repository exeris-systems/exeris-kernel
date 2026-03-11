/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.http.http2;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * RFC 7540 §4 — HTTP/2 Frame Parser (wire → structured frame header).
 *
 * <h2>Frame Layout (9-byte header)</h2>
 * <pre>
 * +-----------------------------------------------+
 * |                 Length (24)                    |
 * +---------------+---------------+---------------+
 * |   Type (8)    |   Flags (8)   |
 * +-+-------------+---------------+---------------+
 * |R|                 Stream Identifier (31)       |
 * +-+---------------------------------------------+
 * |                 Frame Payload (Length bytes)    |
 * +-----------------------------------------------+
 * </pre>
 *
 * <h2>Zero-Copy</h2>
 * <p>This parser reads directly from a {@link MemorySegment} — no intermediate
 * byte arrays, no heap allocation per frame.
 *
 * @since 0.5.0
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7540#section-4">RFC 7540 §4</a>
 */
public final class Http2FrameParser {

    /** HTTP/2 frame header size in bytes. */
    public static final int FRAME_HEADER_SIZE = 9;

    /**
     * Parsed frame header — Valhalla-ready value type.
     *
     * @param length   payload length (24-bit, max 16384 default, negotiable to 16777215)
     * @param type     frame type code
     * @param flags    frame flags byte
     * @param streamId stream identifier (31-bit, R bit masked)
     */
    public record FrameHeader(int length, int type, int flags, int streamId) {

        /**
         * Resolves the {@link Http2FrameType} enum for this frame.
         *
         * @return frame type enum, or {@code null} for extension types
         */
        public Http2FrameType frameType() {
            return Http2FrameType.fromCode(type);
        }

        /** Returns {@code true} if the END_STREAM flag (0x01) is set. */
        public boolean isEndStream() {
            return (flags & 0x01) != 0;
        }

        /** Returns {@code true} if the END_HEADERS flag (0x04) is set. */
        public boolean isEndHeaders() {
            return (flags & 0x04) != 0;
        }

        /** Returns {@code true} if the PADDED flag (0x08) is set. */
        public boolean isPadded() {
            return (flags & 0x08) != 0;
        }

        /** Returns {@code true} if the PRIORITY flag (0x20) is set. */
        public boolean isPriority() {
            return (flags & 0x20) != 0;
        }

        /** Returns {@code true} if the ACK flag (0x01) is set (SETTINGS/PING). */
        public boolean isAck() {
            return (flags & 0x01) != 0;
        }
    }

    private Http2FrameParser() {
    }

    /**
     * Parses a 9-byte frame header from the given segment.
     *
     * <p>All multi-byte fields are read as big-endian per RFC 7540 §4.1.
     *
     * @param seg    segment containing at least 9 bytes from {@code offset}
     * @param offset byte offset to the start of the frame header
     * @return parsed frame header
     * @throws IndexOutOfBoundsException if the segment is too small
     */
    public static FrameHeader parseHeader(MemorySegment seg, long offset) {
        return parseHeaderBigEndian(seg, offset);
    }

    /**
     * Parses a frame header using explicit big-endian byte extraction for all
     * multi-byte fields (RFC 7540 §4.1).
     *
     * @param seg    source segment
     * @param offset byte offset
     * @return parsed frame header
     */
    public static FrameHeader parseHeaderBigEndian(MemorySegment seg, long offset) {
        int byte0 = seg.get(ValueLayout.JAVA_BYTE, offset) & 0xFF;
        int byte1 = seg.get(ValueLayout.JAVA_BYTE, offset + 1) & 0xFF;
        int byte2 = seg.get(ValueLayout.JAVA_BYTE, offset + 2) & 0xFF;
        int length = (byte0 << 16) | (byte1 << 8) | byte2;

        int type = seg.get(ValueLayout.JAVA_BYTE, offset + 3) & 0xFF;
        int flags = seg.get(ValueLayout.JAVA_BYTE, offset + 4) & 0xFF;

        int sid0 = seg.get(ValueLayout.JAVA_BYTE, offset + 5) & 0xFF;
        int sid1 = seg.get(ValueLayout.JAVA_BYTE, offset + 6) & 0xFF;
        int sid2 = seg.get(ValueLayout.JAVA_BYTE, offset + 7) & 0xFF;
        int sid3 = seg.get(ValueLayout.JAVA_BYTE, offset + 8) & 0xFF;
        int streamId = ((sid0 & 0x7F) << 24) | (sid1 << 16) | (sid2 << 8) | sid3;

        return new FrameHeader(length, type, flags, streamId);
    }
}
