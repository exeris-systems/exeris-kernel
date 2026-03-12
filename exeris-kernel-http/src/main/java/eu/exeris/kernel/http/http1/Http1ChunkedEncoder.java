/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.http.http1;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * RFC 9112 §7.1 — HTTP/1.1 Chunked Transfer Encoder.
 *
 * <h2>Chunk Layout</h2>
 * <pre>
 * chunk-size CRLF
 * chunk-data CRLF
 * </pre>
 *
 * <h2>Termination</h2>
 * <p>The chunked message is terminated by a final chunk with size 0, followed by CRLF CRLF.
 *
 * @since 0.5.0
 */
public final class Http1ChunkedEncoder {

    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] LAST_CHUNK = {'0', '\r', '\n', '\r', '\n'};

    private Http1ChunkedEncoder() {
    }

    /**
     * Writes a single chunk header (size in hex + CRLF) into the segment.
     *
     * @param seg    destination segment
     * @param offset byte offset
     * @param size   chunk data size
     * @return byte position after the chunk header (caller writes data, then CRLF)
     */
    public static long writeChunkHeader(MemorySegment seg, long offset, int size) {
        if (size < 0) {
            throw new IllegalArgumentException("Chunk size must be non-negative, got: " + size);
        }
        long pos = offset;
        if (size == 0) {
            seg.set(ValueLayout.JAVA_BYTE, pos, (byte) '0');
            pos++;
        } else {
            boolean seenNonZero = false;
            for (int step = 0; step < 8; step++) {
                int shift = 28 - step * 4;
                int hexDigit = (size >>> shift) & 0xF;
                if (!seenNonZero && hexDigit == 0) {
                    continue;
                }
                seenNonZero = true;
                seg.set(ValueLayout.JAVA_BYTE, pos,
                        (byte) (hexDigit < 10 ? '0' + hexDigit : 'a' + hexDigit - 10));
                pos++;
            }
        }
        seg.set(ValueLayout.JAVA_BYTE, pos, CRLF[0]);
        pos++;
        seg.set(ValueLayout.JAVA_BYTE, pos, CRLF[1]);
        pos++;
        return pos;
    }

    /**
     * Writes the CRLF terminator after chunk data.
     *
     * @param seg    destination segment
     * @param offset byte offset (immediately after chunk data)
     * @return new byte position
     */
    public static long writeChunkTrailer(MemorySegment seg, long offset) {
        seg.set(ValueLayout.JAVA_BYTE, offset, CRLF[0]);
        seg.set(ValueLayout.JAVA_BYTE, offset + 1, CRLF[1]);
        return offset + 2;
    }

    /**
     * Writes the last-chunk (0 CRLF CRLF) to terminate the chunked message.
     *
     * @param seg    destination segment
     * @param offset byte offset
     * @return new byte position (5 bytes written)
     */
    public static long writeLastChunk(MemorySegment seg, long offset) {
        MemorySegment.copy(MemorySegment.ofArray(LAST_CHUNK), ValueLayout.JAVA_BYTE, 0,
                seg, ValueLayout.JAVA_BYTE, offset, LAST_CHUNK.length);
        return offset + LAST_CHUNK.length;
    }

    /**
     * Convenience: writes a complete chunk (header + data + trailer).
     *
     * @param seg    destination segment
     * @param offset byte offset
     * @param data   chunk data segment
     * @return new byte position after the complete chunk
     */
    public static long writeChunk(MemorySegment seg, long offset, MemorySegment data) {
        long dataSize = data.byteSize();
        if (dataSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Chunk data exceeds maximum allowed size (" + dataSize + " > " + Integer.MAX_VALUE + ")");
        }
        int size = (int) dataSize;
        long pos = writeChunkHeader(seg, offset, size);
        MemorySegment.copy(data, 0, seg, pos, size);
        pos += size;
        return writeChunkTrailer(seg, pos);
    }
}
