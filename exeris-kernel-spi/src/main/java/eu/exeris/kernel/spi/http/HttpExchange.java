/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.http;

import eu.exeris.kernel.spi.exceptions.http.HttpException;

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
 * @since 0.5.0
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
     * @throws HttpException
     *         if the response cannot be written (connection reset, etc.)
     * @throws IllegalStateException if {@code respond} has already been called on this exchange
     */
    void respond(HttpResponse response);

    /**
     * Convenience overload — writes a bodyless response using the request's version.
     *
     * @param status response status; must not be {@code null}
     * @throws HttpException if the response cannot be written
     * @throws IllegalStateException if {@code respond} has already been called on this exchange
     */
    default void respond(HttpStatus status) {
        respond(HttpResponse.noBody(status, request().version()));
    }
}




