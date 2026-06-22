/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.http.routing;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpStreamHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Transport-agnostic HTTP request router. Matches on (method, path) pairs using exact or prefix
 * semantics. Provides HEAD→GET fallback per RFC 9110 §9.3.2. Thread-safe after construction.
 *
 * <p>Build with {@link #builder()}:
 * <pre>{@code
 * HttpRouter router = HttpRouter.builder()
 *     .route(HttpMethod.GET, "/health",    e -> e.respond(HttpStatus.OK))
 *     .route(HttpMethod.GET, "/api/users", usersHandler)
 *     .prefixRoute(HttpMethod.GET, "/static", staticFileHandler)
 *     .build();
 * }</pre>
 */
public final class HttpRouter implements HttpHandler {

    private static final HttpHandler DEFAULT_NOT_FOUND = exchange ->
            exchange.respond(HttpStatus.NOT_FOUND);

    private final List<RouteEntry> exactRoutes;
    private final List<RouteEntry> prefixRoutes;
    private final Map<StreamRouteKey, HttpStreamHandler> streamRoutes;
    private final HttpHandler notFoundHandler;

    private HttpRouter(List<RouteEntry> exactRoutes,
                       List<RouteEntry> prefixRoutes,
                       Map<StreamRouteKey, HttpStreamHandler> streamRoutes,
                       HttpHandler notFoundHandler) {
        this.exactRoutes = List.copyOf(exactRoutes);
        this.prefixRoutes = List.copyOf(prefixRoutes);
        this.streamRoutes = Map.copyOf(streamRoutes);
        this.notFoundHandler = notFoundHandler;
    }

    /**
     * Resolves a streaming-flagged route to its {@link HttpStreamHandler}, or {@code null} when the
     * route is not registered as a stream (ADR-043 obligation 7).
     *
     * <p>A streaming route resolves <em>only</em> here, never through {@link #handle(HttpExchange)} —
     * so a streaming route never delivers a respond-once {@link HttpExchange}, and a respond-once route
     * never resolves to an {@link HttpStreamHandler}. The transport tier consults this first; on a hit
     * it opens an {@code HttpStreamExchange}, otherwise it falls back to respond-once dispatch.
     *
     * @param method request method
     * @param path   request path (query stripped)
     * @return the stream handler for a streaming route, or {@code null}
     */
    public HttpStreamHandler resolveStream(HttpMethod method, String path) {
        return streamRoutes.get(new StreamRouteKey(method, stripQuery(path)));
    }

    /**
     * Returns {@code true} if {@code (method, path)} is registered as a streaming route.
     *
     * @param method request method
     * @param path   request path (query stripped)
     * @return whether the route is streaming
     */
    public boolean isStreamRoute(HttpMethod method, String path) {
        return streamRoutes.containsKey(new StreamRouteKey(method, stripQuery(path)));
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public void handle(HttpExchange exchange) {
        String path = stripQuery(exchange.request().path());
        HttpMethod method = exchange.request().method();

        HttpHandler handler = resolve(method, path);
        if (handler != null) {
            handler.handle(exchange);
            return;
        }

        // HEAD → GET fallback per RFC 9110 §9.3.2
        if (method == HttpMethod.HEAD) {
            handler = resolve(HttpMethod.GET, path);
            if (handler != null) {
                handler.handle(exchange);
                return;
            }
        }

        notFoundHandler.handle(exchange);
    }

    private HttpHandler resolve(HttpMethod method, String path) {
        for (RouteEntry entry : exactRoutes) {
            if (entry.method() == method && entry.path().equals(path)) {
                return entry.handler();
            }
        }
        for (RouteEntry entry : prefixRoutes) {
            if (entry.method() == method && matchesPrefix(path, entry.path())) {
                return entry.handler();
            }
        }
        return null;
    }

    private static boolean matchesPrefix(String path, String prefix) {
        if (!path.startsWith(prefix)) {
            return false;
        }
        int len = prefix.length();
        return path.length() == len || path.charAt(len) == '/';
    }

    private static String stripQuery(String path) {
        int idx = path.indexOf('?');
        return idx < 0 ? path : path.substring(0, idx);
    }

    private record RouteEntry(HttpMethod method, String path, HttpHandler handler) {}

    private record StreamRouteKey(HttpMethod method, String path) {}

    public static final class Builder {

        private static final String HANDLER_PARAM = "handler";
        private static final String METHOD_PARAM = "method";

        private final List<RouteEntry> exactRoutes = new ArrayList<>();
        private final List<RouteEntry> prefixRoutes = new ArrayList<>();
        private final Map<StreamRouteKey, HttpStreamHandler> streamRoutes = new HashMap<>();
        private HttpHandler notFoundHandler = DEFAULT_NOT_FOUND;

        private Builder() {}

        /** Registers a single (method, path) → handler exact route. */
        public Builder route(HttpMethod method, String path, HttpHandler handler) {
            Objects.requireNonNull(method, METHOD_PARAM);
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(handler, HANDLER_PARAM);
            exactRoutes.add(new RouteEntry(method, path, handler));
            return this;
        }

        /** Registers one handler for a path under multiple HTTP methods. */
        public Builder route(HttpHandler handler, String path, HttpMethod... methods) {
            Objects.requireNonNull(handler, HANDLER_PARAM);
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(methods, "methods");
            for (HttpMethod method : methods) {
                Objects.requireNonNull(method, METHOD_PARAM);
                exactRoutes.add(new RouteEntry(method, path, handler));
            }
            return this;
        }

        /**
         * Registers a prefix route. A trailing {@code /*} suffix is stripped automatically.
         * Matches require a path boundary ({@code /}) or exact length match
         * to prevent partial-segment false positives (e.g. {@code /api} does not match
         * {@code /apiv2}).
         */
        public Builder prefixRoute(HttpMethod method, String pathPrefix, HttpHandler handler) {
            Objects.requireNonNull(method, METHOD_PARAM);
            Objects.requireNonNull(pathPrefix, "pathPrefix");
            Objects.requireNonNull(handler, HANDLER_PARAM);
            String normalized = pathPrefix.endsWith("/*")
                    ? pathPrefix.substring(0, pathPrefix.length() - 2)
                    : pathPrefix;
            prefixRoutes.add(new RouteEntry(method, normalized, handler));
            return this;
        }

        /**
         * Registers a streaming (SSE) route resolving to an {@link HttpStreamHandler}, distinct from the
         * respond-once {@code (method, path) → HttpHandler} table (ADR-043 obligation 7). A streaming
         * route never delivers a respond-once {@link HttpExchange}; it is opened as an
         * {@code HttpStreamExchange} by the transport tier.
         *
         * @param method  request method
         * @param path    exact request path
         * @param handler the streaming handler
         * @return this builder
         */
        public Builder streamRoute(HttpMethod method, String path, HttpStreamHandler handler) {
            Objects.requireNonNull(method, METHOD_PARAM);
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(handler, HANDLER_PARAM);
            streamRoutes.put(new StreamRouteKey(method, path), handler);
            return this;
        }

        /** Overrides the default 404 handler. */
        public Builder notFound(HttpHandler handler) {
            this.notFoundHandler = Objects.requireNonNull(handler, HANDLER_PARAM);
            return this;
        }

        /** Builds the immutable router and emits a JFR lifecycle event. */
        public HttpRouter build() {
            HttpRouterRegisteredEvent.emit(exactRoutes.size(), prefixRoutes.size());
            return new HttpRouter(exactRoutes, prefixRoutes, streamRoutes, notFoundHandler);
        }
    }
}
