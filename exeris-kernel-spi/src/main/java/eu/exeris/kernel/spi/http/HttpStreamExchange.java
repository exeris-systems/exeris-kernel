/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

import eu.exeris.kernel.spi.exceptions.http.StreamClosedException;

import java.util.Map;

/**
 * SPI: A long-lived, server-initiated HTTP push exchange — Server-Sent Events (SSE).
 *
 * <p>This is the <strong>sibling</strong> of the respond-once {@link HttpExchange}, not a mode on it.
 * Where {@code HttpExchange} writes exactly one response and finalises, an {@code HttpStreamExchange}
 * stays open and {@link #emit(StreamEvent) emits} events repeatedly until the stream closes. The
 * respond-once invariant of {@code HttpExchange} is deliberately left intact and unconditional;
 * streaming is a separate, opt-in surface the router selects from route metadata (ADR-043).
 *
 * <h2>Threading Model</h2>
 * <p>Each {@code HttpStreamExchange} is owned by exactly one virtual thread — the "1 VT per stream"
 * model, mirroring "1 VT per request". The handler body is an imperative emit loop. Handlers MUST NOT
 * park carrier threads (no {@code synchronized} on identity objects, no {@link ThreadLocal});
 * {@code ScopedValue} is the correct mechanism for stream-scoped state.
 *
 * <h2>Backpressure — park the VT, never an on-heap queue</h2>
 * <p>{@link #emit(StreamEvent)} is flow-aware. When the egress window (TLS record / HTTP/2 flow-control
 * window) is full, {@code emit} <strong>blocks the calling virtual thread</strong> until window credit is
 * available — exactly as the transport's response-body write loop already does. It MUST NOT buffer to an
 * unbounded heap queue (No Waste Compute). A virtual thread parked in {@code emit} costs nothing but its
 * stack; there is no backpressure timeout in this contract (see {@link #emit(StreamEvent)}).
 *
 * <h2>Disconnect Signal</h2>
 * <p>There is no {@code awaitDisconnect()}. On client disconnect, dead key, or fail-closed teardown, the
 * <em>next</em> {@link #emit(StreamEvent)} throws the unchecked {@link StreamClosedException}: the emit loop
 * unwinds naturally and the engine reclaims the stream — no parked-VT leak.
 *
 * <h2>Lifecycle</h2>
 * <pre>
 *   engine resolves a streaming route → handler.handle(streamExchange)
 *   engine has already written the SSE response head (200, text/event-stream, no-cache)
 *   handler calls exchange.emit(event)   → frames + writes one SSE event (parks under backpressure)
 *   ... repeated until the producer is exhausted or the stream closes ...
 *   handler calls exchange.close()       → graceful end-of-stream (FIN); or
 *   emit() throws StreamClosedException   → peer gone / token expired; loop unwinds
 *   handler returns                       → engine runs teardown
 * </pre>
 *
 * <h2>The Wall</h2>
 * <p>This interface carries no wire-format types (chunk headers, HPACK tables, {@code text/event-stream}
 * literals, io_uring SQEs). SSE framing is owned by Core; the held-open transport mechanics live in the
 * Community (NIO) and Enterprise (native) tiers.
 *
 * @since 0.10
 */
public interface HttpStreamExchange {

    /**
     * Returns the inbound request that opened this stream.
     *
     * <p>The request is immutable and fully parsed before {@link HttpStreamHandler#handle} is invoked.
     * Notably, {@link HttpRequest} exposes the {@code Last-Event-ID} header (case-insensitive accessor)
     * so a handler can resume a replayable stream from the client's last seen event id.
     *
     * @return the inbound request; never {@code null}
     */
    HttpRequest request();

    /**
     * Returns the path parameters captured by the router from a templated streaming route.
     *
     * <p>The streaming counterpart of {@link HttpExchange#pathParams()}, with the same semantics: a
     * stream route registered as {@code /entities/{id}/events} and opened on {@code /entities/42/events}
     * yields {@code pathParams().get("id") == "42"}. An exact stream route, or any exchange not opened
     * through a template, returns an empty map.
     *
     * <p>Without this a templated stream route would resolve and then drop the one value that made it
     * worth templating — the handler would know a stream is open, but not what it is a stream *of*.
     *
     * <p>The returned map is immutable and carries no wire-format types — values are the already-decoded
     * path segments as received on the request target.
     *
     * @return an immutable map of captured path parameters; never {@code null}, empty when the route
     *         declared no placeholders
     * @since 0.11
     */
    default Map<String, String> pathParams() {
        return Map.of();
    }

    /**
     * Frames and writes a single SSE event to the wire.
     *
     * <p><strong>Backpressure:</strong> if the egress window is full, this call parks the calling virtual
     * thread until credit is available. There is no timeout — the park ends on credit or on disconnect.
     *
     * <p><strong>Ownership transfer:</strong> where the event payload is framed into a
     * {@link eu.exeris.kernel.spi.memory.LoanedBuffer}, the engine takes ownership and releases it after the
     * write completes; the caller MUST NOT close or retain it — identical to
     * {@link HttpExchange#respond(HttpResponse)}. No defensive copy is made on the emit path.
     *
     * @param event the event to emit; must not be {@code null}
     * @throws StreamClosedException if the stream has closed (peer disconnect, graceful close, or
     *                               fail-closed teardown on token expiry); unchecked, so an imperative
     *                               emit loop unwinds naturally
     */
    void emit(StreamEvent event);

    /**
     * Gracefully ends the stream (writes end-of-stream / FIN and finalises this exchange).
     *
     * <p>Idempotent: calling {@code close} on an already-closed stream is a no-op and never throws. After
     * {@code close}, any subsequent {@link #emit(StreamEvent)} throws {@link StreamClosedException}.
     */
    void close();
}
