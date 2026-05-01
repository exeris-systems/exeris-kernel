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
import eu.exeris.kernel.core.http.http1.Http1Codec;
import eu.exeris.kernel.core.http.jfr.HttpAggregateBufferForcedReleaseEvent;
import eu.exeris.kernel.core.http.jfr.HttpAggregateBufferHeldEvent;
import eu.exeris.kernel.core.security.SecurityInterceptor;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponseBodyEncoderRegistry;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.transport.TransportStream;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

@SuppressWarnings({
    "PMD.AvoidCatchingGenericException",
    "PMD.CloseResource",
    "PMD.CyclomaticComplexity",
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
    private static final String HEADER_CONNECTION = "Connection";
    private static final String HEADER_UPGRADE = "Upgrade";
    private static final String H2C_TOKEN = "h2c";
    private static final String UPGRADE_TOKEN = "upgrade";
    private static final int READ_CHUNK_BYTES = 8 * 1024;
    private static final int INITIAL_HTTP1_AGGREGATE_BYTES = 16 * 1024;
    private static final int MAX_AGGREGATE_BYTES = 1 * 1024 * 1024;
    private static final byte[] HTTP2_PRIOR_KNOWLEDGE_PREFACE =
            "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    
    // Phase 1C: HTTP buffer lifecycle tracking
    private static final long BUFFER_AGE_WARNING_THRESHOLD_MS = 100;
    private static final double PIPELINED_FRACTION_THRESHOLD = 0.05; // If pipelined < 5%, force release

    private final MemoryAllocator allocator;
    private final HttpResponseBodyEncoderRegistry encoderRegistry;
    private final HttpConfig config;
    private final CommunityHttpRequestDispatcher requestDispatcher;
    private final CommunityHttp2SessionProcessor http2SessionProcessor;

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

        SecurityInterceptor securityInterceptor = KernelProviders.SECURITY_PROVIDER.isBound()
                ? new SecurityInterceptor(KernelProviders.SECURITY_PROVIDER.get())
                : null;
        PersistenceEngine persistenceEngine = KernelProviders.PERSISTENCE_ENGINE.isBound()
                ? KernelProviders.PERSISTENCE_ENGINE.get()
                : null;

        this.requestDispatcher = new CommunityHttpRequestDispatcher(
                this.allocator,
                securityInterceptor,
                persistenceEngine);
        this.http2SessionProcessor = new CommunityHttp2SessionProcessor(
                this.allocator,
                this.encoderRegistry,
                this.config,
                this.requestDispatcher,
                READ_CHUNK_BYTES,
                MAX_AGGREGATE_BYTES);
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
        state.ensureAggregate(allocator, INITIAL_HTTP1_AGGREGATE_BYTES);
        if (!hadAggregate) {
            state.resetBufferForNewAggregate();
        }

        ReadResult readResult = readRequest(codec, stream, handler, state, state.bufferedBytes());
        if (readResult == null) {
            return false;
        }

        if (isH2cEnabled() && isH2cUpgradeIntent(readResult.headers())) {
            if (LOG.isLoggable(System.Logger.Level.INFO)) {
                LOG.log(System.Logger.Level.INFO,
                        "HTTP/1.1 h2c upgrade requested; switching to HTTP/2 frame-loop mode");
            }
            handleHttp1UpgradeToH2c(readResult, stream, handler, state);
            return false;
        }

        trackPipelinedRequest(state);
        handleRequest(readResult, state.aggregate(), stream, handler);

        state.updateBufferedBytes(CommunityHttp1RequestReader.retainUnreadBytes(
                state.aggregate(),
                readResult.consumedBytes()));
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

        requestDispatcher.dispatch(request, exchange, handler);
    }

    @SuppressWarnings("PMD.CognitiveComplexity")
    private ReadResult readRequest(Http1Codec codec,
                                   TransportStream stream,
                                   HttpHandler handler,
                                   ProcessingState state,
                                   long bufferedBytes) {
        codec.reset();
        LoanedBuffer aggregate = state.aggregate();
        long total = bufferedBytes;

        if (shouldHandlePriorKnowledge(stream, handler, state, total)) {
            return null;
        }

        if (total > 0) {
            ReadResult parsed = CommunityHttp1RequestReader.tryParseRequest(codec, aggregate, total);
            if (parsed != null) {
                return parsed;
            }
        }

        while (total < MAX_AGGREGATE_BYTES) {
            long requiredCapacity = Math.min(MAX_AGGREGATE_BYTES, total + READ_CHUNK_BYTES);
            state.ensureAggregateCapacity(allocator, requiredCapacity, MAX_AGGREGATE_BYTES);
            aggregate = state.aggregate();

            int remaining = (int) Math.min(aggregate.capacity() - total, MAX_AGGREGATE_BYTES - total);
            int chunk = Math.min(READ_CHUNK_BYTES, remaining);
            int read = stream.read(aggregate.segment().asSlice(total, chunk), chunk);
            if (read < 0) {
                return null;
            }
            if (read > 0) {
                total += read;
                aggregate.setSize(total);

                if (shouldHandlePriorKnowledge(stream, handler, state, total)) {
                    return null;
                }

                ReadResult parsed = CommunityHttp1RequestReader.tryParseRequest(codec, aggregate, total);
                if (parsed != null) {
                    return parsed;
                }
            }
        }
        return null;
    }


    private boolean isH2cEnabled() {
        return config.h2cUpgradeEnabled() && supportsHttp2(config.maxVersion());
    }

    private static boolean supportsHttp2(HttpVersion maxVersion) {
        return maxVersion == HttpVersion.HTTP_2 || maxVersion == HttpVersion.HTTP_3;
    }

    private boolean shouldHandlePriorKnowledge(TransportStream stream,
                                               HttpHandler handler,
                                               ProcessingState state,
                                               long totalBytes) {
        if (!supportsHttp2(config.maxVersion())) {
            return false;
        }
        LoanedBuffer aggregate = state.aggregate();
        if (!isHttp2PriorKnowledgePreface(aggregate.segment(), totalBytes)) {
            return false;
        }
        handleHttp2PriorKnowledge(stream, handler, state, totalBytes);
        return true;
    }

    private void handleHttp2PriorKnowledge(TransportStream stream,
                                           HttpHandler handler,
                                           ProcessingState state,
                                           long totalBytes) {
        http2SessionProcessor.handlePriorKnowledge(
                stream,
                handler,
                state,
                totalBytes,
                HTTP2_PRIOR_KNOWLEDGE_PREFACE.length);
    }

    private void handleHttp1UpgradeToH2c(ReadResult readResult,
                                         TransportStream stream,
                                         HttpHandler handler,
                                         ProcessingState state) {
        http2SessionProcessor.handleUpgrade(readResult, stream, handler, state);
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
