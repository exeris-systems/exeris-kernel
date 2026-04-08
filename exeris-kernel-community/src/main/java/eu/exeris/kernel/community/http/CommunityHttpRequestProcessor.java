/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.persistence.PersistenceSessionBox;
import eu.exeris.kernel.core.http.hpack.HpackDecoder;
import eu.exeris.kernel.core.http.hpack.HpackDynamicTable;
import eu.exeris.kernel.core.http.hpack.HpackEncoder;
import eu.exeris.kernel.core.http.http1.Http1Codec;
import eu.exeris.kernel.core.http.http1.Http1RequestParser;
import eu.exeris.kernel.core.http.http2.Http2ErrorCode;
import eu.exeris.kernel.core.http.http2.Http2FrameCodec;
import eu.exeris.kernel.core.http.http2.Http2FrameEncoder;
import eu.exeris.kernel.core.http.http2.Http2FrameParser;
import eu.exeris.kernel.core.http.http2.Http2FrameType;
import eu.exeris.kernel.core.http.http2.Http2HeaderBlockAssembler;
import eu.exeris.kernel.core.http.jfr.HttpAggregateBufferForcedReleaseEvent;
import eu.exeris.kernel.core.http.jfr.HttpAggregateBufferHeldEvent;
import eu.exeris.kernel.core.security.SecurityInterceptor;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpExchange;
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
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.TransactionIsolation;
import eu.exeris.kernel.spi.security.PrincipalContext;
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
public final class CommunityHttpRequestProcessor {

    /**
     * Request-scoped persistence session box binding.
     * Lazily acquires a JDBC connection on first subsystem access, consolidating connection +
     * transaction state for the duration of an HTTP request.
     * <p>Accessible to subsystems (e.g., graph, persistence) to reuse the request-bound connection.
     */
    public static final ScopedValue<PersistenceSessionBox> REQUEST_SESSION =
            PersistenceSessionBox.REQUEST_SESSION;

    private static final System.Logger LOG =
            System.getLogger(CommunityHttpRequestProcessor.class.getName());
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String HEALTH_PATH = "/health";
    private static final String HEALTH_LIVE_PATH = "/health/live";
    private static final String HEALTH_READY_PATH = "/health/ready";
    private static final String DB_PING_PATH = "/db/ping";
    private static final String DB_ROUNDTRIP_PATH = "/db/roundtrip";
    private static final String SECURE_PATH_PREFIX = "/secure";
    private static final String ADMIN_PATH_PREFIX = "/secure/admin";
    private static final String READ_SCOPE = "security:read";
    private static final String WRITE_SCOPE = "security:write";
    private static final String HEADER_CONTENT_LENGTH = "Content-Length";
    private static final String HEADER_CONNECTION = "Connection";
    private static final String HEADER_UPGRADE = "Upgrade";
    private static final String CRLF = "\r\n";
    private static final String H2C_TOKEN = "h2c";
    private static final String UPGRADE_TOKEN = "upgrade";
    private static final int READ_CHUNK_BYTES = 8 * 1024;
    private static final int MAX_AGGREGATE_BYTES = 1 * 1024 * 1024;
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
    private static final byte[] HTTP2_PRIOR_KNOWLEDGE_PREFACE =
            "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final int H2_HANDSHAKE_BUFFER_BYTES = 64;
    
    // Phase 1C: HTTP buffer lifecycle tracking
    private static final long BUFFER_AGE_WARNING_THRESHOLD_MS = 100;
    private static final double PIPELINED_FRACTION_THRESHOLD = 0.05; // If pipelined < 5%, force release

    private final MemoryAllocator allocator;
    private final HttpResponseBodyEncoderRegistry encoderRegistry;
    private final HttpConfig config;
    private final SecurityInterceptor securityInterceptor;
    private final PersistenceEngine persistenceEngine;

    /* default */ CommunityHttpRequestProcessor(MemoryAllocator allocator,
                                                HttpResponseBodyEncoderRegistry encoderRegistry) {
        this(allocator, encoderRegistry, HttpConfig.defaultServer());
    }

    /* default */ CommunityHttpRequestProcessor(MemoryAllocator allocator,
                                                HttpResponseBodyEncoderRegistry encoderRegistry,
                                                HttpConfig config) {
        this.allocator = Objects.requireNonNull(allocator, "allocator must not be null");
        this.encoderRegistry = Objects.requireNonNull(encoderRegistry, "encoderRegistry must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.securityInterceptor = KernelProviders.SECURITY_PROVIDER.isBound()
                ? new SecurityInterceptor(KernelProviders.SECURITY_PROVIDER.get())
                : null;
        this.persistenceEngine = KernelProviders.PERSISTENCE_ENGINE.isBound()
                ? KernelProviders.PERSISTENCE_ENGINE.get()
                : null;
    }

    // Aggregate buffer lifecycle is intentionally stateful across loop iterations to
    // preserve HTTP/1.1 pipelined leftovers while still releasing when idle.
    /* default */ void process(TransportStream stream, HttpHandler handler) {
        try (stream; ProcessingState state = new ProcessingState()) {
            Http1Codec codec = new Http1Codec();
            boolean continueProcessing = true;
            while (continueProcessing) {
                continueProcessing = processIteration(codec, stream, handler, state);
            }
        } catch (RuntimeException streamError) {
            if (isExpectedStreamClose(streamError)) {
                return;
            }
            if (LOG.isLoggable(System.Logger.Level.WARNING)) {
                LOG.log(System.Logger.Level.WARNING,
                        "Community HTTP stream handling failed", streamError);
            }
        }
    }

    private boolean processIteration(Http1Codec codec,
                                     TransportStream stream,
                                     HttpHandler handler,
                                     ProcessingState state) {
        boolean hadAggregate = state.hasAggregate();
        state.ensureAggregate(allocator, MAX_AGGREGATE_BYTES);
        if (!hadAggregate) {
            state.resetBufferForNewAggregate();
        }

        ReadResult readResult = readRequest(codec, stream, handler, state.aggregate(), state.bufferedBytes());
        if (readResult == null) {
            return false;
        }

        if (isH2cEnabled() && isH2cUpgradeIntent(readResult.headers())) {
            if (LOG.isLoggable(System.Logger.Level.INFO)) {
                LOG.log(System.Logger.Level.INFO,
                        "HTTP/1.1 h2c upgrade requested; switching to HTTP/2 frame-loop mode");
            }
            handleHttp1UpgradeToH2c(readResult, stream, handler, state.aggregate());
            return false;
        }

        trackPipelinedRequest(state);
        handleRequest(readResult, state.aggregate(), stream, handler);

        state.updateBufferedBytes(retainUnreadBytes(state.aggregate(), readResult.consumedBytes()));
        if (!readResult.keepAlive()) {
            return false;
        }

        applyAggregateTelemetryAndRelease(state);
        state.releaseAggregateIfIdle();
        return true;
    }

    private static void trackPipelinedRequest(ProcessingState state) {
        state.recordRequest();
    }

    private static void applyAggregateTelemetryAndRelease(ProcessingState state) {
        long aggregateAgeMs = aggregateAgeMillis(state.aggregateAllocationTimeNs());
        if (!shouldEmitAggregateHeldTelemetry(aggregateAgeMs)) {
            return;
        }

        double pipelinedFraction = pipelinedFraction(state.totalRequestCount(), state.pipelinedRequestCount());
        emitAggregateHeldTelemetry(aggregateAgeMs, state.bufferedBytes(), pipelinedFraction);
        if (shouldForceReleaseAggregate(pipelinedFraction, state.bufferedBytes())) {
            emitForcedReleaseTelemetry(state.bufferedBytes(), pipelinedFraction);
            state.forceReleaseAggregate();
        }
    }

    private void handleRequest(ReadResult readResult,
                               LoanedBuffer aggregate,
                               TransportStream stream,
                               HttpHandler handler) {
        int bodyLength = readResult.bodyLength();
        if (bodyLength <= 0) {
            dispatchRequest(readResult, null, stream, handler);
            return;
        }

        try (LoanedBuffer bodyBuffer = allocator.allocateNetwork(bodyLength)) {
            MemorySegment.copy(
                    aggregate.segment(), readResult.bodyStart(),
                    bodyBuffer.segment(), 0,
                    bodyLength);
            bodyBuffer.setSize(bodyLength);
            dispatchRequest(readResult, bodyBuffer, stream, handler);
        }
    }

    private static long aggregateAgeMillis(long aggregateAllocationTimeNs) {
        return (System.nanoTime() - aggregateAllocationTimeNs) / 1_000_000;
    }

    private static boolean shouldEmitAggregateHeldTelemetry(long aggregateAgeMs) {
        return aggregateAgeMs > BUFFER_AGE_WARNING_THRESHOLD_MS;
    }

    private static double pipelinedFraction(long totalRequestCount, long pipelinedRequestCount) {
        return totalRequestCount > 0
                ? (double) pipelinedRequestCount / totalRequestCount
                : 0.0;
    }

    private static void emitAggregateHeldTelemetry(long aggregateAgeMs,
                                                   long bufferedBytes,
                                                   double pipelinedFraction) {
        HttpAggregateBufferHeldEvent.emit(
                aggregateAgeMs,
                (int) bufferedBytes,
                MAX_AGGREGATE_BYTES,
                pipelinedFraction
        );
    }

    private static boolean shouldForceReleaseAggregate(double pipelinedFraction, long bufferedBytes) {
        return pipelinedFraction < PIPELINED_FRACTION_THRESHOLD && bufferedBytes == 0;
    }

    private static void emitForcedReleaseTelemetry(long bufferedBytes, double pipelinedFraction) {
        HttpAggregateBufferForcedReleaseEvent.emit(
                "low_pipelined_fraction",
                (int) bufferedBytes,
                PIPELINED_FRACTION_THRESHOLD,
                pipelinedFraction
        );
    }

    private static boolean isExpectedStreamClose(RuntimeException streamError) {
        Throwable current = streamError;
        for (int depth = 0; depth < 4 && current != null; depth++) {
            if (current instanceof IllegalStateException
                    && "Stream is closed".equals(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void dispatchRequest(ReadResult readResult,
                                 LoanedBuffer bodyBuffer,
                                 TransportStream stream,
                                 HttpHandler handler) {
        HttpRequest request = new HttpRequest(
                readResult.method(),
                readResult.path(),
                readResult.version(),
                readResult.headers(),
                bodyBuffer);

        CommunityHttpExchange exchange = new CommunityHttpExchange(
                request, stream, allocator, readResult.keepAlive(), encoderRegistry);

        dispatchRequest(request, exchange, handler);
    }

    private void dispatchRequest(HttpRequest request,
                                 HttpExchange exchange,
                                 HttpHandler handler) {
        String path = request.path();
        HttpMethod method = request.method();

        // Borrowed from KernelProviders binding; this scope must not close provider-owned engine.
        PersistenceEngine engine = resolvePersistenceEngine();

        // NEW: Admission control gate — check if persistence layer can service this request
        // to prevent thread park storms when pool is saturated
        if (engine != null) {
            try {
                if (!engine.canServiceRequest()) {
                    // Pool saturated: apply backpressure with 503 Service Unavailable
                    exchange.respond(backpressureResponse(request.version(), "1"));
                    return; // Don't create session box
                }
                } catch (RuntimeException _) {
                // Engine shutting down or in error state
                exchange.respond(backpressureResponse(request.version(), "10"));
                return;
            }
        }

        // EXISTING: Create session box and dispatch
        if (requiresAdmission(path)) {
            if (securityInterceptor == null || !interceptRequest(request, () ->
                    handleAuthorizedRequest(path, method, request, exchange, handler, engine))) {
                exchange.respond(HttpResponse.noBody(HttpStatus.UNAUTHORIZED, request.version()));
                return;
            }
        } else {
            handleWithinRequestSession(method, request, exchange, handler, engine);
        }

        if (!isResponded(exchange)) {
            exchange.respond(
                    HttpResponse.noBody(HttpStatus.INTERNAL_SERVER_ERROR, request.version()));
        }
    }

    private boolean interceptRequest(HttpRequest request, Runnable admittedHandler) {
        if (securityInterceptor == null) {
            return false;
        }

        LoanedBuffer tokenBuffer = createBearerTokenBuffer(request.headers());
        if (tokenBuffer == null) {
            return false;
        }

        try (tokenBuffer) {
            return securityInterceptor.intercept(tokenBuffer, admittedHandler);
        }
    }

    private void handleAuthorizedRequest(String path,
                                         HttpMethod method,
                                         HttpRequest request,
                                         HttpExchange exchange,
                                         HttpHandler handler,
                                         PersistenceEngine engine) {
        if (!isAuthorized(path)) {
            exchange.respond(HttpResponse.noBody(HttpStatus.FORBIDDEN, request.version()));
            return;
        }
        handleWithinRequestSession(method, request, exchange, handler, engine);
    }

    private static boolean isAuthorized(String path) {
        if (!KernelProviders.PRINCIPAL_CONTEXT.isBound()) {
            return false;
        }

        PrincipalContext principal = KernelProviders.PRINCIPAL_CONTEXT.get();
        if (requiresAdminScope(path)) {
            return principal.hasAnyScope(WRITE_SCOPE);
        }
        return principal.hasAnyScope(READ_SCOPE);
    }

    private static boolean requiresAdminScope(String path) {
        return path != null && path.startsWith(ADMIN_PATH_PREFIX);
    }

    private void handleWithinRequestSession(HttpMethod method,
                                            HttpRequest request,
                                            HttpExchange exchange,
                                            HttpHandler handler,
                                            PersistenceEngine engine) {
        boolean readOnly = isReadOnlyMethod(method);
        PersistenceSessionBox box = new PersistenceSessionBox(engine, TransactionIsolation.READ_COMMITTED, readOnly);

        ScopedValue.where(REQUEST_SESSION, box).run(() -> {
            try {
                handler.handle(exchange);
            } catch (RuntimeException _) {
                if (!isResponded(exchange)) {
                    exchange.respond(
                            HttpResponse.noBody(HttpStatus.INTERNAL_SERVER_ERROR, request.version()));
                }
            } finally {
                box.release();
            }
        });
    }

    private static boolean isResponded(HttpExchange exchange) {
        return (exchange instanceof CommunityHttpExchange communityExchange
                && communityExchange.isResponded())
                || (exchange instanceof InMemoryHttp2Exchange inMemoryHttp2Exchange
                && inMemoryHttp2Exchange.isResponded());
    }

    private LoanedBuffer createBearerTokenBuffer(List<HttpHeader> headers) {
        String token = extractBearerToken(headers);
        if (token == null) {
            return null;
        }

        byte[] tokenBytes = token.getBytes(StandardCharsets.UTF_8);
        if (tokenBytes.length == 0) {
            return null;
        }

        LoanedBuffer tokenBuffer = allocator.allocateNetwork(tokenBytes.length);
        MemorySegment.copy(
                MemorySegment.ofArray(tokenBytes), 0,
                tokenBuffer.segment(), 0,
                tokenBytes.length);
        tokenBuffer.setSize(tokenBytes.length);
        return tokenBuffer;
    }

    private static String extractBearerToken(List<HttpHeader> headers) {
        String found = "";
        boolean authorizationResolved = false;
        for (HttpHeader header : headers) {
            if (!authorizationResolved && header.nameEqualsIgnoreCase(AUTHORIZATION_HEADER)) {
                found = parseBearerTokenValue(header.value());
                authorizationResolved = true;
            }
        }
        return found.isEmpty() ? null : found;
    }

    private static String parseBearerTokenValue(String headerValue) {
        String value = headerValue.trim();
        if (!value.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return "";
        }
        String token = value.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? "" : token;
    }

    private static boolean requiresAdmission(String path) {
        return path != null && path.startsWith(SECURE_PATH_PREFIX) && !isPublicPath(path);
    }

    private static boolean isPublicPath(String path) {
        if (path == null) {
            return false;
        }
        return HEALTH_PATH.equals(path)
                || HEALTH_LIVE_PATH.equals(path)
                || HEALTH_READY_PATH.equals(path)
                || DB_PING_PATH.equals(path)
                || path.startsWith(DB_ROUNDTRIP_PATH);
    }

    private static HttpResponse backpressureResponse(HttpVersion version, String retryAfterSeconds) {
        List<HttpHeader> headers = List.of(new HttpHeader("Retry-After", retryAfterSeconds));
        return HttpResponse.noBody(HttpStatus.SERVICE_UNAVAILABLE, version, headers);
    }

    private PersistenceEngine resolvePersistenceEngine() {
        return this.persistenceEngine;
    }

    private static boolean isReadOnlyMethod(HttpMethod method) {
        return method == HttpMethod.GET || method == HttpMethod.HEAD;
    }

    @SuppressWarnings("PMD.CognitiveComplexity")
    private ReadResult readRequest(Http1Codec codec,
                                   TransportStream stream,
                                   HttpHandler handler,
                                   LoanedBuffer aggregate,
                                   long bufferedBytes) {
        codec.reset();
        long total = bufferedBytes;

        if (shouldHandlePriorKnowledge(stream, handler, aggregate, total)) {
            return null;
        }

        if (total > 0) {
            ReadResult parsed = tryParseRequest(codec, aggregate, total);
            if (parsed != null) {
                return parsed;
            }
        }

        while (total < MAX_AGGREGATE_BYTES) {
            int remaining = MAX_AGGREGATE_BYTES - (int) total;
            int chunk = Math.min(READ_CHUNK_BYTES, remaining);
            int read = stream.read(aggregate.segment().asSlice(total, chunk), chunk);
            if (read < 0) {
                return null;
            }
            if (read > 0) {
                total += read;
                aggregate.setSize(total);

                if (shouldHandlePriorKnowledge(stream, handler, aggregate, total)) {
                    return null;
                }

                ReadResult parsed = tryParseRequest(codec, aggregate, total);
                if (parsed != null) {
                    return parsed;
                }
            }
        }
        return null;
    }

    private static long retainUnreadBytes(LoanedBuffer aggregate, long consumedBytes) {
        long total = aggregate.size();
        long remaining = Math.max(total - consumedBytes, 0);
        if (remaining > 0) {
            MemorySegment.copy(
                    aggregate.segment(), consumedBytes,
                    aggregate.segment(), 0,
                    remaining);
        }
        aggregate.setSize(remaining);
        return remaining;
    }

    private static ReadResult tryParseRequest(Http1Codec codec,
                                              LoanedBuffer aggregate,
                                              long total) {
        long requestLineEnd = findCrLf(aggregate.segment(), 0, total);
        if (requestLineEnd < 0) {
            return null;
        }

        Http1RequestParser.RequestLine requestLine =
                codec.parseRequestLine(aggregate.segment(), 0, total);
        if (requestLine == null) {
            return null;
        }

        long headerStart = requestLineEnd + 2;
        long headersEnd = codec.parseHeaders(
                aggregate.segment(), headerStart, total - headerStart);
        if (headersEnd < 0) {
            return null;
        }

        int bodyLength = (int) Math.max(codec.pendingContentLength(), 0);
        if (total < headersEnd + bodyLength) {
            return null;
        }

        List<HttpHeader> headers = new ArrayList<>();
        Http1RequestParser.parseHeaders(
                aggregate.segment(),
                headerStart,
                total - headerStart,
                (name, value) -> headers.add(new HttpHeader(name, value)));

        return new ReadResult(
                parseMethod(requestLine.method()),
                requestLine.target(),
                parseVersion(requestLine.version()),
                List.copyOf(headers),
                headersEnd,
                bodyLength,
                headersEnd + bodyLength,
                codec.isKeepAlive());
    }

    private static long findCrLf(MemorySegment segment, long start, long endExclusive) {
        for (long index = start; index + 1 < endExclusive; index++) {
            byte current = segment.get(ValueLayout.JAVA_BYTE, index);
            byte next = segment.get(ValueLayout.JAVA_BYTE, index + 1);
            if (current == '\r' && next == '\n') {
                return index;
            }
        }
        return -1;
    }

    private static HttpMethod parseMethod(String token) {
        try {
            return HttpMethod.valueOf(token);
        } catch (IllegalArgumentException _) {
            return HttpMethod.GET;
        }
    }

    private static HttpVersion parseVersion(String token) {
        return switch (token) {
            case "HTTP/1.0" -> HttpVersion.HTTP_1_0;
            case "HTTP/1.1" -> HttpVersion.HTTP_1_1;
            default -> HttpVersion.HTTP_1_1;
        };
    }

    private boolean isH2cEnabled() {
        return config.h2cUpgradeEnabled() && supportsHttp2(config.maxVersion());
    }

    private static boolean supportsHttp2(HttpVersion maxVersion) {
        return maxVersion == HttpVersion.HTTP_2 || maxVersion == HttpVersion.HTTP_3;
    }

    private boolean shouldHandlePriorKnowledge(TransportStream stream,
                                               HttpHandler handler,
                                               LoanedBuffer aggregate,
                                               long totalBytes) {
        if (!supportsHttp2(config.maxVersion())) {
            return false;
        }
        if (!isHttp2PriorKnowledgePreface(aggregate.segment(), totalBytes)) {
            return false;
        }
        handleHttp2PriorKnowledge(stream, handler, aggregate, totalBytes);
        return true;
    }

    private void handleHttp2PriorKnowledge(TransportStream stream,
                                           HttpHandler handler,
                                           LoanedBuffer aggregate,
                                           long totalBytes) {
        if (LOG.isLoggable(System.Logger.Level.INFO)) {
            LOG.log(System.Logger.Level.INFO,
                    "HTTP/2 prior-knowledge preface detected; entering h2c request frame-loop mode");
        }
        sendHttp2ServerSettings(stream);
        try (Http2SessionContext session = Http2SessionContext.create(allocator)) {
            processBufferedHttp2Frames(
                    stream,
                    handler,
                    session,
                    aggregate,
                    totalBytes,
                    HTTP2_PRIOR_KNOWLEDGE_PREFACE.length);
        }
    }

    private void handleHttp1UpgradeToH2c(ReadResult readResult,
                                          TransportStream stream,
                                          HttpHandler handler,
                                          LoanedBuffer aggregate) {
        writeHttp11UpgradeResponse(stream);
        long bufferedHttp2Bytes = retainUnreadBytes(aggregate, readResult.consumedBytes());
        sendHttp2ServerSettings(stream);
        try (Http2SessionContext session = Http2SessionContext.create(allocator)) {
            processBufferedHttp2Frames(stream, handler, session, aggregate, bufferedHttp2Bytes, 0);
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
                                            LoanedBuffer aggregate,
                                            long initialBytes,
                                            long initialFrameOffset) {
        long bufferedBytes = initialBytes;
        long offset = initialFrameOffset;

        while (true) {
            offset = processAvailableHttp2Frames(stream, handler, session, aggregate, bufferedBytes, offset);
            if (offset == HTTP2_FRAME_LOOP_STOP) {
                return;
            }
            if (offset == HTTP2_FRAME_LOOP_INVALID) {
                sendHttp2GoAway(stream, session.lastProcessedStreamId(), Http2ErrorCode.PROTOCOL_ERROR);
                return;
            }

            long unreadBytes = compactHttp2UnreadBytes(aggregate, bufferedBytes, offset);
            offset = 0;
            bufferedBytes = unreadBytes;

            if (bufferedBytes >= MAX_AGGREGATE_BYTES) {
                sendHttp2GoAway(stream, session.lastProcessedStreamId(), Http2ErrorCode.FRAME_SIZE_ERROR);
                return;
            }

            int read = readHttp2Bytes(stream, aggregate, bufferedBytes);
            if (read < 0) {
                sendHttp2GoAwayNoError(stream);
                return;
            }
            if (read > 0) {
                bufferedBytes += read;
                aggregate.setSize(bufferedBytes);
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

    private static long compactHttp2UnreadBytes(LoanedBuffer aggregate,
                                                long bufferedBytes,
                                                long offset) {
        long unreadBytes = Math.max(bufferedBytes - offset, 0);
        if (unreadBytes > 0 && offset > 0) {
            MemorySegment.copy(
                    aggregate.segment(), offset,
                    aggregate.segment(), 0,
                    unreadBytes);
        }
        aggregate.setSize(unreadBytes);
        return unreadBytes;
    }

    private static int readHttp2Bytes(TransportStream stream,
                                      LoanedBuffer aggregate,
                                      long bufferedBytes) {
        int remaining = MAX_AGGREGATE_BYTES - (int) bufferedBytes;
        int chunk = Math.min(READ_CHUNK_BYTES, remaining);
        return stream.read(aggregate.segment().asSlice(bufferedBytes, chunk), chunk);
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
            dispatchRequest(request, exchange, handler);
            if (!exchange.isResponded()) {
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

    private void writeHttp2Response(TransportStream stream,
                                    Http2SessionContext session,
                                    int streamId,
                                    HttpResponse response) {
        try (LoanedBuffer headerBlock = allocator.allocateNetwork(HTTP2_MAX_HEADER_BLOCK_BYTES)) {
            long headerBytes = session.encodeResponseHeaders(headerBlock.segment(), response);
            LoanedBuffer bodyBuffer = response.body();
            int bodyBytes = bodyBuffer == null ? 0 : (int) bodyBuffer.size();
            boolean headerEndsStream = bodyBytes == 0;
            writeHttp2HeaderBlockFrames(stream, streamId, headerBlock.segment(), (int) headerBytes, headerEndsStream);

            if (bodyBuffer != null) {
                try (bodyBuffer) {
                    if (bodyBytes > 0) {
                        writeHttp2DataFrames(stream, streamId, bodyBuffer.segment(), bodyBytes);
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
                                             boolean endStream) {
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
            writeHttp2Frame(stream, type, flags, streamId, headerBlock, written, chunk);
            written += chunk;
            firstFrame = false;
        }
    }

    private void writeHttp2DataFrames(TransportStream stream,
                                      int streamId,
                                      MemorySegment body,
                                      int bodyLength) {
        int written = 0;
        while (written < bodyLength) {
            int chunk = Math.min(HTTP2_MAX_FRAME_PAYLOAD_BYTES, bodyLength - written);
            boolean endStream = (written + chunk) == bodyLength;
            int flags = endStream ? HTTP2_FLAG_END_STREAM : 0;
            writeHttp2Frame(stream, Http2FrameType.DATA.code(), flags, streamId, body, written, chunk);
            written += chunk;
        }
    }

    private void writeHttp2Frame(TransportStream stream,
                                 int frameType,
                                 int flags,
                                 int streamId,
                                 MemorySegment payloadSource,
                                 int payloadOffset,
                                 int payloadLength) {
        try (LoanedBuffer outbound = allocator.allocateNetwork(Http2FrameParser.FRAME_HEADER_SIZE + payloadLength)) {
            Http2FrameEncoder.writeHeader(outbound.segment(), 0, payloadLength, frameType, flags, streamId);
            if (payloadLength > 0) {
                MemorySegment.copy(
                        payloadSource,
                        payloadOffset,
                        outbound.segment(),
                        Http2FrameParser.FRAME_HEADER_SIZE,
                        payloadLength);
            }
            long written = Http2FrameParser.FRAME_HEADER_SIZE + (long) payloadLength;
            outbound.setSize(written);
            stream.write(outbound.segment(), (int) written);
        }
    }

    private record HeaderFragment(long offset, int length) {}

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

        /* default */ Http2FrameCodec codec() {
            return codec;
        }

        private void applyPeerMaxFrameSize(int maxFrameSize) {
            codec.setMaxFrameSize(maxFrameSize);
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

        /* default */ void beginHeaders(Http2FrameParser.FrameHeader header, MemorySegment payload,
                          long dataOffset, int dataLength) {
            assembler.beginHeaders(header, payload, dataOffset, dataLength);
        }

        /* default */ void appendContinuation(Http2FrameParser.FrameHeader header, MemorySegment payload,
                                long dataOffset, int dataLength) {
            assembler.appendContinuation(header, payload, dataOffset, dataLength);
        }

        /* default */ void validateContinuationMode(Http2FrameParser.FrameHeader header) {
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
            Http2RequestStreamState previous = requestStreams.put(request.streamId(),
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
                position = encoder.encodeHeader(target,
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
                if (isConnectionSpecificHeader(name)) {
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
                return new Http2DecodedRequest(streamId, method, resolvedPath, List.copyOf(requestHeaders),
                    requestValid);
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

        @Override
        public void close() {
            assembler.reset();
            for (Http2RequestStreamState requestStream : requestStreams.values()) {
                requestStream.close();
            }
            requestStreams.clear();
        }
    }
    /* default */ static boolean isHttp2PriorKnowledgePreface(MemorySegment segment, long totalBytes) {
        if (totalBytes < HTTP2_PRIOR_KNOWLEDGE_PREFACE.length) {
            return false;
        }
        for (int index = 0; index < HTTP2_PRIOR_KNOWLEDGE_PREFACE.length; index++) {
            byte actual = segment.get(ValueLayout.JAVA_BYTE, index);
            if (actual != HTTP2_PRIOR_KNOWLEDGE_PREFACE[index]) {
                return false;
            }
        }
        return true;
    }

    /* default */ static boolean isH2cUpgradeIntent(List<HttpHeader> headers) {
        boolean hasUpgradeH2c = false;
        boolean hasConnectionUpgrade = false;
        for (HttpHeader header : headers) {
            if (header.nameEqualsIgnoreCase(HEADER_UPGRADE)
                    && containsCsvTokenIgnoreCase(header.value(), H2C_TOKEN)) {
                hasUpgradeH2c = true;
            }
            if (header.nameEqualsIgnoreCase(HEADER_CONNECTION)
                    && containsCsvTokenIgnoreCase(header.value(), UPGRADE_TOKEN)) {
                hasConnectionUpgrade = true;
            }
        }
        return hasUpgradeH2c && hasConnectionUpgrade;
    }

    private static boolean containsCsvTokenIgnoreCase(String headerValue, String token) {
        int start = 0;
        int length = headerValue.length();
        while (start < length) {
            int end = headerValue.indexOf(',', start);
            if (end < 0) {
                end = length;
            }
            String candidate = headerValue.substring(start, end).trim();
            if (candidate.equalsIgnoreCase(token)) {
                return true;
            }
            start = end + 1;
        }
        return false;
    }

}
