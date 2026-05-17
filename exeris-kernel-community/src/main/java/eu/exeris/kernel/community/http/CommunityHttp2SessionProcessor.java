/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.core.http.http2.Http2ErrorCode;
import eu.exeris.kernel.core.http.http2.Http2FrameEncoder;
import eu.exeris.kernel.core.http.http2.Http2FrameParser;
import eu.exeris.kernel.core.http.http2.Http2FrameType;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpResponseBodyEncoderRegistry;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.transport.TransportStream;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

// QA-018a extracted 4 seams: Http2SessionContext (HPACK + assembler + per-stream state),
// PendingRequestHeaders (HPACK-decode accumulator), CommunityHttp2ControlFrames (SETTINGS/PING/
// RST_STREAM/GOAWAY writers), and CommunityHttp2FrameFragments (pad/priority extraction).
// Residual processor retains the frame-loop, frame dispatcher, headers/data frame handlers,
// response writer, and ingress read loop.
// Retained suppressions:
// - GodClass / TooManyMethods: residual WMC=72; frame-loop + dispatcher + frame handlers +
//   response writer remain cohesive; further split deferred.
// - AvoidCatchingGenericException: frame-loop catches RuntimeException to surface PROTOCOL_ERROR via GOAWAY.
// - CloseResource: LoanedBuffer ownership transfers via response.body() pipeline.
// - CyclomaticComplexity: frame-dispatcher switch + frame loop are intrinsically cohesive.
@SuppressWarnings({
        "PMD.GodClass",
        "PMD.TooManyMethods",
        "PMD.AvoidCatchingGenericException",
        "PMD.CloseResource",
        "PMD.CyclomaticComplexity"
})
final class CommunityHttp2SessionProcessor {

    private static final System.Logger LOG =
            System.getLogger(CommunityHttp2SessionProcessor.class.getName());

    private static final String CRLF = "\r\n";
    private static final long HTTP2_FRAME_LOOP_INVALID = -1L;
    private static final long HTTP2_FRAME_LOOP_STOP = -2L;
    private static final int HTTP2_MAX_HEADER_BLOCK_BYTES = 65_536;
    private static final int HTTP2_MAX_FRAME_PAYLOAD_BYTES = 16 * 1024;
    private static final int HTTP2_FLAG_END_STREAM = 0x01;
    private static final int HTTP2_FLAG_END_HEADERS = 0x04;

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
        CommunityHttp2ControlFrames.sendServerSettings(allocator, stream);
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
        CommunityHttp2ControlFrames.sendServerSettings(allocator, stream);
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
                CommunityHttp2ControlFrames.sendGoAway(
                        allocator, stream, session.lastProcessedStreamId(), Http2ErrorCode.PROTOCOL_ERROR);
                return;
            }

            long unreadBytes = CommunityHttpBufferOps.compactUnreadBytes(aggregate, bufferedBytes, offset);
            offset = 0;
            bufferedBytes = unreadBytes;

            if (bufferedBytes >= maxAggregateBytes) {
                CommunityHttp2ControlFrames.sendGoAway(
                        allocator, stream, session.lastProcessedStreamId(), Http2ErrorCode.FRAME_SIZE_ERROR);
                return;
            }

            int read = readHttp2Bytes(stream, state, bufferedBytes);
            if (read < 0) {
                CommunityHttp2ControlFrames.sendGoAwayNoError(allocator, stream);
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
                    session.applyPeerSettings(aggregate, payloadOffset, header.length());
                    CommunityHttp2ControlFrames.sendSettingsAck(allocator, stream);
                }
                yield true;
            }
            case PING -> {
                if (header.streamId() != 0 || header.length() != 8) {
                    throw new IllegalStateException("Invalid PING frame");
                }
                if (!header.isAck()) {
                    CommunityHttp2ControlFrames.sendPingAck(allocator, stream, aggregate, payloadOffset);
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
            CommunityHttp2ControlFrames.sendGoAway(
                    allocator, stream, session.lastProcessedStreamId(), Http2ErrorCode.PROTOCOL_ERROR);
            return false;
        }
        CommunityHttp2FrameFragments.Fragment fragment =
                CommunityHttp2FrameFragments.extractHeadersFragment(aggregate, payloadOffset, header);
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
            CommunityHttp2ControlFrames.sendRstStreamRefused(allocator, stream, header.streamId());
            return;
        }

        CommunityHttp2FrameFragments.Fragment dataFragment =
                CommunityHttp2FrameFragments.extractDataFragment(aggregate, payloadOffset, header);
        int nextBodyBytes = requestStream.bodyBytes() + dataFragment.length();
        long maxBodyBytes = config.maxRequestBodyBytes();
        if (maxBodyBytes >= 0 && nextBodyBytes > maxBodyBytes) {
            CommunityHttp2ControlFrames.sendRstStreamCancel(allocator, stream, header.streamId());
            session.resetRequestStream(header.streamId());
            return;
        }

        requestStream.appendBody(allocator, aggregate.segment(), dataFragment.offset(), dataFragment.length());
        if (!header.isEndStream()) {
            return;
        }

        Http2RequestStreamState finished = session.takeRequestStream(header.streamId());
        if (finished == null) {
            CommunityHttp2ControlFrames.sendRstStreamRefused(allocator, stream, header.streamId());
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

}
