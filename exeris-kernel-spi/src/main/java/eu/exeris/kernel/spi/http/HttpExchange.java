/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

import java.util.Map;

/**
 * SPI: A single HTTP request–response exchange lifecycle.
 *
 * <h2>Threading Model</h2>
 * <p>Each {@code HttpExchange} is owned by exactly one virtual thread — the
 * "1 VT per request" model. Calls to {@link #respond(HttpResponse)} are
 * synchronous from the handler's perspective: the engine writes the response
 * to the wire (or enqueues it for the carrier loop) and returns.
 *
 * <h2>Lifecycle</h2>
 * <pre>
 *   engine delivers exchange → handler.handle(exchange)
 *   handler calls exchange.request()          → reads inbound headers + body
 *   handler calls exchange.respond(response)  → writes outbound response (once)
 *   handler returns                           → exchange is closed by engine
 * </pre>
 *
 * <h2>The Wall</h2>
 * <p>This interface carries no wire-format types (frame headers, HPACK tables,
 * io_uring SQEs). Implementations may be backed by HTTP/1.1, HTTP/2, or HTTP/3
 * transports transparently.
 *
 * <p><b>Thread confinement:</b> owner thread — one exchange belongs to the single virtual thread
 * the engine dispatched it on, and neither the exchange nor the buffers reachable through it may be
 * touched from another thread
 * <p><b>Ownership:</b> the engine owns both bodies — the request buffer for the duration of the
 * handler call, and the response buffer from {@link #respond(HttpResponse)} onwards, which it
 * releases after the write; a handler that needs the request body beyond the call must
 * {@code retain()} its own reference and close it
 *
 * @since 0.5
 */
public interface HttpExchange {

    /**
     * Returns the inbound request for this exchange.
     *
     * <p>The request is immutable and fully parsed before
     * {@link HttpHandler#handle(HttpExchange)} is invoked. Streaming body reads
     * are deferred: {@link HttpRequest#body()} returns the body buffer only when
     * the full body has been received and assembled by the engine.
     *
     * @return the inbound request; never {@code null}
     */
    HttpRequest request();

    /**
     * Returns the path parameters captured by the router from a templated route.
     *
     * <p>When a request resolves through a path-template route (e.g. registering
     * {@code /entities/{id}} and serving {@code /entities/42}), the router captures
     * each {@code {name}} placeholder and exposes the bound value here — so
     * {@code pathParams().get("id")} yields {@code "42"}. For exact or prefix routes,
     * and for any exchange not dispatched through a templated route, this returns an
     * empty map.
     *
     * <p>The returned map is immutable and carries no wire-format types — values are
     * the already-decoded path segments as received on the request target.
     *
     * @return an immutable map of captured path parameters; never {@code null}, empty
     *         when the route declared no placeholders
     * @implSpec The default implementation returns an empty map, which is the correct answer for
     *           an exchange never dispatched through a template. An implementation that overrides
     *           it must return an immutable map: the captured values are routing state, so a
     *           handler able to write to it could change what a later request resolves to.
     * @since 0.10
     */
    default Map<String, String> pathParams() {
        return Map.of();
    }

    /**
     * Writes the given response to the wire and finalises this exchange.
     *
     * @param response response to write; must not be {@code null}
     * @throws IllegalStateException if {@code respond} has already been called on this exchange
     * @implSpec An implementation takes ownership of a non-null {@link HttpResponse#body()} and
     *           releases that {@link eu.exeris.kernel.spi.memory.LoanedBuffer} exactly once, after
     *           the write completes — on the failure path as well, or the segment never returns to
     *           the pool. The respond-once invariant is unconditional: a second call throws rather
     *           than writing a second response.
     * @apiNote Call this exactly once per exchange, and do not close or retain the body buffer
     *          afterwards — it is the engine's from here on.
     */
    void respond(HttpResponse response);

    /**
     * Encodes the payload through the exchange's response-encoding pipeline and writes the result,
     * so a handler can answer with a domain object instead of assembling the body itself.
     *
     * @param response typed response payload descriptor; must not be {@code null}
     * @throws IllegalStateException if {@code respond} has already been called on this exchange
     * @throws UnsupportedOperationException if typed response encoding is not supported
     * @implSpec The default implementation throws {@link UnsupportedOperationException}: typed
     *           response encoding is an opt-in path, and an exchange with no encoder registry
     *           refuses it rather than silently writing a bodyless response. An implementation
     *           that overrides it consumes the respond-once budget exactly as
     *           {@link #respond(HttpResponse)} does.
     */
    default void respond(HttpTypedResponse response) {
        throw new UnsupportedOperationException("Typed response encoding is not supported by this exchange");
    }

    /**
     * Convenience overload for typed payload responses with no extra headers.
     *
     * @param status response status; must not be {@code null}
     * @param payload payload object to encode; may be {@code null}
     * @throws IllegalStateException if {@code respond} has already been called on this exchange
     * @throws UnsupportedOperationException if typed response encoding is not supported
     * @implSpec The default implementation delegates to {@link #respond(HttpTypedResponse)} with an
     *           empty header list.
     */
    default void respond(HttpStatus status, Object payload) {
        respond(HttpTypedResponse.of(status, payload));
    }

    /**
     * Convenience overload — writes a bodyless response using the request's version.
     *
     * @param status response status; must not be {@code null}
     * @throws IllegalStateException if {@code respond} has already been called on this exchange
     * @implSpec The default implementation delegates to {@link #respond(HttpResponse)} with a
     *           bodyless response carrying no headers and the inbound request's protocol version,
     *           so a status-only answer cannot disagree with the version it is written on.
     */
    default void respond(HttpStatus status) {
        respond(HttpResponse.noBody(status, request().version()));
    }
}




