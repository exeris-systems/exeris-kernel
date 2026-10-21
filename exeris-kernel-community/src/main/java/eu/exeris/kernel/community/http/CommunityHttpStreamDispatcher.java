/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.core.http.routing.PathParamStreamExchange;
import eu.exeris.kernel.core.http.sse.HttpStreamEngine;
import eu.exeris.kernel.core.http.sse.StreamAdmissionController;
import eu.exeris.kernel.core.http.sse.StreamEgress;
import eu.exeris.kernel.spi.exceptions.http.StreamClosedException;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpStreamHandler;
import eu.exeris.kernel.spi.http.StreamMatch;
import eu.exeris.kernel.spi.http.StreamRouteResolver;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.transport.TransportStream;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToLongFunction;

/**
 * Community: routes a parsed request to the SSE streaming path when its route is streaming-flagged
 * (ADR-043 obligation 7, amendment A1). A streaming route resolves to an {@link HttpStreamHandler}
 * through the bound handler's {@link StreamRouteResolver}; a handler that does not implement it gets
 * respond-once dispatch only. The handler runs on the stream's own virtual thread (the "1 VT per
 * stream" model): on HTTP/1.1 the one the transport already dispatched the connection on, on HTTP/2 a
 * thread forked for the stream, because many streams share one connection and its frame loop.
 *
 * <p>The SSE wire framing and the held-open egress mechanics live in the tier-blind Core
 * {@link HttpStreamEngine}; this class is the NIO-side wiring that hands it the
 * {@link NativeTcpStream}-backed {@link TransportStream} and the per-stream allocator.
 *
 * @since 0.10
 */
final class CommunityHttpStreamDispatcher {

    /**
     * Egress credit window (outstanding bytes) before {@code emit()} parks. Overridable via the
     * {@code exeris.http.stream.creditWindowBytes} system property so the backpressure TCK probe can
     * force a deterministic park below OS socket-buffer sizes.
     */
    private static final int CREDIT_WINDOW_BYTES = Integer.getInteger(
            "exeris.http.stream.creditWindowBytes", HttpStreamEngine.DEFAULT_CREDIT_WINDOW_BYTES);

    private static final ToLongFunction<HttpRequest> NO_AUTH_DEADLINE = request -> 0L;

    private final MemoryAllocator allocator;
    private final StreamAdmissionController admissionController;
    private final ToLongFunction<HttpRequest> authDeadline;
    private final AtomicInteger activeStreams = new AtomicInteger(0);

    /* default */ CommunityHttpStreamDispatcher(MemoryAllocator allocator) {
        this(allocator, null);
    }

    /* default */ CommunityHttpStreamDispatcher(MemoryAllocator allocator,
                                                StreamAdmissionController admissionController) {
        this(allocator, admissionController, NO_AUTH_DEADLINE);
    }

    /**
     * Creates a dispatcher whose HTTP/2 streams carry a fail-closed deadline.
     *
     * @param allocator           the allocator events are framed with
     * @param admissionController admits or sheds a new stream open, or {@code null} for none
     * @param authDeadline        the JWT-expiry deadline of a request as epoch milliseconds, {@code <= 0}
     *                            for none; production passes none until the identity provider surfaces a
     *                            principal expiry on the streaming path
     */
    /* default */ CommunityHttpStreamDispatcher(MemoryAllocator allocator,
                                                StreamAdmissionController admissionController,
                                                ToLongFunction<HttpRequest> authDeadline) {
        this.allocator = Objects.requireNonNull(allocator, "allocator must not be null");
        this.admissionController = admissionController;
        this.authDeadline = Objects.requireNonNull(authDeadline, "authDeadline must not be null");
    }

    /**
     * Returns the allocator the dispatcher frames events with.
     *
     * @return the allocator
     */
    /* default */ MemoryAllocator allocator() {
        return allocator;
    }

    /**
     * Returns the per-stream egress credit window in bytes: the system property
     * {@code exeris.http.stream.creditWindowBytes} when set, else the engine's default.
     *
     * @return the window, which an HTTP/2 stream also uses as its bound on bytes queued at the transport
     */
    /* default */ static int creditWindowBytes() {
        return CREDIT_WINDOW_BYTES;
    }

    /**
     * Resolves the streaming handler for {@code request} if the active handler resolves stream
     * routes; returns {@code null} when the request is not streaming (respond-once path). The method
     * and the path are passed as the request carries them, query string included.
     *
     * @param request the parsed request
     * @param handler the active root handler
     * @return the resolved stream route, or {@code null}
     */
    /* default */ StreamMatch resolveStreamHandler(HttpRequest request, HttpHandler handler) {
        if (handler instanceof StreamRouteResolver resolver) {
            return resolver.resolveStream(request.method(), request.path());
        }
        return null;
    }

    /**
     * Opens an SSE stream over {@code stream}, writes the response head, and runs the handler's emit
     * loop on the current (stream-owned) virtual thread. A {@link StreamClosedException} that unwinds
     * the handler on disconnect / fail-closed teardown is the designed end-of-loop and is swallowed
     * here; the engine has already torn the stream down.
     *
     * @param request the parsed request that opened the stream
     * @param stream  the held-open transport stream
     * @param match   the resolved stream route
     */
    /* default */ StreamClosedException dispatchStream(HttpRequest request,
                                                      TransportStream stream,
                                                      StreamMatch match) {
        return dispatchStream(request, stream, match, 0L);
    }

    /**
     * Opens an SSE stream with a JWT-expiry fail-closed deadline (ADR-043 obligation 6). The deadline
     * is derived inside the Community JWT path and is binding-internal until ADR-040 lands.
     *
     * @param request                 the parsed request
     * @param stream                  the held-open transport stream
     * @param match                   the resolved stream route
     * @param authDeadlineEpochMillis epoch-millis deadline; {@code <= 0} disables expiry enforcement
     */
    /* default */ StreamClosedException dispatchStream(HttpRequest request,
                                                      TransportStream stream,
                                                      StreamMatch match,
                                                      long authDeadlineEpochMillis) {
        // PAQS admission (ADR-043 obligation 7): a NEW open is admitted once and holds its slot for the
        // stream lifetime. Under SHED_LOAD this throws TransportException(EX-NET-4006) before the slot is
        // taken — already-open streams (counted in activeStreams) keep emitting, untouched.
        if (admissionController != null) {
            admissionController.admit(stream.streamId(), activeStreams.get());
        }
        activeStreams.incrementAndGet();
        HttpStreamEngine engine = HttpStreamEngine.open(
                request, stream, allocator, authDeadlineEpochMillis, CREDIT_WINDOW_BYTES);
        return runHandler(engine, match);
    }

    /**
     * Opens an SSE stream on one HTTP/2 stream of a connection, writes its head through {@code egress},
     * and runs the handler's emit loop on the calling thread, which belongs to this stream alone. The
     * stream ends, gracefully or not, without closing the connection; a {@link StreamClosedException}
     * that unwinds the handler is the designed end of the loop and is swallowed, as on HTTP/1.1.
     *
     * @param request the parsed request that opened the stream
     * @param egress  the HTTP/2 side of the stream
     * @param match   the resolved stream route
     * @return what ended the stream, or {@code null} when it closed gracefully
     */
    /* default */ StreamClosedException dispatchHttp2Stream(HttpRequest request,
                                                            StreamEgress egress,
                                                            StreamMatch match) {
        if (admissionController != null) {
            admissionController.admit(egress.transport().streamId(), activeStreams.get());
        }
        activeStreams.incrementAndGet();
        HttpStreamEngine engine;
        boolean opened = false;
        try {
            engine = HttpStreamEngine.open(request, egress, authDeadline.applyAsLong(request));
            opened = true;
        } finally {
            // A head that cannot be written must not leave the slot counted for a stream that never ran.
            if (!opened) {
                activeStreams.decrementAndGet();
            }
        }
        return runHandler(engine, match);
    }

    private StreamClosedException runHandler(HttpStreamEngine engine, StreamMatch match) {
        try {
            // An exact stream route gets the engine itself; only a template pays for the decorator.
            match.handler().handle(match.params().isEmpty()
                    ? engine
                    : new PathParamStreamExchange(engine, match.params()));
            engine.close();
        } catch (StreamClosedException _) {
            // Designed unwind: peer disconnect or fail-closed teardown. The engine already reset the
            // stream; nothing more to do — the handler's emit loop exited naturally.
        } finally {
            activeStreams.decrementAndGet();
        }
        // The terminal signal is recorded even when the handler swallows the throw, so callers can
        // surface "what ended the stream" (null on graceful close).
        return engine.terminalSignal();
    }
}
