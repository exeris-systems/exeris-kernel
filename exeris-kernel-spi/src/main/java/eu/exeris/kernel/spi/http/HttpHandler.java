/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

import eu.exeris.kernel.spi.exceptions.http.HttpException;

/**
 * SPI: Application-level HTTP request handler — the single extension point
 * between the kernel HTTP engine and business logic.
 *
 * <h2>Contract</h2>
 * <p>Implementations receive a fully-parsed {@link HttpExchange} and MUST call
 * {@link HttpExchange#respond(HttpResponse)} exactly once before returning. The
 * engine enforces this contract: if the handler returns without calling
 * {@code respond}, the engine sends a {@link HttpStatus#INTERNAL_SERVER_ERROR}.
 * If the handler throws an {@link HttpException}, the engine sends the appropriate
 * error response and closes the connection.
 *
 * <h2>Threading Model</h2>
 * <p>Each invocation runs on its own virtual thread (1 VT per request). Handlers
 * MUST NOT park carrier threads (no {@code synchronized} blocks on identity objects,
 * no {@link ThreadLocal} access). {@code ScopedValue} is the correct mechanism for
 * propagating request-scoped state.
 *
 * <h2>No Magic DI</h2>
 * <p>Handlers are plain Java objects. Dependencies are injected via constructors.
 * No Spring, Guice, or Jakarta Inject annotations are permitted.
 *
 * @since 0.5
 */
@FunctionalInterface
public interface HttpHandler {

    /**
     * Handles the given HTTP exchange.
     *
     * <p>The implementation MUST call {@link HttpExchange#respond(HttpResponse)}
     * exactly once before returning normally. Throwing {@link HttpException} is
     * the correct way to signal a protocol-level or application-level error.
     *
     * @param exchange the exchange to handle; never {@code null}
     */
    void handle(HttpExchange exchange);
}



