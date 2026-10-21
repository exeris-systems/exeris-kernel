/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.core.http.hpack.HpackDynamicTable;
import eu.exeris.kernel.core.http.hpack.HpackEncoder;
import eu.exeris.kernel.core.http.http2.Http2FrameType;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Builders for the client-to-server HTTP/2 frames the frame-loop tests feed. */
final class Http2Frames {

    static final byte[] PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private Http2Frames() {
    }

    static byte[] frame(int type, int flags, int streamId, byte[] payload) {
        byte[] frame = new byte[9 + payload.length];
        int len = payload.length;
        frame[0] = (byte) ((len >>> 16) & 0xFF);
        frame[1] = (byte) ((len >>> 8) & 0xFF);
        frame[2] = (byte) (len & 0xFF);
        frame[3] = (byte) (type & 0xFF);
        frame[4] = (byte) (flags & 0xFF);
        frame[5] = (byte) ((streamId >>> 24) & 0x7F);
        frame[6] = (byte) ((streamId >>> 16) & 0xFF);
        frame[7] = (byte) ((streamId >>> 8) & 0xFF);
        frame[8] = (byte) (streamId & 0xFF);
        System.arraycopy(payload, 0, frame, 9, payload.length);
        return frame;
    }

    static byte[] emptySettings() {
        return frame(Http2FrameType.SETTINGS.code(), 0, 0, new byte[0]);
    }

    /** A SETTINGS frame with one entry. */
    static byte[] settings(int id, long value) {
        byte[] payload = new byte[6];
        payload[0] = (byte) (id >>> 8);
        payload[1] = (byte) id;
        payload[2] = (byte) (value >>> 24);
        payload[3] = (byte) (value >>> 16);
        payload[4] = (byte) (value >>> 8);
        payload[5] = (byte) value;
        return frame(Http2FrameType.SETTINGS.code(), 0, 0, payload);
    }

    static byte[] headers(int streamId, byte[] block, boolean endStream) {
        return frame(Http2FrameType.HEADERS.code(), 0x04 | (endStream ? 0x01 : 0), streamId, block);
    }

    static byte[] data(int streamId, byte[] payload, boolean endStream) {
        return frame(Http2FrameType.DATA.code(), endStream ? 0x01 : 0, streamId, payload);
    }

    static byte[] windowUpdate(int streamId, int increment) {
        return frame(Http2FrameType.WINDOW_UPDATE.code(), 0, streamId, int32(increment));
    }

    static byte[] windowUpdateOfLength(int streamId, int length) {
        return frame(Http2FrameType.WINDOW_UPDATE.code(), 0, streamId, new byte[length]);
    }

    static byte[] rstStream(int streamId, int errorCode) {
        return frame(Http2FrameType.RST_STREAM.code(), 0, streamId, int32(errorCode));
    }

    static byte[] int32(int value) {
        return new byte[]{(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
    }

    static int readInt32(byte[] payload, int offset) {
        return ((payload[offset] & 0xFF) << 24) | ((payload[offset + 1] & 0xFF) << 16)
                | ((payload[offset + 2] & 0xFF) << 8) | (payload[offset + 3] & 0xFF);
    }

    /** A request header block encoded against a fresh dynamic table, so it decodes on any stream. */
    static byte[] requestBlock(MemoryAllocator allocator, String method, String path) {
        return encode(allocator, List.of(
                new HttpHeader(":method", method),
                new HttpHeader(":path", path),
                new HttpHeader(":scheme", "http"),
                new HttpHeader(":authority", "localhost")));
    }

    static byte[] encode(MemoryAllocator allocator, List<HttpHeader> headers) {
        try (LoanedBuffer block = allocator.allocateNetwork(4096)) {
            HpackEncoder encoder = new HpackEncoder(new HpackDynamicTable(0), allocator, false);
            long pos = 0;
            for (HttpHeader header : headers) {
                pos = encoder.encodeHeader(block.segment(), pos, header.name(), header.value(), false);
            }
            byte[] bytes = new byte[(int) pos];
            MemorySegment.copy(block.segment(), 0, MemorySegment.ofArray(bytes), 0, bytes.length);
            return bytes;
        }
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
