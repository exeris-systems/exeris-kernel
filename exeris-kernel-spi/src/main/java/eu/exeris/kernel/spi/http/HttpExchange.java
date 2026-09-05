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
     * @since 0.10
     */
    default Map<String, String> pathParams() {
        return Map.of();
    }

    /**
     * Writes the given response to the wire and finalises this exchange.
     *
     * <p><strong>Ownership transfer:</strong> if {@link HttpResponse#body()} is
     * non-null, the engine takes ownership of the {@link eu.exeris.kernel.spi.memory.LoanedBuffer}
     * and releases it after the write completes. The caller MUST NOT close or retain the
     * buffer after this call.
     *
     * <p>This method MUST be called exactly once per exchange. Calling it a second time
     * throws {@link IllegalStateException}.
     *
     * @param response response to write; must not be {@code null}
     * @throws IllegalStateException if {@code respond} has already been called on this exchange
     */
    void respond(HttpResponse response);

    /**
     * Writes a typed response payload using the exchange's response-encoding pipeline.
     *
     * <p>This is an additive path for auto-binding payload responses. Implementations that
     * do not support typed response encoding may keep legacy behavior and throw
     * {@link UnsupportedOperationException}.
     *
     * @param response typed response payload descriptor; must not be {@code null}
     * @throws IllegalStateException if {@code respond} has already been called on this exchange
     * @throws UnsupportedOperationException if typed response encoding is not supported
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
     */
    default void respond(HttpStatus status, Object payload) {
        respond(HttpTypedResponse.of(status, payload));
    }

    /**
     * Convenience overload — writes a bodyless response using the request's version.
     *
     * @param status response status; must not be {@code null}
     * @throws IllegalStateException if {@code respond} has already been called on this exchange
     */
    default void respond(HttpStatus status) {
        respond(HttpResponse.noBody(status, request().version()));
    }
}




