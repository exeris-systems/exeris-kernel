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
import eu.exeris.kernel.core.http.routing.PathParamStreamExchange;
import eu.exeris.kernel.core.http.sse.HttpStreamEngine;
import eu.exeris.kernel.core.http.sse.StreamAdmissionController;
import eu.exeris.kernel.spi.exceptions.http.StreamClosedException;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpStreamHandler;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.transport.TransportStream;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Community: routes a parsed request to the SSE streaming path when its route is streaming-flagged
 * (ADR-043 obligation 7). A streaming route resolves to an {@link HttpStreamHandler} via
 * {@link HttpRouter#resolveStream}; the handler runs on the stream's own virtual thread (the
 * "1 VT per stream" model — the same VT the transport already dispatched this stream on).
 *
 * <p>The SSE wire framing and the held-open egress mechanics live in the tier-blind Core
 * {@link HttpStreamEngine}; this class is the NIO-side wiring that hands it the
 * {@link NativeTcpStream}-backed {@link TransportStream} and the per-stream allocator.
 *
 * @since 0.10.0
 */
final class CommunityHttpStreamDispatcher {

    /**
     * Egress credit window (outstanding bytes) before {@code emit()} parks. Overridable via the
     * {@code exeris.http.stream.creditWindowBytes} system property so the backpressure TCK probe can
     * force a deterministic park below OS socket-buffer sizes.
     */
    private static final int CREDIT_WINDOW_BYTES = Integer.getInteger(
            "exeris.http.stream.creditWindowBytes", HttpStreamEngine.DEFAULT_CREDIT_WINDOW_BYTES);

    private final MemoryAllocator allocator;
    private final StreamAdmissionController admissionController;
    private final AtomicInteger activeStreams = new AtomicInteger(0);

    /* default */ CommunityHttpStreamDispatcher(MemoryAllocator allocator) {
        this(allocator, null);
    }

    /* default */ CommunityHttpStreamDispatcher(MemoryAllocator allocator,
                                                StreamAdmissionController admissionController) {
        this.allocator = Objects.requireNonNull(allocator, "allocator must not be null");
        this.admissionController = admissionController;
    }

    /**
     * Resolves the streaming handler for {@code request} if the active handler is a router carrying a
     * streaming route; returns {@code null} when the request is not streaming (respond-once path).
     *
     * @param request the parsed request
     * @param handler the active root handler
     * @return the resolved stream route, or {@code null}
     */
    /* default */ HttpRouter.StreamMatch resolveStreamHandler(HttpRequest request,
                                                              HttpHandler handler) {
        if (handler instanceof HttpRouter router) {
            return router.resolveStream(request.method(), request.path());
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
                                                      HttpRouter.StreamMatch match) {
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
                                                      HttpRouter.StreamMatch match,
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
