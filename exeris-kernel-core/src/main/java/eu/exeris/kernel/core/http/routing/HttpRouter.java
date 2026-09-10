/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.routing;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpStreamHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Transport-agnostic HTTP request router. Matches on (method, path) pairs using exact,
 * path-template ({@code {id}} placeholder), or prefix semantics. Provides HEAD→GET fallback
 * per RFC 9110 §9.3.2. Thread-safe after construction.
 *
 * <p>Build with {@link #builder()}:
 * {@snippet lang="java" :
 * HttpRouter router = HttpRouter.builder()
 *     .route(HttpMethod.GET, "/health",      e -> e.respond(HttpStatus.OK))
 *     .route(HttpMethod.GET, "/x",           collectionHandler)
 *     .route(HttpMethod.GET, "/x/{id}",      byIdHandler)
 *     .prefixRoute(HttpMethod.GET, "/static", staticFileHandler)
 *     .build();
 * }
 *
 * <h2>Resolution precedence</h2>
 * <p>An exact route always wins over a template route, which always wins over a prefix route.
 * A path registered with at least one {@code {name}} segment is matched as a template:
 * each placeholder captures the corresponding request segment and is exposed to the handler
 * via {@link HttpExchange#pathParams()}.
 *
 * <p>The streaming table follows the same rules — exact before template, same placeholder syntax,
 * captured values reaching the handler through
 * {@link eu.exeris.kernel.spi.http.HttpStreamExchange#pathParams()}. A registration that cannot match
 * is unrepresentable — a malformed brace throws at {@link Builder#streamRoute}, and a well-formed one
 * is compiled as a template.
 */
public final class HttpRouter implements HttpHandler {

    private static final HttpHandler DEFAULT_NOT_FOUND = exchange ->
            exchange.respond(HttpStatus.NOT_FOUND);

    private final List<RouteEntry> exactRoutes;
    private final List<PathTemplateRoute> templateRoutes;
    private final List<RouteEntry> prefixRoutes;
    private final StreamRouteTable streamRoutes;
    private final HttpHandler notFoundHandler;

    private HttpRouter(List<RouteEntry> exactRoutes,
                       List<PathTemplateRoute> templateRoutes,
                       List<RouteEntry> prefixRoutes,
                       StreamRouteTable streamRoutes,
                       HttpHandler notFoundHandler) {
        this.exactRoutes = List.copyOf(exactRoutes);
        this.templateRoutes = List.copyOf(templateRoutes);
        this.prefixRoutes = List.copyOf(prefixRoutes);
        this.streamRoutes = streamRoutes;
        this.notFoundHandler = notFoundHandler;
    }

    /**
     * Resolves a streaming-flagged route, or {@code null} when the route is not registered as a stream
     * (ADR-043 obligation 7).
     *
     * <p>A streaming route resolves <em>only</em> here, never through {@link #handle(HttpExchange)} —
     * so a streaming route never delivers a respond-once {@link HttpExchange}, and a respond-once route
     * never resolves to an {@link HttpStreamHandler}. The transport tier consults this first; on a hit
     * it opens an {@code HttpStreamExchange}, otherwise it falls back to respond-once dispatch.
     *
     * <p>Exact stream routes win over template stream routes, mirroring the respond-once precedence:
     * a deployment that registers both a literal and a templated path meant the literal to be special.
     *
     * @param method request method
     * @param path   request path (query stripped)
     * @return the resolved stream route, or {@code null}
     */
    public StreamMatch resolveStream(HttpMethod method, String path) {
        return streamRoutes.resolve(method, stripQuery(path));
    }

    /**
     * Returns {@code true} if {@code (method, path)} is registered as a streaming route.
     *
     * @param method request method
     * @param path   request path (query stripped)
     * @return whether the route is streaming
     */
    public boolean isStreamRoute(HttpMethod method, String path) {
        return resolveStream(method, path) != null;
    }

    /**
     * Creates a new, empty builder for assembling routes before compiling them into a router.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public void handle(HttpExchange exchange) {
        String path = stripQuery(exchange.request().path());
        HttpMethod method = exchange.request().method();

        RouteMatch match = resolve(method, path);
        if (match != null) {
            dispatch(match, exchange);
            return;
        }

        // HEAD → GET fallback per RFC 9110 §9.3.2
        if (method == HttpMethod.HEAD) {
            match = resolve(HttpMethod.GET, path);
            if (match != null) {
                dispatch(match, exchange);
                return;
            }
        }

        notFoundHandler.handle(exchange);
    }

    private static void dispatch(RouteMatch match, HttpExchange exchange) {
        if (match.params().isEmpty()) {
            match.handler().handle(exchange);
        } else {
            match.handler().handle(new PathParamHttpExchange(exchange, match.params()));
        }
    }

    // Resolution precedence: exact wins over template, which wins over prefix.
    private RouteMatch resolve(HttpMethod method, String path) {
        RouteMatch exact = resolveExact(method, path);
        if (exact != null) {
            return exact;
        }
        RouteMatch template = resolveTemplate(method, path);
        if (template != null) {
            return template;
        }
        for (RouteEntry entry : prefixRoutes) {
            if (entry.method() == method && matchesPrefix(path, entry.path())) {
                return new RouteMatch(entry.handler(), Map.of());
            }
        }
        return null;
    }

    private RouteMatch resolveExact(HttpMethod method, String path) {
        for (RouteEntry entry : exactRoutes) {
            if (entry.method() == method && entry.path().equals(path)) {
                return new RouteMatch(entry.handler(), Map.of());
            }
        }
        return null;
    }

    private RouteMatch resolveTemplate(HttpMethod method, String path) {
        for (PathTemplateRoute template : templateRoutes) {
            if (template.method() == method) {
                RouteMatch match = template.toMatch(path);
                if (match != null) {
                    return match;
                }
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

    /**
     * A resolved streaming route: the handler, and whatever its template captured.
     *
     * @param handler the streaming handler to drive
     * @param params  captured path parameters; empty for an exact stream route
     */
    public record StreamMatch(HttpStreamHandler handler, Map<String, String> params) {

        /**
         * A match with nothing captured — what an exact stream route resolves to.
         *
         * @param handler the streaming handler
         * @return the match; never {@code null}
         */
        public static StreamMatch exact(HttpStreamHandler handler) {
            return new StreamMatch(handler, Map.of());
        }
    }

    /**
     * Mutable accumulator for route registrations, compiled into an immutable {@link HttpRouter} by
     * {@link #build()}. Precedence across route kinds — exact, then template, then prefix — is fixed
     * regardless of registration order; within the same kind, the first registration that matches a
     * given request wins.
     *
     * <p>Not thread-safe: a {@code Builder} instance is meant to be populated and built from a single
     * thread during application startup.
     */
    public static final class Builder {

        private static final String HANDLER_PARAM = "handler";
        private static final String METHOD_PARAM = "method";

        private final List<RouteEntry> exactRoutes = new ArrayList<>();
        private final List<PathTemplateRoute> templateRoutes = new ArrayList<>();
        private final List<RouteEntry> prefixRoutes = new ArrayList<>();
        private final StreamRouteTable.Builder streamRoutes = new StreamRouteTable.Builder();
        private HttpHandler notFoundHandler = DEFAULT_NOT_FOUND;

        private Builder() {}

        /**
         * Registers a single (method, path) → handler route. A path containing one or more
         * {@code {name}} segments is registered as a path-template route (captured into
         * {@link HttpExchange#pathParams()}); otherwise it is an exact route.
         *
         * @param method  the request method to match
         * @param path    the route path, as a literal or a {@code {name}}-templated pattern
         * @param handler the handler to dispatch to on a match
         * @return this builder
         * @throws IllegalArgumentException if {@code path} contains a malformed brace segment
         *     (an unbalanced {@code {}/{@code }} that is not a well-formed {@code {name}} placeholder)
         */
        public Builder route(HttpMethod method, String path, HttpHandler handler) {
            Objects.requireNonNull(method, METHOD_PARAM);
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(handler, HANDLER_PARAM);
            addRoute(method, path, handler);
            return this;
        }

        /**
         * Registers one handler for a path under multiple HTTP methods (template-aware, as
         * {@link #route(HttpMethod, String, HttpHandler)}).
         *
         * @param handler the handler to dispatch to on a match
         * @param path    the route path, as a literal or a {@code {name}}-templated pattern
         * @param methods the request methods to register the handler under
         * @return this builder
         */
        public Builder route(HttpHandler handler, String path, HttpMethod... methods) {
            Objects.requireNonNull(handler, HANDLER_PARAM);
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(methods, "methods");
            for (HttpMethod method : methods) {
                Objects.requireNonNull(method, METHOD_PARAM);
                addRoute(method, path, handler);
            }
            return this;
        }

        private void addRoute(HttpMethod method, String path, HttpHandler handler) {
            // The shared predicate, not a second copy of it: one type decides what counts as a template
            // for both tables, which is the whole reason PathTemplate was extracted.
            if (PathTemplate.isTemplate(path)) {
                templateRoutes.add(PathTemplateRoute.compile(method, path, handler));
            } else {
                exactRoutes.add(new RouteEntry(method, path, handler));
            }
        }

        /**
         * Registers a prefix route. A trailing {@code /*} suffix is stripped automatically.
         * Matches require a path boundary ({@code /}) or exact length match
         * to prevent partial-segment false positives (e.g. {@code /api} does not match
         * {@code /apiv2}).
         *
         * @param method     the request method to match
         * @param pathPrefix the path prefix to match against, with or without a trailing {@code /*}
         * @param handler    the handler to dispatch to on a match
         * @return this builder
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
         * @param method  the request method to match
         * @param path    the route path, as a literal or a {@code {name}}-templated pattern
         * @param handler the streaming handler to drive
         * @return this builder
         */
        public Builder streamRoute(HttpMethod method, String path, HttpStreamHandler handler) {
            Objects.requireNonNull(method, METHOD_PARAM);
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(handler, HANDLER_PARAM);
            streamRoutes.add(method, path, handler);
            return this;
        }

        /**
         * Overrides the default 404 handler.
         *
         * @param handler the handler to invoke when no route matches
         * @return this builder
         */
        public Builder notFound(HttpHandler handler) {
            this.notFoundHandler = Objects.requireNonNull(handler, HANDLER_PARAM);
            return this;
        }

        /**
         * Builds the immutable router and emits a JFR lifecycle event.
         *
         * @return the immutable router
         */
        public HttpRouter build() {
            HttpRouterRegisteredEvent.emit(exactRoutes.size(), templateRoutes.size(), prefixRoutes.size());
            return new HttpRouter(exactRoutes, templateRoutes, prefixRoutes, streamRoutes.build(),
                    notFoundHandler);
        }
    }
}
