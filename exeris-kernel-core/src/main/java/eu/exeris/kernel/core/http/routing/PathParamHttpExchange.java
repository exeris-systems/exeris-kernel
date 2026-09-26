/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.routing;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpTypedResponse;

import java.util.Map;
import java.util.Objects;

/**
 * Exchange decorator that overlays router-captured path parameters onto a delegate
 * {@link HttpExchange}, without coupling the router to any concrete transport-side
 * exchange implementation.
 *
 * <p>The router constructs one of these only when a request resolves through a
 * templated route ({@code /x/{id}}); exact and prefix routes hand the delegate to the
 * handler unchanged. All request/response behavior — including ownership transfer of a
 * {@link eu.exeris.kernel.spi.memory.LoanedBuffer} response body and the
 * respond-exactly-once contract — is delegated verbatim; only {@link #pathParams()} is
 * overridden. The two concrete {@code respond} entry points
 * ({@link #respond(HttpResponse)} and {@link #respond(HttpTypedResponse)}) are forwarded
 * so the SPI convenience overloads route to the real exchange.
 */
final class PathParamHttpExchange implements HttpExchange {

    private final HttpExchange delegate;
    private final Map<String, String> pathParams;

    // Package-private: constructed only by HttpRouter on a templated-route hit.
    /* default */ PathParamHttpExchange(HttpExchange delegate, Map<String, String> pathParams) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.pathParams = Objects.requireNonNull(pathParams, "pathParams");
    }

    @Override
    public HttpRequest request() {
        return delegate.request();
    }

    @Override
    public Map<String, String> pathParams() {
        return pathParams;
    }

    @Override
    public void respond(HttpResponse response) {
        delegate.respond(response);
    }

    @Override
    public void respond(HttpTypedResponse response) {
        delegate.respond(response);
    }
}
