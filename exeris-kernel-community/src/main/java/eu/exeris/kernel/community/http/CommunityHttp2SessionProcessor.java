/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.core.http.hpack.HpackDecoder;
import eu.exeris.kernel.core.http.hpack.HpackDynamicTable;
import eu.exeris.kernel.core.http.hpack.HpackEncoder;
import eu.exeris.kernel.core.http.http2.Http2ErrorCode;
import eu.exeris.kernel.core.http.http2.Http2FrameCodec;
import eu.exeris.kernel.core.http.http2.Http2FrameEncoder;
import eu.exeris.kernel.core.http.http2.Http2FrameParser;
import eu.exeris.kernel.core.http.http2.Http2FrameType;
import eu.exeris.kernel.core.http.http2.Http2HeaderBlockAssembler;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpResponseBodyEncoderRegistry;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.transport.TransportStream;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@SuppressWarnings({
        "PMD.AvoidCatchingGenericException",
        "PMD.CloseResource",
        "PMD.CouplingBetweenObjects",
        "PMD.CyclomaticComplexity",
        "PMD.ExcessiveImports",
        "PMD.GodClass",
        "PMD.TooManyMethods"
})
final class CommunityHttp2SessionProcessor {

    private static final System.Logger LOG =
            System.getLogger(CommunityHttp2SessionProcessor.class.getName());

    private static final String CRLF = "\r\n";
    private static final String HEADER_CONTENT_LENGTH = "Content-Length";
    private static final String UPGRADE_TOKEN = "upgrade";
    private static final long HTTP2_FRAME_LOOP_INVALID = -1L;
    private static final long HTTP2_FRAME_LOOP_STOP = -2L;
    private static final int HTTP2_MAX_DYNAMIC_TABLE_SIZE = 4096;
    private static final int HTTP2_MAX_HEADER_LIST_SIZE = 65_536;
    private static final int HTTP2_MAX_HEADER_BLOCK_BYTES = 65_536;
    private static final int HTTP2_MAX_FRAME_PAYLOAD_BYTES = 16 * 1024;
    private static final int HTTP2_FLAG_END_STREAM = 0x01;
    private static final int HTTP2_FLAG_END_HEADERS = 0x04;
    private static final int HTTP2_FLAG_PADDED = 0x08;
    private static final int HTTP2_SETTINGS_HEADER_TABLE_SIZE = 0x01;
    private static final int HTTP2_PADDED_LENGTH_FIELD_BYTES = 1;
    private static final int HTTP2_PRIORITY_FIELD_BYTES = 5;
    private static final int H2_HANDSHAKE_BUFFER_BYTES = 64;

    private final MemoryAllocator allocator;
    private final HttpResponseBodyEncoderRegistry encoderRegistry;
    private final HttpConfig config;
    private final CommunityHttpRequestDispatcher requestDispatcher;
    private final int readChunkBytes;
    private final int maxAggregateBytes;

    /* default */ CommunityHttp2SessionProcessor(MemoryAllocator allocator,
                                   HttpResponseBodyEncoderRegistry encoderRegistry,
                                   HttpConfig config,
                                   CommunityHttpRequestDispatcher requestDispatcher,
                                   int readChunkBytes,
                                   int maxAggregateBytes) {
        this.allocator = Objects.requireNonNull(allocator, "allocator must not be null");
        this.encoderRegistry = Objects.requireNonNull(encoderRegistry, "encoderRegistry must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.requestDispatcher = Objects.requireNonNull(requestDispatcher, "requestDispatcher must not be null");
        this.readChunkBytes = readChunkBytes;
        this.maxAggregateBytes = maxAggregateBytes;
    }

    /* default */ void handlePriorKnowledge(TransportStream stream,
                              HttpHandler handler,
                              ProcessingState state,
                              long totalBytes,
                              long initialFrameOffset) {
        if (LOG.isLoggable(System.Logger.Level.INFO)) {
            LOG.log(System.Logger.Level.INFO,
                    "HTTP/2 prior-knowledge preface detected; entering h2c request frame-loop mode");
        }
        sendHttp2ServerSettings(stream);
        try (Http2SessionContext session = Http2SessionContext.create(allocator)) {
            processBufferedHttp2Frames(stream, handler, session, state, totalBytes, initialFrameOffset);
        }
    }

    /* default */ void handleUpgrade(ReadResult readResult,
                       TransportStream stream,
                       HttpHandler handler,
                       ProcessingState state) {
        LoanedBuffer aggregate = state.aggregate();
        writeHttp11UpgradeResponse(stream);
        long bufferedHttp2Bytes = CommunityHttpBufferOps.retainUnreadBytes(aggregate, readResult.consumedBytes());
        sendHttp2ServerSettings(stream);
        try (Http2SessionContext session = Http2SessionContext.create(allocator)) {
            processBufferedHttp2Frames(stream, handler, session, state, bufferedHttp2Bytes, 0);
        }
    }

    private void writeHttp11UpgradeResponse(TransportStream stream) {
        byte[] responseBytes = (
                "HTTP/1.1 101 Switching Protocols" + CRLF
                        + "Connection: Upgrade" + CRLF
                        + "Upgrade: h2c" + CRLF
                        + CRLF)
                .getBytes(StandardCharsets.US_ASCII);
        MemorySegment responseSegment = MemorySegment.ofArray(responseBytes);
        stream.write(responseSegment, responseBytes.length);
    }

    private void processBufferedHttp2Frames(TransportStream stream,
                                            HttpHandler handler,
                                            Http2SessionContext session,
                                            ProcessingState state,
                                            long initialBytes,
                                            long initialFrameOffset) {
        long bufferedBytes = initialBytes;
        long offset = initialFrameOffset;

        while (true) {
            LoanedBuffer aggregate = state.aggregate();
            offset = processAvailableHttp2Frames(stream, handler, session, aggregate, bufferedBytes, offset);
            if (offset == HTTP2_FRAME_LOOP_STOP) {
                return;
            }
            if (offset == HTTP2_FRAME_LOOP_INVALID) {
                sendHttp2GoAway(stream, session.lastProcessedStreamId(), Http2ErrorCode.PROTOCOL_ERROR);
                return;
            }

            long unreadBytes = CommunityHttpBufferOps.compactUnreadBytes(aggregate, bufferedBytes, offset);
            offset = 0;
            bufferedBytes = unreadBytes;

            if (bufferedBytes >= maxAggregateBytes) {
                sendHttp2GoAway(stream, session.lastProcessedStreamId(), Http2ErrorCode.FRAME_SIZE_ERROR);
                return;
            }

            int read = readHttp2Bytes(stream, state, bufferedBytes);
            if (read < 0) {
                sendHttp2GoAwayNoError(stream);
                return;
            }
            if (read > 0) {
                bufferedBytes += read;
                state.aggregate().setSize(bufferedBytes);
            }
        }
    }

    private long processAvailableHttp2Frames(TransportStream stream,
                                             HttpHandler handler,
                                             Http2SessionContext session,
                                             LoanedBuffer aggregate,
                                             long bufferedBytes,
                                             long startOffset) {
        long offset = startOffset;
        while (bufferedBytes - offset >= Http2FrameParser.FRAME_HEADER_SIZE) {
            try {
                Http2FrameParser.FrameHeader header =
                        session.codec().parseAndValidate(aggregate.segment(), offset);
                long frameLength = (long) Http2FrameParser.FRAME_HEADER_SIZE + header.length();
                long frameEnd = offset + frameLength;
                if (bufferedBytes < frameEnd) {
                    return offset;
                }

                if (session.isAwaitingContinuation()) {
                    session.validateContinuationMode(header);
                }
                long payloadOffset = offset + Http2FrameParser.FRAME_HEADER_SIZE;
                if (!handleHttp2Frame(stream, handler, session, aggregate, payloadOffset, header)) {
                    return HTTP2_FRAME_LOOP_STOP;
                }
                offset = frameEnd;
            } catch (RuntimeException _) {
                return HTTP2_FRAME_LOOP_INVALID;
            }
        }
        return offset;
    }

    private boolean handleHttp2Frame(TransportStream stream,
                                     HttpHandler handler,
                                     Http2SessionContext session,
                                     LoanedBuffer aggregate,
                                     long payloadOffset,
                                     Http2FrameParser.FrameHeader header) {
        Http2FrameType frameType = header.frameType();
        if (frameType == null) {
            return true;
        }

        return switch (frameType) {
            case SETTINGS -> {
                if (header.streamId() != 0) {
                    throw new IllegalStateException("Invalid SETTINGS stream");
                }
                if (!header.isAck()) {
                    applyHttp2SettingsFromPeer(session, aggregate, payloadOffset, header.length());
                    sendHttp2SettingsAck(stream);
                }
                yield true;
            }
            case PING -> {
                if (header.streamId() != 0 || header.length() != 8) {
                    throw new IllegalStateException("Invalid PING frame");
                }
                if (!header.isAck()) {
                    sendHttp2PingAck(stream, aggregate, payloadOffset);
                }
                yield true;
            }
            case HEADERS -> handleHttp2HeadersFrame(stream, handler, session, aggregate, payloadOffset, header);
            case CONTINUATION -> {
                handleHttp2ContinuationFrame(stream, handler, session, aggregate, payloadOffset, header);
                yield true;
            }
            case DATA -> {
                handleHttp2DataFrame(stream, handler, session, aggregate, payloadOffset, header);
                yield true;
            }
            case GOAWAY -> false;
            case RST_STREAM -> {
                session.clearPendingIfStreamMatches(header.streamId());
                session.resetRequestStream(header.streamId());
                yield true;
            }
            default -> true;
        };
    }

    private void applyHttp2SettingsFromPeer(Http2SessionContext session,
                                            LoanedBuffer aggregate,
                                            long payloadOffset,
                                            int payloadLength) {
        if (payloadLength == 0) {
            return;
        }
        if (payloadLength % 6 != 0) {
            throw new IllegalStateException("Invalid SETTINGS payload length");
        }
        for (int offset = 0; offset < payloadLength; offset += 6) {
            long cursor = payloadOffset + offset;
            int settingId = ((aggregate.segment().get(ValueLayout.JAVA_BYTE, cursor) & 0xFF) << 8)
                    | (aggregate.segment().get(ValueLayout.JAVA_BYTE, cursor + 1) & 0xFF);
            long settingValue = ((aggregate.segment().get(ValueLayout.JAVA_BYTE, cursor + 2) & 0xFFL) << 24)
                    | ((aggregate.segment().get(ValueLayout.JAVA_BYTE, cursor + 3) & 0xFFL) << 16)
                    | ((aggregate.segment().get(ValueLayout.JAVA_BYTE, cursor + 4) & 0xFFL) << 8)
                    | (aggregate.segment().get(ValueLayout.JAVA_BYTE, cursor + 5) & 0xFFL);
            if (settingId == HTTP2_SETTINGS_HEADER_TABLE_SIZE) {
                session.applyPeerHeaderTableSize(settingValue);
            } else if (settingId == 0x05
                    && settingValue >= Http2FrameCodec.MIN_MAX_FRAME_SIZE
                    && settingValue <= Http2FrameCodec.MAX_MAX_FRAME_SIZE) {
                session.applyPeerMaxFrameSize((int) settingValue);
            }
        }
    }

    private boolean handleHttp2HeadersFrame(TransportStream stream,
                                            HttpHandler handler,
                                            Http2SessionContext session,
                                            LoanedBuffer aggregate,
                                            long payloadOffset,
                                            Http2FrameParser.FrameHeader header) {
        if (header.streamId() <= 0) {
            throw new IllegalStateException("Invalid HEADERS stream");
        }
        if (session.isAwaitingContinuation()) {
            sendHttp2GoAway(stream, session.lastProcessedStreamId(), Http2ErrorCode.PROTOCOL_ERROR);
            return false;
        }
        HeaderFragment fragment = extractHeadersFragment(aggregate, payloadOffset, header);
        session.setPendingEndStream(header.isEndStream());
        session.beginHeaders(header, aggregate.segment(), fragment.offset(), fragment.length());
        if (session.isAwaitingContinuation()) {
            return true;
        }
        decodeAndDispatchCompletedHeaderBlock(stream, handler, session);
        return true;
    }

    private void handleHttp2ContinuationFrame(TransportStream stream,
                                              HttpHandler handler,
                                              Http2SessionContext session,
                                              LoanedBuffer aggregate,
                                              long payloadOffset,
                                              Http2FrameParser.FrameHeader header) {
        session.appendContinuation(header, aggregate.segment(), payloadOffset, header.length());
        if (session.isAwaitingContinuation()) {
            return;
        }
        decodeAndDispatchCompletedHeaderBlock(stream, handler, session);
    }

    private void handleHttp2DataFrame(TransportStream stream,
                                      HttpHandler handler,
                                      Http2SessionContext session,
                                      LoanedBuffer aggregate,
                                      long payloadOffset,
                                      Http2FrameParser.FrameHeader header) {
        if (header.streamId() <= 0) {
            throw new IllegalStateException("Invalid DATA stream");
        }

        Http2RequestStreamState requestStream = session.requestStream(header.streamId());
        if (requestStream == null) {
            sendHttp2RstStreamRefused(stream, header.streamId());
            return;
        }

        HeaderFragment dataFragment = extractDataFragment(aggregate, payloadOffset, header);
        int nextBodyBytes = requestStream.bodyBytes() + dataFragment.length();
        long maxBodyBytes = config.maxRequestBodyBytes();
        if (maxBodyBytes >= 0 && nextBodyBytes > maxBodyBytes) {
            sendHttp2RstStreamCancel(stream, header.streamId());
            session.resetRequestStream(header.streamId());
            return;
        }

        requestStream.appendBody(allocator, aggregate.segment(), dataFragment.offset(), dataFragment.length());
        if (!header.isEndStream()) {
            return;
        }

        Http2RequestStreamState finished = session.takeRequestStream(header.streamId());
        if (finished == null) {
            sendHttp2RstStreamRefused(stream, header.streamId());
            return;
        }
        dispatchHttp2Request(stream, handler, session, finished.streamId(), finished.request(), finished.detachBody());
    }

    private void decodeAndDispatchCompletedHeaderBlock(TransportStream stream,
                                                       HttpHandler handler,
                                                       Http2SessionContext session) {
        boolean requestEndedInHeaders = session.pendingEndStream();
        Http2DecodedRequest decoded = session.decodePendingRequest();
        session.setLastProcessedStreamId(decoded.streamId());

        if (!decoded.valid()) {
            writeHttp2NoBodyResponse(stream, session, decoded.streamId(), HttpStatus.BAD_REQUEST);
            return;
        }

        if (requestEndedInHeaders) {
            dispatchHttp2Request(stream, handler, session, decoded.streamId(), decoded, null);
            return;
        }

        session.openRequestStream(decoded);
    }

    private void dispatchHttp2Request(TransportStream stream,
                                      HttpHandler handler,
                                      Http2SessionContext session,
                                      int streamId,
                                      Http2DecodedRequest decoded,
                                      LoanedBuffer bodyBuffer) {
        try (LoanedBuffer requestBody = bodyBuffer) {
            HttpRequest request = new HttpRequest(
                    decoded.method(),
                    decoded.path(),
                    HttpVersion.HTTP_2,
                    decoded.headers(),
                    requestBody);
            InMemoryHttp2Exchange exchange = new InMemoryHttp2Exchange(request, allocator, encoderRegistry);
            requestDispatcher.dispatch(request, exchange, handler);
            if (!CommunityHttpRequestDispatcher.isResponded(exchange)) {
                exchange.respond(HttpResponse.noBody(HttpStatus.INTERNAL_SERVER_ERROR, HttpVersion.HTTP_2));
            }
            HttpResponse response = exchange.capturedResponse();
            if (response == null) {
                writeHttp2NoBodyResponse(stream, session, streamId, HttpStatus.INTERNAL_SERVER_ERROR);
                return;
            }

            writeHttp2Response(stream, session, streamId, response);
        }
    }

    private void writeHttp2Response(TransportStream stream,
                                    Http2SessionContext session,
                                    int streamId,
                                    HttpResponse response) {
        // PERF-061: per-response reusable outbound carrier sized to MAX frame header + payload.
        // One borrow replaces (1 HEADERS/CONTINUATION + N DATA) per-frame allocations from the
        // pre-PERF-061 path. Frame writers receive a slice of this carrier and serialize the
        // wire image in-place; no per-frame slab borrow + free cycle on the response hot path.
        try (LoanedBuffer headerBlock = allocator.allocateNetwork(HTTP2_MAX_HEADER_BLOCK_BYTES);
             LoanedBuffer outboundFrame = allocator.allocateNetwork(
                     Http2FrameParser.FRAME_HEADER_SIZE + HTTP2_MAX_FRAME_PAYLOAD_BYTES)) {
            long headerBytes = session.encodeResponseHeaders(headerBlock.segment(), response);
            LoanedBuffer bodyBuffer = response.body();
            int bodyBytes = bodyBuffer == null ? 0 : (int) bodyBuffer.size();
            boolean headerEndsStream = bodyBytes == 0;
            writeHttp2HeaderBlockFrames(stream, streamId, headerBlock.segment(), (int) headerBytes,
                    headerEndsStream, outboundFrame);

            if (bodyBuffer != null) {
                try (bodyBuffer) {
                    if (bodyBytes > 0) {
                        writeHttp2DataFrames(stream, streamId, bodyBuffer.segment(), bodyBytes,
                                outboundFrame);
                    }
                }
            }
        }
    }

    private void writeHttp2NoBodyResponse(TransportStream stream,
                                          Http2SessionContext session,
                                          int streamId,
                                          HttpStatus status) {
        writeHttp2Response(stream, session, streamId, HttpResponse.noBody(status, HttpVersion.HTTP_2));
    }

    private void writeHttp2HeaderBlockFrames(TransportStream stream,
                                             int streamId,
                                             MemorySegment headerBlock,
                                             int headerLength,
                                             boolean endStream,
                                             LoanedBuffer outboundFrame) {
        int written = 0;
        boolean firstFrame = true;
        while (written < headerLength) {
            int chunk = Math.min(HTTP2_MAX_FRAME_PAYLOAD_BYTES, headerLength - written);
            boolean last = (written + chunk) == headerLength;
            int type = firstFrame ? Http2FrameType.HEADERS.code() : Http2FrameType.CONTINUATION.code();
            int flags = last ? HTTP2_FLAG_END_HEADERS : 0;
            if (firstFrame && endStream) {
                flags |= HTTP2_FLAG_END_STREAM;
            }
            writeHttp2Frame(stream, type, flags, streamId, headerBlock, written, chunk, outboundFrame);
            written += chunk;
            firstFrame = false;
        }
    }

    private void writeHttp2DataFrames(TransportStream stream,
                                      int streamId,
                                      MemorySegment body,
                                      int bodyLength,
                                      LoanedBuffer outboundFrame) {
        int written = 0;
        while (written < bodyLength) {
            int chunk = Math.min(HTTP2_MAX_FRAME_PAYLOAD_BYTES, bodyLength - written);
            boolean endStream = (written + chunk) == bodyLength;
            int flags = endStream ? HTTP2_FLAG_END_STREAM : 0;
            writeHttp2Frame(stream, Http2FrameType.DATA.code(), flags, streamId, body, written, chunk,
                    outboundFrame);
            written += chunk;
        }
    }

    private void writeHttp2Frame(TransportStream stream,
                                 int frameType,
                                 int flags,
                                 int streamId,
                                 MemorySegment payloadSource,
                                 int payloadOffset,
                                 int payloadLength,
                                 LoanedBuffer outboundFrame) {
        // PERF-061: serialize the frame wire image into the per-response reusable carrier.
        // No allocator borrow on the per-frame path; the carrier is sized to one full frame
        // (header + max payload) and is reused across every frame in the response.
        Http2FrameEncoder.writeHeader(outboundFrame.segment(), 0, payloadLength, frameType, flags, streamId);
        if (payloadLength > 0) {
            MemorySegment.copy(
                    payloadSource,
                    payloadOffset,
                    outboundFrame.segment(),
                    Http2FrameParser.FRAME_HEADER_SIZE,
                    payloadLength);
        }
        long written = Http2FrameParser.FRAME_HEADER_SIZE + (long) payloadLength;
        outboundFrame.setSize(written);
        stream.write(outboundFrame.segment(), (int) written);
    }

    private void sendHttp2PingAck(TransportStream stream,
                                  LoanedBuffer aggregate,
                                  long payloadOffset) {
        try (LoanedBuffer outbound = allocator.allocateNetwork(H2_HANDSHAKE_BUFFER_BYTES)) {
            Http2FrameEncoder.writeHeader(
                    outbound.segment(),
                    0,
                    8,
                    Http2FrameType.PING.code(),
                    0x01,
                    0);
            MemorySegment.copy(
                    aggregate.segment(),
                    payloadOffset,
                    outbound.segment(),
                    Http2FrameParser.FRAME_HEADER_SIZE,
                    8);
            long written = Http2FrameParser.FRAME_HEADER_SIZE + 8L;
            outbound.setSize(written);
            stream.write(outbound.segment(), (int) written);
        }
    }

    private void sendHttp2ServerSettings(TransportStream stream) {
        try (LoanedBuffer outbound = allocator.allocateNetwork(H2_HANDSHAKE_BUFFER_BYTES)) {
            long written = Http2FrameEncoder.writeSettings(outbound.segment(), 0, 0, false);
            outbound.setSize(written);
            stream.write(outbound.segment(), (int) written);
        }
    }

    private void sendHttp2SettingsAck(TransportStream stream) {
        try (LoanedBuffer outbound = allocator.allocateNetwork(H2_HANDSHAKE_BUFFER_BYTES)) {
            long written = Http2FrameEncoder.writeSettings(outbound.segment(), 0, 0, true);
            outbound.setSize(written);
            stream.write(outbound.segment(), (int) written);
        }
    }

    private void sendHttp2RstStreamRefused(TransportStream stream, int streamId) {
        try (LoanedBuffer outbound = allocator.allocateNetwork(H2_HANDSHAKE_BUFFER_BYTES)) {
            long written = Http2FrameEncoder.writeRstStream(
                    outbound.segment(),
                    0,
                    streamId,
                    Http2ErrorCode.REFUSED_STREAM.code());
            outbound.setSize(written);
            stream.write(outbound.segment(), (int) written);
        }
    }

    private void sendHttp2RstStreamCancel(TransportStream stream, int streamId) {
        try (LoanedBuffer outbound = allocator.allocateNetwork(H2_HANDSHAKE_BUFFER_BYTES)) {
            long written = Http2FrameEncoder.writeRstStream(
                    outbound.segment(),
                    0,
                    streamId,
                    Http2ErrorCode.CANCEL.code());
            outbound.setSize(written);
            stream.write(outbound.segment(), (int) written);
        }
    }

    private void sendHttp2GoAwayNoError(TransportStream stream) {
        sendHttp2GoAway(stream, 0, Http2ErrorCode.NO_ERROR);
    }

    private void sendHttp2GoAway(TransportStream stream, int lastStreamId, Http2ErrorCode errorCode) {
        try (LoanedBuffer outbound = allocator.allocateNetwork(H2_HANDSHAKE_BUFFER_BYTES)) {
            long written = Http2FrameEncoder.writeGoAway(
                    outbound.segment(), 0, lastStreamId, errorCode.code());
            outbound.setSize(written);
            stream.write(outbound.segment(), (int) written);
        }
    }

    private HeaderFragment extractHeadersFragment(LoanedBuffer aggregate,
                                                  long payloadOffset,
                                                  Http2FrameParser.FrameHeader header) {
        long fragmentOffset = payloadOffset;
        int fragmentLength = header.length();
        int padLength = 0;

        if (header.isPadded()) {
            if (fragmentLength < HTTP2_PADDED_LENGTH_FIELD_BYTES) {
                throw new IllegalStateException("Invalid PADDED HEADERS frame");
            }
            padLength = aggregate.segment().get(ValueLayout.JAVA_BYTE, fragmentOffset) & 0xFF;
            fragmentOffset += HTTP2_PADDED_LENGTH_FIELD_BYTES;
            fragmentLength -= HTTP2_PADDED_LENGTH_FIELD_BYTES;
        }

        if (header.isPriority()) {
            if (fragmentLength < HTTP2_PRIORITY_FIELD_BYTES) {
                throw new IllegalStateException("Invalid PRIORITY section in HEADERS frame");
            }
            fragmentOffset += HTTP2_PRIORITY_FIELD_BYTES;
            fragmentLength -= HTTP2_PRIORITY_FIELD_BYTES;
        }

        if (padLength > fragmentLength) {
            throw new IllegalStateException("Invalid HEADERS padding");
        }
        fragmentLength -= padLength;
        return new HeaderFragment(fragmentOffset, fragmentLength);
    }

    private HeaderFragment extractDataFragment(LoanedBuffer aggregate,
                                               long payloadOffset,
                                               Http2FrameParser.FrameHeader header) {
        long fragmentOffset = payloadOffset;
        int fragmentLength = header.length();
        int padLength = 0;

        if ((header.flags() & HTTP2_FLAG_PADDED) != 0) {
            if (fragmentLength < HTTP2_PADDED_LENGTH_FIELD_BYTES) {
                throw new IllegalStateException("Invalid PADDED DATA frame");
            }
            padLength = aggregate.segment().get(ValueLayout.JAVA_BYTE, fragmentOffset) & 0xFF;
            fragmentOffset += HTTP2_PADDED_LENGTH_FIELD_BYTES;
            fragmentLength -= HTTP2_PADDED_LENGTH_FIELD_BYTES;
        }

        if (padLength > fragmentLength) {
            throw new IllegalStateException("Invalid DATA padding");
        }
        fragmentLength -= padLength;
        return new HeaderFragment(fragmentOffset, fragmentLength);
    }

    private int readHttp2Bytes(TransportStream stream,
                               ProcessingState state,
                               long bufferedBytes) {
        long requiredCapacity = Math.min(maxAggregateBytes, bufferedBytes + readChunkBytes);
        state.ensureAggregateCapacity(allocator, requiredCapacity, maxAggregateBytes);
        LoanedBuffer aggregate = state.aggregate();

        int remaining = (int) Math.min(aggregate.capacity() - bufferedBytes, maxAggregateBytes - bufferedBytes);
        int chunk = Math.min(readChunkBytes, remaining);
        return stream.read(aggregate.segment().asSlice(bufferedBytes, chunk), chunk);
    }

    private record HeaderFragment(long offset, int length) {
    }

    private static final class Http2SessionContext implements AutoCloseable {
        private final HpackDynamicTable encodeTable;
        private final HpackDecoder decoder;
        private final HpackEncoder encoder;
        private final Http2FrameCodec codec;
        private final Http2HeaderBlockAssembler assembler;
        private final Map<Integer, Http2RequestStreamState> requestStreams;
        private boolean pendingEndStream;
        private int lastProcessedStreamId;

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
            this.pendingEndStream = false;
            this.lastProcessedStreamId = 0;
        }

        private static Http2SessionContext create(MemoryAllocator allocator) {
            HpackDynamicTable decodeTable = new HpackDynamicTable(HTTP2_MAX_DYNAMIC_TABLE_SIZE);
            HpackDynamicTable encodeTable = new HpackDynamicTable(HTTP2_MAX_DYNAMIC_TABLE_SIZE);
            HpackDecoder decoder = new HpackDecoder(decodeTable, allocator, HTTP2_MAX_HEADER_LIST_SIZE);
            HpackEncoder encoder = new HpackEncoder(encodeTable, allocator, false);
            Http2FrameCodec codec = new Http2FrameCodec();
            Http2HeaderBlockAssembler assembler = new Http2HeaderBlockAssembler(allocator);
            return new Http2SessionContext(encodeTable, decoder, encoder, codec, assembler);
        }

        private void applyPeerHeaderTableSize(long headerTableSize) {
            encodeTable.setMaxSize(headerTableSize);
        }

        private Http2FrameCodec codec() {
            return codec;
        }

        private void applyPeerMaxFrameSize(int maxFrameSize) {
            codec.setMaxFrameSize(maxFrameSize);
        }

        private boolean isAwaitingContinuation() {
            return assembler.isAwaitingContinuation();
        }

        private void setPendingEndStream(boolean endStream) {
            this.pendingEndStream = endStream;
        }

        private boolean pendingEndStream() {
            return pendingEndStream;
        }

        private void beginHeaders(Http2FrameParser.FrameHeader header,
                                  MemorySegment payload,
                                  long dataOffset,
                                  int dataLength) {
            assembler.beginHeaders(header, payload, dataOffset, dataLength);
        }

        private void appendContinuation(Http2FrameParser.FrameHeader header,
                                        MemorySegment payload,
                                        long dataOffset,
                                        int dataLength) {
            assembler.appendContinuation(header, payload, dataOffset, dataLength);
        }

        private void validateContinuationMode(Http2FrameParser.FrameHeader header) {
            assembler.validateContinuationMode(header);
        }

        private Http2DecodedRequest decodePendingRequest() {
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

        private void openRequestStream(Http2DecodedRequest request) {
            Http2RequestStreamState previous = requestStreams.put(
                    request.streamId(),
                    new Http2RequestStreamState(request.streamId(), request));
            if (previous != null) {
                previous.close();
            }
        }

        private Http2RequestStreamState requestStream(int streamId) {
            return requestStreams.get(streamId);
        }

        private Http2RequestStreamState takeRequestStream(int streamId) {
            return requestStreams.remove(streamId);
        }

        private void resetRequestStream(int streamId) {
            Http2RequestStreamState removed = requestStreams.remove(streamId);
            if (removed != null) {
                removed.close();
            }
        }

        private long encodeResponseHeaders(MemorySegment target, HttpResponse response) {
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

        private void clearPendingIfStreamMatches(int streamId) {
            if (assembler.currentStreamId() == streamId) {
                assembler.reset();
                pendingEndStream = false;
            }
        }

        private int lastProcessedStreamId() {
            return lastProcessedStreamId;
        }

        private void setLastProcessedStreamId(int lastProcessedStreamId) {
            this.lastProcessedStreamId = Math.max(this.lastProcessedStreamId, lastProcessedStreamId);
        }

        private static boolean isConnectionSpecificHeader(String headerName) {
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

    private static final class PendingRequestHeaders {
        private String methodToken;
        private String path;
        private boolean valid = true;
        private boolean sawRegularHeader;
        private final List<HttpHeader> requestHeaders = new ArrayList<>();

        private void accept(String name, String value) {
            if (!valid) {
                return;
            }
            if (name.startsWith(":")) {
                acceptPseudoHeader(name, value);
                return;
            }
            sawRegularHeader = true;
            if (Http2SessionContext.isConnectionSpecificHeader(name)) {
                valid = false;
                return;
            }
            requestHeaders.add(new HttpHeader(name, value));
        }

        private void invalidate() {
            valid = false;
        }

        private Http2DecodedRequest toDecodedRequest(int streamId) {
            HttpMethod method = parseHttp2Method(methodToken);
            String resolvedPath = path == null ? "" : path;
            boolean requestValid = valid && method != null && !resolvedPath.isEmpty();
            return new Http2DecodedRequest(streamId, method, resolvedPath, List.copyOf(requestHeaders), requestValid);
        }

        private void acceptPseudoHeader(String name, String value) {
            if (sawRegularHeader) {
                valid = false;
                return;
            }
            switch (name) {
                case ":method" -> methodToken = value;
                case ":path" -> path = value;
                case ":authority", ":scheme" -> {
                    // Accepted but not required for this processing path.
                }
                default -> valid = false;
            }
        }

        private static HttpMethod parseHttp2Method(String methodToken) {
            if (methodToken == null || methodToken.isBlank()) {
                return null;
            }
            try {
                return HttpMethod.valueOf(methodToken);
            } catch (IllegalArgumentException _) {
                return null;
            }
        }
    }
}
