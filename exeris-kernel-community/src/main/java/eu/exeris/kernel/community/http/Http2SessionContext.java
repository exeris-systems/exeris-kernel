/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.core.http.hpack.HpackDecoder;
import eu.exeris.kernel.core.http.hpack.HpackDynamicTable;
import eu.exeris.kernel.core.http.http2.Http2ErrorCode;
import eu.exeris.kernel.core.http.http2.Http2FlowController;
import eu.exeris.kernel.core.http.http2.Http2FrameCodec;
import eu.exeris.kernel.core.http.http2.Http2FrameParser;
import eu.exeris.kernel.core.http.http2.Http2HeaderBlockAssembler;
import eu.exeris.kernel.core.http.http2.Http2Settings;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Package-private per-connection HTTP/2 decode-side session state used by
 * {@link CommunityHttp2SessionProcessor}. Owns:
 * <ul>
 *   <li>The per-connection HPACK decoder (RFC 7541) and its dynamic table. The
 *       encoder belongs to {@link Http2ConnectionWriter}, which uses it under its lock.</li>
 *   <li>The frame codec ({@link Http2FrameCodec}), which enforces the server's own advertised
 *       {@code SETTINGS_MAX_FRAME_SIZE} on inbound frames; a peer's value never changes it.</li>
 *   <li>The header-block assembler ({@link Http2HeaderBlockAssembler}) that
 *       tracks HEADERS / CONTINUATION continuation state.</li>
 *   <li>The per-stream request registry ({@link Http2RequestStreamState}).</li>
 *   <li>The {@code last-stream-id} pointer for GOAWAY framing.</li>
 *   <li>The inbound flow-control state: the connection's receive window and the total of request-body
 *       bytes held across the requests still arriving, which bounds the memory a peer can pin on one
 *       connection.</li>
 * </ul>
 *
 * <p>Lifetime is one HTTP/2 connection; the processor wraps each session in a
 * try-with-resources so {@link #close()} discards any unprocessed assembler
 * state and request streams.
 *
 * <p><b>Allocation:</b> the HPACK decoder table, frame codec and header-block assembler are allocated once,
 * in {@link #create}, for the connection's whole life; the per-stream {@link Http2RequestStreamState}
 * registry adds one entry per stream admitted by {@link #admitClientStreamId}, up to
 * {@link #maxConcurrentStreams()}.
 * <p><b>Thread confinement:</b> owner thread — every accessor is called from the single
 * frame-processing loop {@link CommunityHttp2SessionProcessor} runs for this connection, never
 * concurrently and never from another connection's loop.
 * <p><b>Ownership:</b> owns every {@link Http2RequestStreamState} it admits until
 * {@link #takeRequestStream} hands it to the caller, {@link #resetRequestStream} discards it, or
 * {@link #close()} closes every entry still open when the connection ends.
 */
// Retained suppressions:
// - TooManyMethods: session state requires accessors for assembler + codec + per-stream registry.
// - CyclomaticComplexity: applyPeerSettings dispatcher + decodePendingRequest path drive total CC=41.
// - NullAssignment: handing the held upgrade request over is a move; nothing else may close it afterwards.
// - CloseResource: Http2RequestStreamState ownership transfers on put/remove; LoanedBuffer transfers
//   via response.body() pipeline.
@SuppressWarnings({
        "PMD.TooManyMethods",
        "PMD.CyclomaticComplexity",
        "PMD.NullAssignment",
        "PMD.CloseResource"
})
final class Http2SessionContext implements AutoCloseable {

    /**
     * The receive window the server keeps open on the connection and on each request stream: the
     * protocol default, because no {@code SETTINGS_INITIAL_WINDOW_SIZE} is advertised.
     */
    /* default */ static final int INBOUND_WINDOW_BYTES = Http2FlowController.DEFAULT_WINDOW_SIZE;

    private static final int HTTP2_MAX_DYNAMIC_TABLE_SIZE = 4096;
    private static final int HTTP2_SETTINGS_ENTRY_BYTES = 6;
    private static final int HTTP2_SETTINGS_HEADER_TABLE_SIZE = 0x01;
    private static final String UPGRADE_TOKEN = "upgrade";

    /**
     * Default max concurrent client-initiated streams per connection (RFC 7540 §5.1.2). Matches
     * the recommended floor in §6.5.2 — a fixed conservative cap that protects the server from
     * stream-table exhaustion; raising it for high-fanout workloads would need a SETTINGS
     * extension this session does not yet send.
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

    private final HpackDecoder decoder;
    private final Http2FrameCodec codec;
    private final Http2HeaderBlockAssembler assembler;
    private final Map<Integer, Http2RequestStreamState> requestStreams;
    private final int maxConcurrentStreams;
    private final Http2FlowController connectionReceiveWindow =
            new Http2FlowController(INBOUND_WINDOW_BYTES);
    private long bufferedRequestBodyBytes;
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
    /**
     * The request of an {@code h2c} upgrade, held as stream 1 until the peer's first SETTINGS frame
     * has been applied and acknowledged; {@code null} when no upgrade request is waiting.
     */
    private Http2RequestStreamState pendingUpgrade;

    private Http2SessionContext(HpackDecoder decoder,
                                Http2FrameCodec codec,
                                Http2HeaderBlockAssembler assembler) {
        this.decoder = decoder;
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
     * pass every test that uses defaults, and only diverge once an operator configured one: a
     * configured bound silently not applied, produced at the call site.
     */
    /* default */ static Http2SessionContext create(MemoryAllocator allocator, HttpConfig config) {
        HpackDynamicTable decodeTable = new HpackDynamicTable(HTTP2_MAX_DYNAMIC_TABLE_SIZE);
        HpackDecoder decoder = new HpackDecoder(
                decodeTable, allocator, config.maxHeaderListSize(), config.maxStringLiteralSize());
        // The inbound frame limit is the SETTINGS_MAX_FRAME_SIZE the server advertises, and the server
        // advertises none, so it is the protocol default (RFC 9113 section 6.5.2). Raising this must go
        // together with advertising the same value in CommunityHttp2ControlFrames#sendServerSettings.
        Http2FrameCodec codec = new Http2FrameCodec(Http2FrameCodec.DEFAULT_MAX_FRAME_SIZE);
        Http2HeaderBlockAssembler assembler =
                new Http2HeaderBlockAssembler(allocator, config.maxHeaderBlockSize());
        return new Http2SessionContext(decoder, codec, assembler);
    }

    /* default */ Http2FrameCodec codec() {
        return codec;
    }

    /**
     * Parses peer-advertised SETTINGS entries from the frame payload and applies them in order:
     * HEADER_TABLE_SIZE (0x01) resizes the HPACK encoder's table, INITIAL_WINDOW_SIZE (0x04) moves
     * the send window of every open response stream, and MAX_FRAME_SIZE (0x05) is validated against
     * the protocol bounds and otherwise not stored: it limits the frames the peer receives, which the
     * writer keeps within every legal value, and never the frames this server accepts. All other
     * settings are accepted but ignored. Throws when the payload length is not a multiple of
     * {@value #HTTP2_SETTINGS_ENTRY_BYTES}.
     *
     * @return {@link Http2ErrorCode#FLOW_CONTROL_ERROR} when an initial window size exceeds the
     *         maximum or moves a stream window past it, {@link Http2ErrorCode#PROTOCOL_ERROR} when a
     *         maximum frame size lies outside [{@value Http2FrameCodec#MIN_MAX_FRAME_SIZE},
     *         {@value Http2FrameCodec#MAX_MAX_FRAME_SIZE}], else {@link Http2ErrorCode#NO_ERROR}; the
     *         caller ends the connection on an error
     */
    /* default */ Http2ErrorCode applyPeerSettings(LoanedBuffer aggregate,
                                                   long payloadOffset,
                                                   int payloadLength,
                                                   Http2ConnectionWriter writer) {
        if (payloadLength == 0) {
            return Http2ErrorCode.NO_ERROR;
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
                writer.applyHeaderTableSize(settingValue);
            } else if (settingId == Http2Settings.ID_INITIAL_WINDOW_SIZE) {
                Http2ErrorCode result = applyInitialWindowSize(settingValue, writer);
                if (result != Http2ErrorCode.NO_ERROR) {
                    return result;
                }
            } else if (settingId == Http2Settings.ID_MAX_FRAME_SIZE
                    && (settingValue < Http2FrameCodec.MIN_MAX_FRAME_SIZE
                    || settingValue > Http2FrameCodec.MAX_MAX_FRAME_SIZE)) {
                return Http2ErrorCode.PROTOCOL_ERROR;
            }
        }
        return Http2ErrorCode.NO_ERROR;
    }

    private Http2ErrorCode applyInitialWindowSize(long value, Http2ConnectionWriter writer) {
        if (value > Integer.MAX_VALUE) {
            return Http2ErrorCode.FLOW_CONTROL_ERROR;
        }
        Http2ErrorCode result = writer.applyInitialWindowSize((int) value);
        if (result != Http2ErrorCode.NO_ERROR) {
            return result;
        }
        return requestPhaseWindowsFit((int) value)
                ? Http2ErrorCode.NO_ERROR
                : Http2ErrorCode.FLOW_CONTROL_ERROR;
    }

    /**
     * Whether every stream whose request is still arriving can start its response window at
     * {@code initialWindow} plus the credit it has already received without passing the maximum
     * (RFC 9113 section 6.9.2: a change that overflows any stream window is a connection error).
     */
    private boolean requestPhaseWindowsFit(int initialWindow) {
        for (Http2RequestStreamState requestStream : requestStreams.values()) {
            if (!requestStream.addSendWindowIncrement(0, initialWindow)) {
                return false;
            }
        }
        return true;
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
     * Admission decision for a newly-arriving peer-initiated HEADERS frame's stream-id. Captures
     * the three cases RFC 7540 distinguishes between an acceptable new stream and the two
     * refuse-reasons:
     *
     * <ul>
     *   <li>{@link #ACCEPT} — odd id, strictly greater than {@code lastClientStreamId},
     *       and the requests still arriving plus the response streams still held by the writer
     *       number fewer than {@code maxConcurrentStreams}. The session
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
        return admitClientStreamId(streamId, 0);
    }

    /**
     * As {@link #admitClientStreamId(int)}, counting the response streams that still hold a slot in
     * the connection's writer against the cap along with the requests still arriving.
     *
     * @param streamId            the client-initiated stream id of the arriving HEADERS frame
     * @param openResponseStreams how many response streams hold a slot
     */
    /* default */ StreamAdmission admitClientStreamId(int streamId, int openResponseStreams) {
        if ((streamId & 1) == 0 || streamId <= lastClientStreamId) {
            return StreamAdmission.REJECT_INVALID_ID;
        }
        if (requestStreams.size() + openResponseStreams >= maxConcurrentStreams) {
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

    /**
     * Decodes the completed header block into a request and resets the assembler.
     *
     * <p>A block that overran a size bound ({@code http.maxHeaderListSize},
     * {@code http.maxStringLiteralSize}) was still read to its end, so the shared HPACK dynamic
     * table is in step with the peer; the result is marked {@code headersTooLarge} and carries no
     * fields. A block that failed to decode any other way leaves that table untrustworthy, and
     * surfaces as {@link Http2CompressionException} for the caller to turn into a connection error.
     */
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
        } catch (HpackDecoder.HpackDecodingException hpack) {
            assembler.reset();
            pendingEndStream = false;
            if (hpack.isSizeLimitExceeded()) {
                return new Http2DecodedRequest(streamId, null, "", List.of(), false, true);
            }
            throw new Http2CompressionException(hpack);
        } catch (RuntimeException cause) {
            assembler.reset();
            pendingEndStream = false;
            throw new Http2CompressionException(cause);
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
            bufferedRequestBodyBytes -= previous.bodyBytes();
            previous.close();
        }
    }

    /**
     * Charges {@code bytes} of a DATA frame, padding included, to the connection's receive window.
     *
     * @return {@code false} when the frame exceeds the window the server has granted, which is a
     *         connection error (FLOW_CONTROL_ERROR)
     */
    /* default */ boolean consumeConnectionReceiveWindow(int bytes) {
        return connectionReceiveWindow.consume(bytes);
    }

    /**
     * Raises the connection's receive window back to {@link #INBOUND_WINDOW_BYTES} once it has fallen
     * to half of it or less.
     *
     * @return the increment to advertise on stream 0, or {@code 0} when no credit is due
     */
    /* default */ int replenishConnectionReceiveWindow() {
        long window = connectionReceiveWindow.windowSize();
        if (window > INBOUND_WINDOW_BYTES / 2) {
            return 0;
        }
        int increment = (int) (INBOUND_WINDOW_BYTES - window);
        connectionReceiveWindow.increment(increment);
        return increment;
    }

    /**
     * Whether holding {@code fragmentBytes} more would take the request bodies buffered on this
     * connection past {@code maxBodyBytes}; never true when the limit is unlimited ({@code < 0}).
     */
    /* default */ boolean exceedsRequestBodyBudget(long maxBodyBytes, int fragmentBytes) {
        return maxBodyBytes >= 0 && bufferedRequestBodyBytes + fragmentBytes > maxBodyBytes;
    }

    /** Appends a DATA fragment to a request body and counts it against the connection's budget. */
    /* default */ void appendRequestBody(Http2RequestStreamState requestStream,
                                         MemoryAllocator allocator,
                                         MemorySegment source,
                                         long sourceOffset,
                                         int length) {
        requestStream.appendBody(allocator, source, sourceOffset, length);
        if (length > 0) {
            bufferedRequestBodyBytes += length;
        }
    }

    /* default */ Http2RequestStreamState requestStream(int streamId) {
        return requestStreams.get(streamId);
    }

    /* default */ Http2RequestStreamState takeRequestStream(int streamId) {
        Http2RequestStreamState taken = requestStreams.remove(streamId);
        if (taken != null) {
            // Read before the caller detaches the body, which zeroes the stream's count.
            bufferedRequestBodyBytes -= taken.bodyBytes();
        }
        return taken;
    }

    /* default */ void resetRequestStream(int streamId) {
        Http2RequestStreamState removed = requestStreams.remove(streamId);
        if (removed != null) {
            bufferedRequestBodyBytes -= removed.bodyBytes();
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

    /**
     * Holds the request of an {@code h2c} upgrade as stream 1 until {@link #takePendingUpgrade()}.
     * Stream 1 is the client's first stream, so a later HEADERS frame on it is refused as an id that
     * does not advance. The session owns the request, and its body, until it is taken.
     */
    /* default */ void holdPendingUpgrade(Http2RequestStreamState upgradeRequest) {
        pendingUpgrade = upgradeRequest;
        lastClientStreamId = upgradeRequest.streamId();
    }

    /** Whether an {@code h2c} upgrade request is still waiting for the peer's first SETTINGS frame. */
    /* default */ boolean hasPendingUpgrade() {
        return pendingUpgrade != null;
    }

    /** Hands the held {@code h2c} upgrade request, and ownership of its body, to the caller. */
    /* default */ Http2RequestStreamState takePendingUpgrade() {
        Http2RequestStreamState upgradeRequest = pendingUpgrade;
        pendingUpgrade = null;
        return upgradeRequest;
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
        if (pendingUpgrade != null) {
            pendingUpgrade.close();
        }
    }
}
