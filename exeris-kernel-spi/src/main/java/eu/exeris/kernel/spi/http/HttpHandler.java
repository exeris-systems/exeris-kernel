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
 * <p><b>Thread confinement:</b> owner thread — each invocation runs on its own virtual thread, one
 * per request, and the exchange it is given belongs to that thread alone
 * <p><b>Ownership:</b> the handler owns nothing it is handed — the engine owns the request body for
 * the duration of the call and takes the response body on
 * {@link HttpExchange#respond(HttpResponse)}; a handler outliving the exchange must
 * {@code retain()} its own reference to the request body and close it
 *
 * @implSpec An implementation:
 *           <ul>
 *             <li>MUST call {@link HttpExchange#respond(HttpResponse)} exactly once before
 *                 returning normally;</li>
 *             <li>MUST NOT park a carrier thread — no {@code synchronized} block on an identity
 *                 object, no {@link ThreadLocal} access; {@code ScopedValue} is the mechanism for
 *                 request-scoped state;</li>
 *             <li>is a plain Java object taking its dependencies through its constructor: no
 *                 Spring, Guice or Jakarta Inject annotation is honoured here.</li>
 *           </ul>
 * @apiNote The engine holds the handler to the respond-once rule rather than trusting it: a handler
 *          that returns without responding produces a
 *          {@link HttpStatus#INTERNAL_SERVER_ERROR}, and one that throws {@link HttpException}
 *          produces the mapped error response and a closed connection. Throwing
 *          {@code HttpException} is therefore the way to refuse a request, not a failure to answer
 *          it.
 * @since 0.5
 */
@FunctionalInterface
public interface HttpHandler {

    /**
     * Serves one request: reads what it needs from the exchange and answers it exactly once.
     *
     * @param exchange the exchange to handle; never {@code null}
     * @implSpec Call {@link HttpExchange#respond(HttpResponse)} exactly once before returning
     *           normally, or throw {@link HttpException} to signal a protocol-level or
     *           application-level error. Returning without either is a defect the engine covers
     *           with a 500 rather than a contract this method offers.
     */
    void handle(HttpExchange exchange);
}



