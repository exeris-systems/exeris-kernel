/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.core.http.routing.HttpRouter;
import eu.exeris.kernel.community.persistence.PersistenceSessionBox;
import eu.exeris.kernel.core.http.http1.Http1Codec;
import eu.exeris.kernel.core.security.GeneratedRoleRegistryLoader;
import eu.exeris.kernel.core.transport.TransportScopes;
import eu.exeris.kernel.core.security.SecurityInterceptor;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpKernelProviders;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpRequestBodyDecoderRegistry;
import eu.exeris.kernel.spi.http.HttpRoutePolicy;
import eu.exeris.kernel.spi.http.HttpResponseBodyEncoderRegistry;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.transport.TransportStream;

import java.lang.foreign.MemorySegment;
import java.util.Objects;

// Cohesion baseline post-QA-011 (v0.8 Sprint 1): h2c/HTTP-2 upgrade detection and
// aggregate-buffer telemetry were extracted to dedicated helpers
// (CommunityHttpH2cUpgradeDetector, CommunityHttpAggregateTelemetry). The remaining
// suppressions reflect intrinsic processor responsibilities: the request loop is the
// natural integration point that catches generic stream errors (graceful close
// detection is testable and locked in `isExpectedStreamClose`), the
// try-with-resources lifecycle on `TransportStream` / `ProcessingState` is
// purposeful (CloseResource suppression covers the buffer flyweight that is shared
// across loop iterations by design), and the keep-alive iteration / read-aggregate
// branching dominates the residual cyclomatic complexity.
@SuppressWarnings({
    "PMD.AvoidCatchingGenericException",
    "PMD.CloseResource",
    "PMD.CyclomaticComplexity"
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
    private static final int READ_CHUNK_BYTES = 8 * 1024;
    private static final int INITIAL_HTTP1_AGGREGATE_BYTES = 16 * 1024;
    private static final int MAX_AGGREGATE_BYTES = 1 * 1024 * 1024;

    private final MemoryAllocator allocator;
    private final HttpResponseBodyEncoderRegistry encoderRegistry;
    private final HttpConfig config;
    private final CommunityHttpRequestDispatcher requestDispatcher;
    private final CommunityHttpStreamDispatcher streamDispatcher;
    private final CommunityHttp2SessionProcessor http2SessionProcessor;
    private final CommunityHttpH2cUpgradeDetector upgradeDetector;

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
                ? new SecurityInterceptor(
                        KernelProviders.SECURITY_PROVIDER.get(),
                        GeneratedRoleRegistryLoader.load())
                : null;
        PersistenceEngine persistenceEngine = KernelProviders.PERSISTENCE_ENGINE.isBound()
                ? KernelProviders.PERSISTENCE_ENGINE.get()
                : null;

        // ADR-036 / W7 boot-path fix: the request-body decoder registry is bound into the kernel
        // carrier scope by CommunityHttpSubsystem.providerBindings(), but the native transport runs
        // per-request handlers on reactor threads that are not structured forks of that scope, so a
        // handler-time HttpKernelProviders.httpRequestBodyDecoderRegistry() read would see an unbound
        // slot. We capture it here — this constructor runs inside the carrier scope at engine start —
        // and re-establish it per request at the dispatch seam (same channel as REQUEST_SESSION).
        HttpRequestBodyDecoderRegistry requestBodyDecoderRegistry =
                HttpKernelProviders.httpRequestBodyDecoderRegistry().orElse(null);

        // ADR-061: captured here for the same reason as the decoder registry above — the route policy
        // is read on the admission path, which runs on reactor threads outside this carrier scope.
        HttpRoutePolicy routePolicy = HttpKernelProviders.httpRoutePolicy().orElse(null);

        this.requestDispatcher = new CommunityHttpRequestDispatcher(
                this.allocator,
                securityInterceptor,
                persistenceEngine,
                requestBodyDecoderRegistry,
                routePolicy);
        this.streamDispatcher = new CommunityHttpStreamDispatcher(this.allocator);
        this.http2SessionProcessor = new CommunityHttp2SessionProcessor(
                this.allocator,
                this.encoderRegistry,
                this.config,
                this.requestDispatcher,
                READ_CHUNK_BYTES,
                MAX_AGGREGATE_BYTES);
        this.upgradeDetector = new CommunityHttpH2cUpgradeDetector(this.config, this.http2SessionProcessor);
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

        // Parked on the next request, this connection is serving nothing — it must not hold graceful
        // shutdown open, because an idle keep-alive connection never closes on its own (issue #282).
        // Streams are busy by default, so a protocol that cannot tell simply never reaches this.
        markIdle();
        ReadResult readResult;
        boolean rearmed;
        try {
            readResult = readRequest(codec, stream, handler, state, state.bufferedBytes());
        } finally {
            rearmed = markBusy();
        }
        if (readResult == null) {
            return false;
        }
        if (!rearmed) {
            // The drain committed to teardown while this connection was parked on the next request,
            // so this stream is not being waited for. Serving now would race a teardown already in
            // progress — the failure the drain exists to prevent, reached from the other side. The
            // peer was told to close on the previous response; a request sent anyway is racing us,
            // and RFC 9112 §9.6 requires a client to tolerate a persistent connection closing.
            return false;
        }

        if (upgradeDetector.isH2cEnabled()
                && CommunityHttpH2cUpgradeDetector.isH2cUpgradeIntent(readResult.headers())) {
            if (LOG.isLoggable(System.Logger.Level.INFO)) {
                LOG.log(System.Logger.Level.INFO,
                        "HTTP/1.1 h2c upgrade requested; switching to HTTP/2 frame-loop mode");
            }
            upgradeDetector.handleHttp1UpgradeToH2c(readResult, stream, handler, state);
            return false;
        }

        state.recordRequest();
        boolean wasStream = handleRequest(readResult, state.aggregate(), stream, handler);
        if (wasStream) {
            // A streaming route held the connection for the stream's lifetime and then closed it;
            // there is no keep-alive continuation on the same connection.
            return false;
        }

        state.updateBufferedBytes(CommunityHttp1RequestReader.retainUnreadBytes(
                state.aggregate(),
                readResult.consumedBytes()));
        if (!readResult.keepAlive()) {
            return false;
        }
        if (isDraining()) {
            // Shutdown began while this connection was being served. Extending it would put the
            // connection back into the idle state the drain cannot wait out; the response already
            // told the peer to close (see CommunityHttpExchange#resolveKeepAlive).
            //
            // Accepted residual: the drain can begin between that write and this check, so a peer can
            // be told keep-alive and then find the connection closed. There is no I/O between the two
            // to widen the window, and RFC 9112 §9.6 requires a client to tolerate exactly this — a
            // persistent connection may close at any time, and an idempotent request is retried.
            return false;
        }

        CommunityHttpAggregateTelemetry.applyAndRelease(state, MAX_AGGREGATE_BYTES);
        state.releaseAggregateIfIdle();
        return true;
    }

    private static void markIdle() {
        if (TransportScopes.STREAM_WORK.isBound()) {
            TransportScopes.STREAM_WORK.get().markIdle();
        }
    }

    /**
     * @return {@code false} only when the drain has committed to teardown; unbound means no drain
     *         coordinator is in play at all, which must not close connections
     */
    private static boolean markBusy() {
        return !TransportScopes.STREAM_WORK.isBound()
                || TransportScopes.STREAM_WORK.get().markBusy();
    }

    private static boolean isDraining() {
        return TransportScopes.DRAIN_COORDINATOR.isBound()
                && TransportScopes.DRAIN_COORDINATOR.get().isDraining();
    }

    private boolean handleRequest(ReadResult readResult,
                                  LoanedBuffer aggregate,
                                  TransportStream stream,
                                  HttpHandler handler) {
        int bodyLength = readResult.bodyLength();
        if (bodyLength <= 0) {
            return dispatchRequest(readResult, null, stream, handler);
        }

        try (LoanedBuffer bodyBuffer = allocator.allocateNetwork(bodyLength)) {
            MemorySegment.copy(
                    aggregate.segment(), readResult.bodyStart(),
                    bodyBuffer.segment(), 0,
                    bodyLength);
            bodyBuffer.setSize(bodyLength);
            return dispatchRequest(readResult, bodyBuffer, stream, handler);
        }
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

    private boolean dispatchRequest(ReadResult readResult,
                                    LoanedBuffer bodyBuffer,
                                    TransportStream stream,
                                    HttpHandler handler) {
        HttpRequest request = new HttpRequest(
                readResult.method(),
                readResult.path(),
                readResult.version(),
                readResult.headers(),
                bodyBuffer);

        HttpRouter.StreamMatch streamRoute = streamDispatcher.resolveStreamHandler(request, handler);
        if (streamRoute != null) {
            // v0.10 streaming dispatch (ADR-043). Two obligation mechanisms are built + TCK-pinned
            // (HttpStreamEngine deadline / StreamAdmissionController) but their PRODUCTION binding is
            // deliberately deferred here, not wired:
            //   - obligation 6 (JWT-expiry fail-closed): dispatched with no auth deadline until the
            //     IdentityProvider SPI (ADR-040) surfaces a principal `exp` on the streaming path
            //     (ADR-043 §6 states the deadline is Community-internal until then).
            //   - obligation 7 (streaming-occupancy ceiling): no StreamAdmissionController is wired, so
            //     the dedicated long-lived-slot ceiling is not yet enforced. The safety property — new
            //     stream-opens shed under load — still holds via carrier-edge PAQS (NativeTcpCarrier's
            //     AdmissionController), which sheds any new stream including an SSE open. Plumbing the
            //     carrier arbiter through to a dedicated streaming ceiling is a v0.10 follow-up.
            // ADR-061 applies to a stream open exactly as it does to a request. Routed through the
            // request dispatcher rather than checked here, so there is one implementation of the
            // route requirement and one place it can drift from.
            CommunityHttpExchange denial = new CommunityHttpExchange(
                    request, stream, allocator, readResult.keepAlive(), encoderRegistry);
            requestDispatcher.dispatchStream(request, denial,
                    () -> streamDispatcher.dispatchStream(request, stream, streamRoute));
            return true;
        }

        // The exchange decides Connection: keep-alive when it writes, not here — see
        // CommunityHttpExchange#resolveKeepAlive.
        CommunityHttpExchange exchange = new CommunityHttpExchange(
                request, stream, allocator, readResult.keepAlive(), encoderRegistry);

        requestDispatcher.dispatch(request, exchange, handler);
        return false;
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

        if (upgradeDetector.shouldHandlePriorKnowledge(stream, handler, state, total)) {
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

                if (upgradeDetector.shouldHandlePriorKnowledge(stream, handler, state, total)) {
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


}
