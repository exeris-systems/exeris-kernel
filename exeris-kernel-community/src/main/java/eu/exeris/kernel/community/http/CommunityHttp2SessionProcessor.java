/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.core.http.http2.Http2ErrorCode;
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
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Community: the HTTP/2 connection frame loop — reads and validates frames off the connection's
 * aggregate buffer, decodes completed HEADERS(+CONTINUATION) blocks via {@link Http2SessionContext},
 * accumulates DATA into the matching {@link Http2RequestStreamState}, and on a complete request
 * dispatches it through {@link CommunityHttpRequestDispatcher} before framing and coalescing the
 * response into one write.
 *
 * <p>Entered from either upgrade path — {@link #handlePriorKnowledge} (the 24-byte connection
 * preface) or {@link #handleUpgrade} (an HTTP/1.1 {@code Upgrade: h2c} request already answered with
 * {@code 101}) — both open one {@link Http2SessionContext} per connection and run the frame loop
 * until a connection-fatal condition — a protocol error, a peer GOAWAY, a Rapid Reset budget trip,
 * the buffered-frame aggregate exceeding its configured capacity, or a read returning end-of-stream —
 * ends it.
 *
 * <p>The frame loop is the connection's only reader. Every write goes through the connection's
 * {@link Http2ConnectionWriter}, which orders it against writes from other threads, and a response
 * that the peer's flow-control windows cannot carry yet continues on a task forked into the
 * connection's {@link Http2Connection} scope while the loop goes on reading the frames that open the
 * windows.
 */
// QA-018a extracted four collaborators: Http2SessionContext holds the HPACK decoder, the assembler and
// the per-stream request state; PendingRequestHeaders accumulates a decoded header block;
// CommunityHttp2ControlFrames writes the SETTINGS, PING, RST_STREAM and GOAWAY frames; and
// CommunityHttp2FrameFragments extracts padding and priority fields. The processor keeps the frame loop,
// the frame dispatcher, the headers and data handlers, the response hand-off and the ingress read loop.
// Response serialization and the HPACK encoder live in Http2ConnectionWriter, and the per-connection
// state is held by Http2Connection.
// Retained suppressions:
// - TooManyMethods: frame-loop + dispatcher + frame handlers + response hand-off remain cohesive;
//   further split deferred.
// - UseTryWithResources: the response body changes owner when a continuation task takes it, so it is
//   closed here only when nothing took it.
// - AvoidCatchingGenericException: frame-loop catches RuntimeException to surface PROTOCOL_ERROR via GOAWAY.
// - CloseResource: LoanedBuffer ownership transfers via response.body() pipeline.
// - CyclomaticComplexity: frame-dispatcher switch + frame loop are intrinsically cohesive.
// - CouplingBetweenObjects (HTTP-112): the processor coordinates the full HTTP/2 frame
//   pipeline — types include the session context, four CommunityHttp2* sibling utilities,
//   five Core http2/hpack types, and the StreamAdmission enum. Coupling=21 (threshold 20)
//   reflects this orchestration role; QA-018a already extracted everything semantically
//   splittable, so further splitting would fragment the frame-loop entry point.
@SuppressWarnings({
        "PMD.TooManyMethods",
        "PMD.UseTryWithResources",
        "PMD.AvoidCatchingGenericException",
        "PMD.CloseResource",
        "PMD.CyclomaticComplexity",
        "PMD.CouplingBetweenObjects"
})
final class CommunityHttp2SessionProcessor {

    private static final System.Logger LOG =
            System.getLogger(CommunityHttp2SessionProcessor.class.getName());

    private static final String CRLF = "\r\n";
    private static final long HTTP2_FRAME_LOOP_INVALID = -1L;
    private static final long HTTP2_FRAME_LOOP_STOP = -2L;
    private static final long HTTP2_FRAME_LOOP_COMPRESSION_ERROR = -3L;
    private static final int WINDOW_UPDATE_PAYLOAD_BYTES = 4;
    private static final ValueLayout.OfInt BIG_ENDIAN_INT =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

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
        try (Http2SessionContext session = Http2SessionContext.create(allocator, config);
             Http2Connection connection = openConnection(stream, handler, session)) {
            CommunityHttp2ControlFrames.sendServerSettings(
                    allocator, connection.writer(), config.maxHeaderListSize());
            processBufferedHttp2Frames(connection, state, totalBytes, initialFrameOffset);
        }
    }

    /* default */ void handleUpgrade(ReadResult readResult,
                       TransportStream stream,
                       HttpHandler handler,
                       ProcessingState state) {
        LoanedBuffer aggregate = state.aggregate();
        writeHttp11UpgradeResponse(stream);
        long bufferedHttp2Bytes = CommunityHttpBufferOps.retainUnreadBytes(aggregate, readResult.consumedBytes());
        try (Http2SessionContext session = Http2SessionContext.create(allocator, config);
             Http2Connection connection = openConnection(stream, handler, session)) {
            CommunityHttp2ControlFrames.sendServerSettings(
                    allocator, connection.writer(), config.maxHeaderListSize());
            processBufferedHttp2Frames(connection, state, bufferedHttp2Bytes, 0);
        }
    }

    private Http2Connection openConnection(TransportStream stream, HttpHandler handler,
                                           Http2SessionContext session) {
        Http2ConnectionWriter writer = Http2ConnectionWriter.create(
                stream, allocator, session.maxConcurrentStreams(),
                CommunityHttpStreamDispatcher.creditWindowBytes());
        return new Http2Connection(stream, handler, session, writer);
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

    private void processBufferedHttp2Frames(Http2Connection connection,
                                            ProcessingState state,
                                            long initialBytes,
                                            long initialFrameOffset) {
        Http2SessionContext session = connection.session();
        Http2ConnectionWriter writer = connection.writer();
        long bufferedBytes = initialBytes;
        long offset = initialFrameOffset;

        while (true) {
            LoanedBuffer aggregate = state.aggregate();
            offset = processAvailableHttp2Frames(connection, aggregate, bufferedBytes, offset);
            if (offset == HTTP2_FRAME_LOOP_STOP) {
                return;
            }
            if (offset == HTTP2_FRAME_LOOP_INVALID) {
                CommunityHttp2ControlFrames.sendGoAway(
                        allocator, writer, session.lastProcessedStreamId(), Http2ErrorCode.PROTOCOL_ERROR);
                return;
            }

            if (offset == HTTP2_FRAME_LOOP_COMPRESSION_ERROR) {
                CommunityHttp2ControlFrames.sendGoAway(
                        allocator, writer, session.lastProcessedStreamId(), Http2ErrorCode.COMPRESSION_ERROR);
                return;
            }

            long unreadBytes = CommunityHttpBufferOps.compactUnreadBytes(aggregate, bufferedBytes, offset);
            offset = 0;
            bufferedBytes = unreadBytes;

            if (bufferedBytes >= maxAggregateBytes) {
                CommunityHttp2ControlFrames.sendGoAway(
                        allocator, writer, session.lastProcessedStreamId(), Http2ErrorCode.FRAME_SIZE_ERROR);
                return;
            }

            int read = readHttp2Bytes(connection.transport(), state, bufferedBytes);
            if (read < 0) {
                CommunityHttp2ControlFrames.sendGoAwayNoError(allocator, writer);
                return;
            }
            if (read > 0) {
                bufferedBytes += read;
                state.aggregate().setSize(bufferedBytes);
            }
        }
    }

    private long processAvailableHttp2Frames(Http2Connection connection,
                                             LoanedBuffer aggregate,
                                             long bufferedBytes,
                                             long startOffset) {
        Http2SessionContext session = connection.session();
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
                if (!handleHttp2Frame(connection, aggregate, payloadOffset, header)) {
                    return HTTP2_FRAME_LOOP_STOP;
                }
                offset = frameEnd;
            } catch (Http2CompressionException _) {
                return HTTP2_FRAME_LOOP_COMPRESSION_ERROR;
            } catch (RuntimeException _) {
                return HTTP2_FRAME_LOOP_INVALID;
            }
        }
        return offset;
    }

    private boolean handleHttp2Frame(Http2Connection connection,
                                     LoanedBuffer aggregate,
                                     long payloadOffset,
                                     Http2FrameParser.FrameHeader header) {
        Http2FrameType frameType = header.frameType();
        if (frameType == null) {
            return true;
        }
        Http2ConnectionWriter writer = connection.writer();

        return switch (frameType) {
            case SETTINGS -> handleSettingsFrame(connection, aggregate, payloadOffset, header);
            case PING -> {
                if (header.streamId() != 0 || header.length() != 8) {
                    throw new IllegalStateException("Invalid PING frame");
                }
                if (!header.isAck()) {
                    CommunityHttp2ControlFrames.sendPingAck(allocator, writer, aggregate, payloadOffset);
                }
                yield true;
            }
            case HEADERS -> handleHttp2HeadersFrame(connection, aggregate, payloadOffset, header);
            case CONTINUATION -> {
                handleHttp2ContinuationFrame(connection, aggregate, payloadOffset, header);
                yield true;
            }
            case DATA -> {
                handleHttp2DataFrame(connection, aggregate, payloadOffset, header);
                yield true;
            }
            case WINDOW_UPDATE -> handleWindowUpdateFrame(connection, aggregate, payloadOffset, header);
            case GOAWAY -> false;
            case RST_STREAM -> handleRstStreamFrame(connection, header);
            default -> true;
        };
    }

    private boolean handleSettingsFrame(Http2Connection connection,
                                        LoanedBuffer aggregate,
                                        long payloadOffset,
                                        Http2FrameParser.FrameHeader header) {
        Http2SessionContext session = connection.session();
        Http2ConnectionWriter writer = connection.writer();
        if (header.streamId() != 0) {
            throw new IllegalStateException("Invalid SETTINGS stream");
        }
        if (header.isAck()) {
            return true;
        }
        Http2ErrorCode settingsError =
                session.applyPeerSettings(aggregate, payloadOffset, header.length(), writer);
        if (settingsError != Http2ErrorCode.NO_ERROR) {
            CommunityHttp2ControlFrames.sendGoAway(
                    allocator, writer, session.lastProcessedStreamId(), settingsError);
            return false;
        }
        CommunityHttp2ControlFrames.sendSettingsAck(allocator, writer);
        return true;
    }

    private boolean handleRstStreamFrame(Http2Connection connection, Http2FrameParser.FrameHeader header) {
        Http2SessionContext session = connection.session();
        Http2ConnectionWriter writer = connection.writer();
        session.clearPendingIfStreamMatches(header.streamId());
        session.resetRequestStream(header.streamId());
        writer.resetByPeer(header.streamId());
        // CVE-2023-44487 (Rapid Reset): each freed slot lets the peer re-open without
        // ever hitting SETTINGS_MAX_CONCURRENT_STREAMS, so the cap alone is no defense.
        // The net reset budget trips GOAWAY(ENHANCE_YOUR_CALM) and stops the frame loop.
        if (session.recordInboundRstStream()) {
            Http2RapidResetFloodEvent.emit(
                    session.rapidResetCount(), session.lastProcessedStreamId());
            CommunityHttp2ControlFrames.sendGoAway(
                    allocator, writer, session.lastProcessedStreamId(),
                    Http2ErrorCode.ENHANCE_YOUR_CALM);
            return false;
        }
        return true;
    }

    /**
     * Applies a WINDOW_UPDATE (RFC 9113 §6.9). A stream the server no longer tracks is ignored
     * rather than treated as a connection error, because the h2c upgrade implicitly opens stream 1,
     * which this session never serves and which a client may still credit. A stream whose request is
     * still arriving has its credit held until the response stream exists.
     *
     * @return {@code false} when the frame ended the connection
     */
    private boolean handleWindowUpdateFrame(Http2Connection connection,
                                            LoanedBuffer aggregate,
                                            long payloadOffset,
                                            Http2FrameParser.FrameHeader header) {
        Http2SessionContext session = connection.session();
        Http2ConnectionWriter writer = connection.writer();
        if (header.length() != WINDOW_UPDATE_PAYLOAD_BYTES) {
            CommunityHttp2ControlFrames.sendGoAway(
                    allocator, writer, session.lastProcessedStreamId(), Http2ErrorCode.FRAME_SIZE_ERROR);
            return false;
        }
        int increment = aggregate.segment().get(BIG_ENDIAN_INT, payloadOffset) & 0x7FFF_FFFF;
        int streamId = header.streamId();
        if (streamId == 0) {
            Http2ErrorCode error = writer.applyConnectionWindowUpdate(increment);
            if (error != Http2ErrorCode.NO_ERROR) {
                CommunityHttp2ControlFrames.sendGoAway(
                        allocator, writer, session.lastProcessedStreamId(), error);
                return false;
            }
            return true;
        }
        // The writer's table is searched first: it is a scan of an array, where the request table is
        // a map keyed by a boxed id, and a stream is never in both.
        if (writer.applyStreamWindowUpdate(streamId, increment)) {
            return true;
        }
        Http2RequestStreamState requestStream = session.requestStream(streamId);
        if (requestStream == null) {
            return true;
        }
        if (increment == 0) {
            CommunityHttp2ControlFrames.sendRstStream(
                    allocator, writer, streamId, Http2ErrorCode.PROTOCOL_ERROR);
            session.resetRequestStream(streamId);
        } else if (!requestStream.addSendWindowIncrement(increment, writer.peerInitialWindowSize())) {
            CommunityHttp2ControlFrames.sendRstStream(
                    allocator, writer, streamId, Http2ErrorCode.FLOW_CONTROL_ERROR);
            session.resetRequestStream(streamId);
        }
        return true;
    }

    private boolean handleHttp2HeadersFrame(Http2Connection connection,
                                            LoanedBuffer aggregate,
                                            long payloadOffset,
                                            Http2FrameParser.FrameHeader header) {
        Http2SessionContext session = connection.session();
        Http2ConnectionWriter writer = connection.writer();
        if (header.streamId() <= 0) {
            throw new IllegalStateException("Invalid HEADERS stream");
        }
        if (session.isAwaitingContinuation()) {
            CommunityHttp2ControlFrames.sendGoAway(
                    allocator, writer, session.lastProcessedStreamId(), Http2ErrorCode.PROTOCOL_ERROR);
            return false;
        }
        // HTTP-112: RFC 7540 §5.1.1 stream-id monotonicity + §5.1.2
        // SETTINGS_MAX_CONCURRENT_STREAMS cap. INVALID_ID is a connection-fatal protocol
        // error (GOAWAY + close loop); OVER_CAP is a per-stream refusal that keeps the
        // connection open for other streams (RST_STREAM REFUSED_STREAM + skip body). The cap
        // counts the requests still arriving plus the response streams a continuation still holds.
        Http2SessionContext.StreamAdmission admission =
                session.admitClientStreamId(header.streamId(), writer.openStreamCount());
        if (admission == Http2SessionContext.StreamAdmission.REJECT_INVALID_ID) {
            CommunityHttp2ControlFrames.sendGoAway(
                    allocator, writer, session.lastProcessedStreamId(), Http2ErrorCode.PROTOCOL_ERROR);
            return false;
        }
        if (admission == Http2SessionContext.StreamAdmission.REJECT_OVER_CAP) {
            CommunityHttp2ControlFrames.sendRstStreamRefused(allocator, writer, header.streamId());
            return true;
        }
        CommunityHttp2FrameFragments.Fragment fragment =
                CommunityHttp2FrameFragments.extractHeadersFragment(aggregate, payloadOffset, header);
        session.setPendingEndStream(header.isEndStream());
        session.beginHeaders(header, aggregate.segment(), fragment.offset(), fragment.length());
        if (session.isAwaitingContinuation()) {
            return true;
        }
        decodeAndDispatchCompletedHeaderBlock(connection);
        return true;
    }

    private void handleHttp2ContinuationFrame(Http2Connection connection,
                                              LoanedBuffer aggregate,
                                              long payloadOffset,
                                              Http2FrameParser.FrameHeader header) {
        Http2SessionContext session = connection.session();
        session.appendContinuation(header, aggregate.segment(), payloadOffset, header.length());
        if (session.isAwaitingContinuation()) {
            return;
        }
        decodeAndDispatchCompletedHeaderBlock(connection);
    }

    private void handleHttp2DataFrame(Http2Connection connection,
                                      LoanedBuffer aggregate,
                                      long payloadOffset,
                                      Http2FrameParser.FrameHeader header) {
        Http2SessionContext session = connection.session();
        Http2ConnectionWriter writer = connection.writer();
        if (header.streamId() <= 0) {
            throw new IllegalStateException("Invalid DATA stream");
        }

        Http2RequestStreamState requestStream = session.requestStream(header.streamId());
        if (requestStream == null) {
            // DATA after the request ended: a stream the writer still serves is closed (RFC 9113
            // section 5.1) and its task stopped; a stream nobody knows is refused.
            if (!writer.resetStream(header.streamId(), Http2ErrorCode.STREAM_CLOSED)) {
                CommunityHttp2ControlFrames.sendRstStreamRefused(allocator, writer, header.streamId());
            }
            return;
        }

        CommunityHttp2FrameFragments.Fragment dataFragment =
                CommunityHttp2FrameFragments.extractDataFragment(aggregate, payloadOffset, header);
        int nextBodyBytes = requestStream.bodyBytes() + dataFragment.length();
        long maxBodyBytes = config.maxRequestBodyBytes();
        if (maxBodyBytes >= 0 && nextBodyBytes > maxBodyBytes) {
            CommunityHttp2ControlFrames.sendRstStreamCancel(allocator, writer, header.streamId());
            session.resetRequestStream(header.streamId());
            return;
        }

        requestStream.appendBody(allocator, aggregate.segment(), dataFragment.offset(), dataFragment.length());
        if (!header.isEndStream()) {
            return;
        }

        Http2RequestStreamState finished = session.takeRequestStream(header.streamId());
        if (finished == null) {
            CommunityHttp2ControlFrames.sendRstStreamRefused(allocator, writer, header.streamId());
            return;
        }
        dispatchHttp2Request(connection, finished.streamId(), finished.request(), finished.detachBody(),
                finished.pendingSendWindowIncrement());
    }

    private void decodeAndDispatchCompletedHeaderBlock(Http2Connection connection) {
        Http2SessionContext session = connection.session();
        boolean requestEndedInHeaders = session.pendingEndStream();
        Http2DecodedRequest decoded = session.decodePendingRequest();
        session.setLastProcessedStreamId(decoded.streamId());

        if (decoded.headersTooLarge()) {
            writeHttp2NoBodyResponse(connection, decoded.streamId(),
                    HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE);
            return;
        }

        if (!decoded.valid()) {
            writeHttp2NoBodyResponse(connection, decoded.streamId(), HttpStatus.BAD_REQUEST);
            return;
        }

        if (requestEndedInHeaders) {
            dispatchHttp2Request(connection, decoded.streamId(), decoded, null, 0L);
            return;
        }

        session.openRequestStream(decoded);
    }

    private void dispatchHttp2Request(Http2Connection connection,
                                      int streamId,
                                      Http2DecodedRequest decoded,
                                      LoanedBuffer bodyBuffer,
                                      long pendingSendWindowIncrement) {
        // Real work credits the Rapid Reset budget (CVE-2023-44487): a peer interleaving genuine
        // requests with cancels nets out and never trips the flood defense.
        connection.session().recordDispatchedRequest();
        try (LoanedBuffer requestBody = bodyBuffer) {
            HttpRequest request = new HttpRequest(
                    decoded.method(),
                    decoded.path(),
                    HttpVersion.HTTP_2,
                    decoded.headers(),
                    requestBody);
            InMemoryHttp2Exchange exchange =
                    new InMemoryHttp2Exchange(request, connection.transport(), allocator, encoderRegistry);
            requestDispatcher.dispatch(request, exchange, connection.handler());
            if (!CommunityHttpRequestDispatcher.isResponded(exchange)) {
                exchange.respond(HttpResponse.noBody(HttpStatus.INTERNAL_SERVER_ERROR, HttpVersion.HTTP_2));
            }
            HttpResponse response = exchange.capturedResponse();
            if (response == null) {
                writeHttp2NoBodyResponse(connection, streamId, HttpStatus.INTERNAL_SERVER_ERROR);
                return;
            }

            writeHttp2Response(connection, streamId, pendingSendWindowIncrement, response);
        }
    }

    /**
     * Hands the response to the connection's writer, which sends the head and as much of the body as
     * both flow-control windows carry in a single write. A body the windows cannot carry yet is
     * finished by a continuation task, which then owns the body buffer; otherwise the buffer is
     * closed here.
     */
    private void writeHttp2Response(Http2Connection connection,
                                    int streamId,
                                    long pendingSendWindowIncrement,
                                    HttpResponse response) {
        Http2ConnectionWriter writer = connection.writer();
        LoanedBuffer body = response.body();
        boolean handedOff = false;
        try {
            Http2OutboundStream rest = writer.writeRespondOnce(streamId, pendingSendWindowIncrement, response);
            if (rest != null) {
                forkContinuation(connection, rest, body);
                handedOff = true;
            }
        } finally {
            if (!handedOff && body != null) {
                body.close();
            }
        }
    }

    /**
     * Forks the task that sends the rest of a body. The frame loop never waits for credit itself:
     * it has to keep reading the WINDOW_UPDATE that frees the task. A scope that cannot take the
     * task, or a body that is not there, resets the stream instead of leaving it half-written.
     */
    private static void forkContinuation(Http2Connection connection, Http2OutboundStream rest,
                                         LoanedBuffer body) {
        Http2ConnectionWriter writer = connection.writer();
        int total = (int) body.size();
        long from = rest.bodyOffset;
        try {
            connection.streamScope().fork(() -> {
                try (body) {
                    writer.sendBody(rest, body, from, (int) (total - from), true);
                } finally {
                    writer.release(rest);
                }
                return null;
            });
        } catch (RuntimeException forkFailure) {
            body.close();
            writer.resetLocal(rest, Http2ErrorCode.INTERNAL_ERROR);
            writer.release(rest);
            if (LOG.isLoggable(System.Logger.Level.WARNING)) {
                LOG.log(System.Logger.Level.WARNING,
                        "HTTP/2 response continuation could not be forked", forkFailure);
            }
        }
    }

    private void writeHttp2NoBodyResponse(Http2Connection connection,
                                          int streamId,
                                          HttpStatus status) {
        writeHttp2Response(connection, streamId, 0L, HttpResponse.noBody(status, HttpVersion.HTTP_2));
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
