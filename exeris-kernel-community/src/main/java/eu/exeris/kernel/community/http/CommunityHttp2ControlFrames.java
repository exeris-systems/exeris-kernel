/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.core.http.http2.Http2ErrorCode;
import eu.exeris.kernel.core.http.http2.Http2FrameEncoder;
import eu.exeris.kernel.core.http.http2.Http2Settings;
import eu.exeris.kernel.core.http.http2.Http2FrameParser;
import eu.exeris.kernel.core.http.http2.Http2FrameType;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.transport.TransportStream;

import java.lang.foreign.MemorySegment;

/**
 * Package-private writers for HTTP/2 control frames issued by
 * {@link CommunityHttp2SessionProcessor}: SETTINGS, SETTINGS-ACK,
 * PING-ACK, RST_STREAM (REFUSED / CANCEL), and GOAWAY.
 *
 * <p>Each writer follows the same shape — borrow a small network slab, encode
 * the frame via {@link Http2FrameEncoder}, write it to the transport.
 */
final class CommunityHttp2ControlFrames {

    private static final int H2_HANDSHAKE_BUFFER_BYTES = 64;
    private static final int H2_PING_PAYLOAD_BYTES = 8;
    private static final int H2_PING_ACK_FLAG = 0x01;

    private CommunityHttp2ControlFrames() {
        // package-private static utility — never instantiated.
    }

    /* default */ static void sendPingAck(MemoryAllocator allocator,
                                          TransportStream stream,
                                          LoanedBuffer aggregate,
                                          long payloadOffset) {
        try (LoanedBuffer outbound = allocator.allocateNetwork(H2_HANDSHAKE_BUFFER_BYTES)) {
            Http2FrameEncoder.writeHeader(
                    outbound.segment(),
                    0,
                    H2_PING_PAYLOAD_BYTES,
                    Http2FrameType.PING.code(),
                    H2_PING_ACK_FLAG,
                    0);
            MemorySegment.copy(
                    aggregate.segment(),
                    payloadOffset,
                    outbound.segment(),
                    Http2FrameParser.FRAME_HEADER_SIZE,
                    H2_PING_PAYLOAD_BYTES);
            long written = Http2FrameParser.FRAME_HEADER_SIZE + (long) H2_PING_PAYLOAD_BYTES;
            outbound.setSize(written);
            stream.write(outbound.segment(), (int) written);
        }
    }

    /**
     * Sends the server's initial SETTINGS, advertising the decoded-field-section bound it enforces
     * via {@code SETTINGS_MAX_HEADER_LIST_SIZE} (RFC 9113 §6.5.2) — the protocol's own way to tell a
     * peer the limit, so it need not discover it by tripping it.
     *
     * <p>The value advertised is {@code maxHeaderListSize} and deliberately NOT the header-block
     * bound, even though the two ship with the same default. RFC 9113 §6.5.2 defines this setting
     * against the UNCOMPRESSED field section, which is the quantity the HPACK decoder enforces;
     * the block bound is on COMPRESSED wire bytes and is a different number as soon as either is
     * configured. Advertising the block bound here would tell a peer a limit nothing checks — and
     * asymmetrically, because raising it would be believed and never honoured while lowering it
     * would merely be conservative. Raising it is the only reason to touch the key.
     */
    /* default */ static void sendServerSettings(MemoryAllocator allocator, TransportStream stream,
                                                 int maxHeaderListSize) {
        try (LoanedBuffer outbound = allocator.allocateNetwork(H2_HANDSHAKE_BUFFER_BYTES)) {
            long written = Http2FrameEncoder.writeSettings(outbound.segment(), 0, 0, false,
                    Http2Settings.ID_MAX_HEADER_LIST_SIZE, maxHeaderListSize);
            outbound.setSize(written);
            stream.write(outbound.segment(), (int) written);
        }
    }

    /* default */ static void sendSettingsAck(MemoryAllocator allocator, TransportStream stream) {
        try (LoanedBuffer outbound = allocator.allocateNetwork(H2_HANDSHAKE_BUFFER_BYTES)) {
            long written = Http2FrameEncoder.writeSettings(outbound.segment(), 0, 0, true);
            outbound.setSize(written);
            stream.write(outbound.segment(), (int) written);
        }
    }

    /* default */ static void sendRstStreamRefused(MemoryAllocator allocator,
                                                   TransportStream stream,
                                                   int streamId) {
        sendRstStream(allocator, stream, streamId, Http2ErrorCode.REFUSED_STREAM);
    }

    /* default */ static void sendRstStreamCancel(MemoryAllocator allocator,
                                                  TransportStream stream,
                                                  int streamId) {
        sendRstStream(allocator, stream, streamId, Http2ErrorCode.CANCEL);
    }

    /* default */ static void sendGoAwayNoError(MemoryAllocator allocator, TransportStream stream) {
        sendGoAway(allocator, stream, 0, Http2ErrorCode.NO_ERROR);
    }

    /* default */ static void sendGoAway(MemoryAllocator allocator,
                                         TransportStream stream,
                                         int lastStreamId,
                                         Http2ErrorCode errorCode) {
        try (LoanedBuffer outbound = allocator.allocateNetwork(H2_HANDSHAKE_BUFFER_BYTES)) {
            long written = Http2FrameEncoder.writeGoAway(
                    outbound.segment(), 0, lastStreamId, errorCode.code());
            outbound.setSize(written);
            stream.write(outbound.segment(), (int) written);
        }
    }

    private static void sendRstStream(MemoryAllocator allocator,
                                      TransportStream stream,
                                      int streamId,
                                      Http2ErrorCode errorCode) {
        try (LoanedBuffer outbound = allocator.allocateNetwork(H2_HANDSHAKE_BUFFER_BYTES)) {
            long written = Http2FrameEncoder.writeRstStream(
                    outbound.segment(), 0, streamId, errorCode.code());
            outbound.setSize(written);
            stream.write(outbound.segment(), (int) written);
        }
    }
}
