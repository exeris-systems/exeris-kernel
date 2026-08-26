/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.routing;

import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpStreamExchange;
import eu.exeris.kernel.spi.http.StreamEvent;

import java.util.Map;
import java.util.Objects;

/**
 * Carries a templated stream route's captured parameters to the handler.
 *
 * <p>The streaming mirror of {@code PathParamHttpExchange}, and deliberately the same shape: a
 * decorator that adds one answer and forwards everything else. Adding the parameters to
 * {@code HttpStreamEngine} instead would have put routing state inside the wire engine, which is the
 * one place in the streaming path that should know nothing about how the route was chosen.
 *
 * <p>Only wrapped when a template actually captured something — an exact stream route gets the engine
 * itself, so the common path pays nothing.
 *
 * @since 0.11.0
 */
public final class PathParamStreamExchange implements HttpStreamExchange {

    private final HttpStreamExchange delegate;
    private final Map<String, String> pathParams;

    /**
     * Wraps a stream exchange with the parameters its route captured.
     *
     * @param delegate   the underlying stream exchange
     * @param pathParams captured parameters; copied, so a later mutation cannot reach the handler
     */
    public PathParamStreamExchange(HttpStreamExchange delegate, Map<String, String> pathParams) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.pathParams = Map.copyOf(Objects.requireNonNull(pathParams, "pathParams must not be null"));
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
    public void emit(StreamEvent event) {
        delegate.emit(event);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
