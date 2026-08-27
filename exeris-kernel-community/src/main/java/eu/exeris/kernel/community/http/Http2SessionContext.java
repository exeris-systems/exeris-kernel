/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.core.http.hpack.HpackDecoder;
import eu.exeris.kernel.core.http.hpack.HpackDynamicTable;
import eu.exeris.kernel.core.http.hpack.HpackEncoder;
import eu.exeris.kernel.core.http.http2.Http2FrameCodec;
import eu.exeris.kernel.core.http.http2.Http2FrameParser;
import eu.exeris.kernel.core.http.http2.Http2HeaderBlockAssembler;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Package-private per-connection HTTP/2 session state used by
 * {@link CommunityHttp2SessionProcessor}.
 *
 * <p>Extracted from {@link CommunityHttp2SessionProcessor} in v0.8 Sprint 3
 * (QA-018a) as one of four seams of the processor's God-class decomposition.
 * Owns:
 * <ul>
 *   <li>The per-connection HPACK encoder/decoder pair (RFC 7541) and their
 *       dynamic tables.</li>
 *   <li>The frame codec ({@link Http2FrameCodec}) with peer-advertised
 *       {@code SETTINGS_MAX_FRAME_SIZE}.</li>
 *   <li>The header-block assembler ({@link Http2HeaderBlockAssembler}) that
 *       tracks HEADERS / CONTINUATION continuation state.</li>
 *   <li>The per-stream request registry ({@link Http2RequestStreamState}).</li>
 *   <li>The {@code last-stream-id} pointer for GOAWAY framing.</li>
 * </ul>
 *
 * <p>Lifetime is one HTTP/2 connection; the processor wraps each session in a
 * try-with-resources so {@link #close()} discards any unprocessed assembler
 * state and request streams.
 */
// Retained suppressions:
// - TooManyMethods: session state requires accessors for assembler + codec + per-stream registry.
// - CyclomaticComplexity: applyPeerSettings dispatcher + decodePendingRequest path drive total CC=41.
// - CloseResource: Http2RequestStreamState ownership transfers on put/remove; LoanedBuffer transfers
//   via response.body() pipeline.
@SuppressWarnings({
        "PMD.TooManyMethods",
        "PMD.CyclomaticComplexity",
        "PMD.CloseResource"
})
final class Http2SessionContext implements AutoCloseable {

    private static final int HTTP2_MAX_DYNAMIC_TABLE_SIZE = 4096;
    private static final int HTTP2_SETTINGS_ENTRY_BYTES = 6;
    private static final int HTTP2_SETTINGS_HEADER_TABLE_SIZE = 0x01;
    private static final int HTTP2_SETTINGS_MAX_FRAME_SIZE = 0x05;
    private static final String HEADER_CONTENT_LENGTH = "Content-Length";
    private static final String UPGRADE_TOKEN = "upgrade";

    /**
     * Default max concurrent client-initiated streams per connection (RFC 7540 §5.1.2).
     * Matches the recommended floor in §6.5.2 — operators tuning for high-fanout workloads
     * can raise this via a future SETTINGS extension; for v0.8 it's a fixed conservative
     * cap that protects the server from stream-table exhaustion (HTTP-112).
     */
    private static final int HTTP2_DEFAULT_MAX_CONCURRENT_STREAMS = 100;

    /**
     * Per-connection Rapid Reset budget (CVE-2023-44487). An inbound {@code RST_STREAM}
     * increments {@link #rapidResetCount}; a request that reaches dispatch decrements it
     * (floor 0). A peer doing legitimate work — even one that cancels streams — keeps the
     * <em>net</em> count low, while an open-then-reset flood that performs no work drives it
     * past this budget and trips {@code GOAWAY(ENHANCE_YOUR_CALM)}. Codec-internal (no SPI knob)
     * per the v0.9 "no SPI change" scope; generous-but-finite at 2× the RFC 7540 §6.5.2
     * concurrent-stream floor.
     */
    private static final int HTTP2_RAPID_RESET_BUDGET = 200;

    private final HpackDynamicTable encodeTable;
    private final HpackDecoder decoder;
    private final HpackEncoder encoder;
    private final Http2FrameCodec codec;
    private final Http2HeaderBlockAssembler assembler;
    private final Map<Integer, Http2RequestStreamState> requestStreams;
    private final int maxConcurrentStreams;
    private boolean pendingEndStream;
    private int lastProcessedStreamId;
    /**
     * Highest peer-initiated stream-id observed so far on this connection. Used to enforce
     * RFC 7540 §5.1.1 (client-initiated stream IDs MUST be odd and strictly increasing).
     * Zero means "no stream opened yet"; the first acceptable client stream is id 1.
     */
    private int lastClientStreamId;
    /**
     * Net count of inbound {@code RST_STREAM} frames not yet offset by a dispatched request
     * (CVE-2023-44487 rapid-reset accounting). See {@link #HTTP2_RAPID_RESET_BUDGET}.
     */
    private int rapidResetCount;

    private Http2SessionContext(HpackDynamicTable encodeTable,
                                HpackDecoder decoder,
                                HpackEncoder encoder,
                                Http2FrameCodec codec,
                                Http2HeaderBlockAssembler assembler) {
        this.encodeTable = encodeTable;
        this.decoder = decoder;
        this.encoder = encoder;
        this.codec = codec;
        this.assembler = assembler;
        this.requestStreams = new HashMap<>();
        this.maxConcurrentStreams = HTTP2_DEFAULT_MAX_CONCURRENT_STREAMS;
        this.pendingEndStream = false;
        this.lastProcessedStreamId = 0;
        this.lastClientStreamId = 0;
        this.rapidResetCount = 0;
    }

    /**
     * Builds a session's codec set from the three HTTP/2 bounds the configuration carries.
     *
     * <p>Takes the {@link HttpConfig} rather than the three {@code int}s it reads out of it. They
     * are the same type, adjacent, and all default to 65 536, so a transposed pair would compile,
     * pass every test that uses defaults, and only diverge once an operator configured one — which
     * is the exact failure shape this slice exists to remove, reintroduced at the call site.
     */
    /* default */ static Http2SessionContext create(MemoryAllocator allocator, HttpConfig config) {
        HpackDynamicTable decodeTable = new HpackDynamicTable(HTTP2_MAX_DYNAMIC_TABLE_SIZE);
        HpackDynamicTable encodeTable = new HpackDynamicTable(HTTP2_MAX_DYNAMIC_TABLE_SIZE);
        HpackDecoder decoder = new HpackDecoder(
                decodeTable, allocator, config.maxHeaderListSize(), config.maxStringLiteralSize());
        HpackEncoder encoder = new HpackEncoder(encodeTable, allocator, false);
        Http2FrameCodec codec = new Http2FrameCodec();
        Http2HeaderBlockAssembler assembler =
                new Http2HeaderBlockAssembler(allocator, config.maxHeaderBlockSize());
        return new Http2SessionContext(encodeTable, decoder, encoder, codec, assembler);
    }

    /* default */ Http2FrameCodec codec() {
        return codec;
    }

    /**
     * Parses peer-advertised SETTINGS entries from the frame payload and
     * applies the ones this implementation honours: HEADER_TABLE_SIZE (0x01)
     * resizes the HPACK encoder dynamic table, MAX_FRAME_SIZE (0x05) clamps
     * the frame codec's maximum frame size to the codec-enforced bounds.
     * All other settings are accepted but ignored. Throws when the payload
     * length is not a multiple of {@value #HTTP2_SETTINGS_ENTRY_BYTES}.
     */
    /* default */ void applyPeerSettings(LoanedBuffer aggregate, long payloadOffset, int payloadLength) {
        if (payloadLength == 0) {
            return;
        }
        if (payloadLength % HTTP2_SETTINGS_ENTRY_BYTES != 0) {
            throw new IllegalStateException("Invalid SETTINGS payload length");
        }
        MemorySegment segment = aggregate.segment();
        for (int offset = 0; offset < payloadLength; offset += HTTP2_SETTINGS_ENTRY_BYTES) {
            long cursor = payloadOffset + offset;
            int settingId = ((segment.get(ValueLayout.JAVA_BYTE, cursor) & 0xFF) << 8)
                    | (segment.get(ValueLayout.JAVA_BYTE, cursor + 1) & 0xFF);
            long settingValue = ((segment.get(ValueLayout.JAVA_BYTE, cursor + 2) & 0xFFL) << 24)
                    | ((segment.get(ValueLayout.JAVA_BYTE, cursor + 3) & 0xFFL) << 16)
                    | ((segment.get(ValueLayout.JAVA_BYTE, cursor + 4) & 0xFFL) << 8)
                    | (segment.get(ValueLayout.JAVA_BYTE, cursor + 5) & 0xFFL);
            if (settingId == HTTP2_SETTINGS_HEADER_TABLE_SIZE) {
                encodeTable.setMaxSize(settingValue);
            } else if (settingId == HTTP2_SETTINGS_MAX_FRAME_SIZE
                    && settingValue >= Http2FrameCodec.MIN_MAX_FRAME_SIZE
                    && settingValue <= Http2FrameCodec.MAX_MAX_FRAME_SIZE) {
                codec.setMaxFrameSize((int) settingValue);
            }
        }
    }

    /* default */ boolean isAwaitingContinuation() {
        return assembler.isAwaitingContinuation();
    }

    /* default */ void setPendingEndStream(boolean endStream) {
        this.pendingEndStream = endStream;
    }

    /* default */ boolean pendingEndStream() {
        return pendingEndStream;
    }

    /**
     * Admission decision for a newly-arriving peer-initiated HEADERS frame's stream-id
     * (HTTP-112, v0.8 Sprint 5). Captures the three cases RFC 7540 distinguishes between
     * an acceptable new stream and the two refuse-reasons:
     *
     * <ul>
     *   <li>{@link #ACCEPT} — odd id, strictly greater than {@code lastClientStreamId},
     *       and {@code requestStreams.size() < maxConcurrentStreams}. The session
     *       advances {@code lastClientStreamId}; caller proceeds with HEADERS decode.</li>
     *   <li>{@link #REJECT_INVALID_ID} — RFC 7540 §5.1.1 violation: even id (server-side
     *       reserved) OR id ≤ {@code lastClientStreamId}. Caller MUST send GOAWAY
     *       PROTOCOL_ERROR and close the connection.</li>
     *   <li>{@link #REJECT_OVER_CAP} — RFC 7540 §5.1.2 cap exceeded. Caller MUST send
     *       RST_STREAM REFUSED_STREAM for this stream-id and skip the HEADERS body. The
     *       connection stays open; other streams continue normally.</li>
     * </ul>
     */
    /* default */ enum StreamAdmission {
        ACCEPT,
        REJECT_INVALID_ID,
        REJECT_OVER_CAP
    }

    /**
     * Validates {@code streamId} against RFC 7540 §5.1.1 (peer-initiated IDs MUST be odd
     * and strictly increasing) and §5.1.2 ({@code SETTINGS_MAX_CONCURRENT_STREAMS} cap).
     * On {@link StreamAdmission#ACCEPT} the session advances its
     * {@code lastClientStreamId} watermark; the caller is responsible for any subsequent
     * error-frame emission on the two reject paths.
     */
    /* default */ StreamAdmission admitClientStreamId(int streamId) {
        if ((streamId & 1) == 0 || streamId <= lastClientStreamId) {
            return StreamAdmission.REJECT_INVALID_ID;
        }
        if (requestStreams.size() >= maxConcurrentStreams) {
            return StreamAdmission.REJECT_OVER_CAP;
        }
        lastClientStreamId = streamId;
        return StreamAdmission.ACCEPT;
    }

    /* default */ int maxConcurrentStreams() {
        return maxConcurrentStreams;
    }

    /* default */ int lastClientStreamId() {
        return lastClientStreamId;
    }

    /* default */ void beginHeaders(Http2FrameParser.FrameHeader header,
                                    MemorySegment payload,
                                    long dataOffset,
                                    int dataLength) {
        assembler.beginHeaders(header, payload, dataOffset, dataLength);
    }

    /* default */ void appendContinuation(Http2FrameParser.FrameHeader header,
                                          MemorySegment payload,
                                          long dataOffset,
                                          int dataLength) {
        assembler.appendContinuation(header, payload, dataOffset, dataLength);
    }

    /* default */ void validateContinuationMode(Http2FrameParser.FrameHeader header) {
        assembler.validateContinuationMode(header);
    }

    // HPACK decode errors surface as RuntimeException; marked as protocol-invalid for GOAWAY.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    /* default */ Http2DecodedRequest decodePendingRequest() {
        if (!assembler.isComplete()) {
            return new Http2DecodedRequest(0, null, "", List.of(), false);
        }
        int streamId = assembler.currentStreamId();
        PendingRequestHeaders pendingHeaders = new PendingRequestHeaders();
        MemorySegment block = assembler.completeBlock();
        try {
            decoder.decode(block, 0, (int) block.byteSize(),
                    (name, value, _) -> pendingHeaders.accept(name, value));
        } catch (RuntimeException _) {
            pendingHeaders.invalidate();
        }
        assembler.reset();
        pendingEndStream = false;
        return pendingHeaders.toDecodedRequest(streamId);
    }

    /* default */ void openRequestStream(Http2DecodedRequest request) {
        Http2RequestStreamState previous = requestStreams.put(
                request.streamId(),
                new Http2RequestStreamState(request.streamId(), request));
        if (previous != null) {
            previous.close();
        }
    }

    /* default */ Http2RequestStreamState requestStream(int streamId) {
        return requestStreams.get(streamId);
    }

    /* default */ Http2RequestStreamState takeRequestStream(int streamId) {
        return requestStreams.remove(streamId);
    }

    /* default */ void resetRequestStream(int streamId) {
        Http2RequestStreamState removed = requestStreams.remove(streamId);
        if (removed != null) {
            removed.close();
        }
    }

    /**
     * Records one inbound {@code RST_STREAM} frame against the Rapid Reset budget
     * (CVE-2023-44487) and reports whether the connection has crossed it. Returns {@code true}
     * once the net reset count exceeds {@link #HTTP2_RAPID_RESET_BUDGET}, at which point the
     * caller MUST terminate the connection with {@code GOAWAY(ENHANCE_YOUR_CALM)}.
     */
    /* default */ boolean recordInboundRstStream() {
        rapidResetCount++;
        return rapidResetCount > HTTP2_RAPID_RESET_BUDGET;
    }

    /**
     * Credits the Rapid Reset budget for a request that reached dispatch (real work), so a peer
     * that interleaves genuine requests with cancels does not accumulate toward the flood trip.
     * Floors at zero.
     */
    /* default */ void recordDispatchedRequest() {
        if (rapidResetCount > 0) {
            rapidResetCount--;
        }
    }

    /** Net inbound reset count not yet offset by dispatched work (for JFR / TCK assertions). */
    /* default */ int rapidResetCount() {
        return rapidResetCount;
    }

    /** The per-connection Rapid Reset budget (CVE-2023-44487); resets beyond it trip GOAWAY. */
    /* default */ static int rapidResetBudget() {
        return HTTP2_RAPID_RESET_BUDGET;
    }

    /* default */ long encodeResponseHeaders(MemorySegment target, HttpResponse response) {
        long position = 0;
        position = encoder.encodeHeader(
                target,
                position,
                ":status",
                Integer.toString(response.status().code()),
                false);

        boolean hasContentLength = false;
        for (HttpHeader header : response.headers()) {
            if (header.nameEqualsIgnoreCase(HEADER_CONTENT_LENGTH)) {
                hasContentLength = true;
            }
            if (isConnectionSpecificHeader(header.name()) || header.name().startsWith(":")) {
                continue;
            }
            position = encoder.encodeHeader(
                    target,
                    position,
                    header.name().toLowerCase(Locale.ROOT),
                    header.value(),
                    false);
        }

        LoanedBuffer body = response.body();
        if (!hasContentLength) {
            int bodyBytes = body == null ? 0 : (int) body.size();
            position = encoder.encodeHeader(target, position, "content-length", Integer.toString(bodyBytes), false);
        }
        return position;
    }

    /* default */ void clearPendingIfStreamMatches(int streamId) {
        if (assembler.currentStreamId() == streamId) {
            assembler.reset();
            pendingEndStream = false;
        }
    }

    /* default */ int lastProcessedStreamId() {
        return lastProcessedStreamId;
    }

    /* default */ void setLastProcessedStreamId(int lastProcessedStreamId) {
        this.lastProcessedStreamId = Math.max(this.lastProcessedStreamId, lastProcessedStreamId);
    }

    /**
     * Connection-specific HTTP/1 headers that MUST NOT appear in HTTP/2
     * messages (RFC 7540 §8.1.2.2). Used both by the response-encoding path
     * and by the request-decoding path in {@link PendingRequestHeaders}.
     */
    /* default */ static boolean isConnectionSpecificHeader(String headerName) {
        return "connection".equalsIgnoreCase(headerName)
                || "keep-alive".equalsIgnoreCase(headerName)
                || "proxy-connection".equalsIgnoreCase(headerName)
                || UPGRADE_TOKEN.equalsIgnoreCase(headerName)
                || "transfer-encoding".equalsIgnoreCase(headerName);
    }

    @Override
    public void close() {
        assembler.reset();
        for (Http2RequestStreamState requestStream : requestStreams.values()) {
            requestStream.close();
        }
        requestStreams.clear();
    }
}
