/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.core.http.routing.HttpRouter;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpStreamHandler;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.http.StreamMatch;
import eu.exeris.kernel.spi.http.StreamRouteResolver;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The driver resolves a stream route through the bound handler's {@link StreamRouteResolver}, whatever
 * the handler's class (ADR-043 obligation 7, amendment A1).
 *
 * <p>An application does not always bind the router itself: a generated one binds a forwarder over a
 * router built inside the boot callback, and a wrapper that adds bindings of its own is another
 * handler again. Every case here binds something that is <em>not</em> an {@link HttpRouter} and drives
 * the production path, {@code resolveStreamHandler} then {@code dispatchStream}, so a driver that
 * recognised stream routes only on the concrete router class fails every positive case.
 *
 * <p>Each handler records what it observed into a reference that starts {@code null} and sets a
 * ran-flag, and each positive case asserts that the route resolved and the handler ran before it
 * asserts anything the handler saw: an assertion that the handler saw nothing must not pass because it
 * never ran.
 *
 * @since 0.12
 */
@DisplayName("Community: a stream route resolves through the bound handler, whatever its class")
class CommunityStreamResolutionDelegationTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    /** A binding of the wrapper's own, standing in for a tenant or a storage context. */
    private static final ScopedValue<String> PROBE = ScopedValue.newInstance();

    @AfterAll
    static void closeAllocator() {
        ALLOCATOR.close();
    }

    @Test
    @DisplayName("A1: a wrapper that delegates resolves the router's exact stream route")
    void wrapperDelegatesExactStreamRoute() {
        AtomicBoolean ran = new AtomicBoolean();
        HttpRouter router = HttpRouter.builder()
                .streamRoute(HttpMethod.GET, "/orders/stream", exchange -> {
                    ran.set(true);
                    exchange.close();
                })
                .build();
        HttpHandler bound = new DelegatingWrapper(router);

        StreamMatch match = resolveAndRun(bound, HttpMethod.GET, "/orders/stream");

        assertThat(match).as("the wrapper answered for the router, so the route resolves").isNotNull();
        assertThat(ran).as("and the stream handler ran on the dispatch path").isTrue();
    }

    @Test
    @DisplayName("A2: a template stream route resolves through the wrapper, and its parameters arrive")
    void wrapperDelegatesTemplateStreamRouteAndParamsArrive() {
        AtomicBoolean ran = new AtomicBoolean();
        AtomicReference<Map<String, String>> params = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        HttpRouter router = HttpRouter.builder()
                .streamRoute(HttpMethod.POST, "/orders/{id}/actions/ship", exchange -> {
                    ran.set(true);
                    params.set(exchange.pathParams());
                    path.set(exchange.request().path());
                    exchange.close();
                })
                .build();

        StreamMatch match = resolveAndRun(
                new DelegatingWrapper(router), HttpMethod.POST, "/orders/42/actions/ship");

        assertThat(match).isNotNull();
        assertThat(ran).isTrue();
        assertThat(params.get())
                .as("the driver applies the match's params to the exchange it hands the handler")
                .isEqualTo(Map.of("id", "42"));
        assertThat(path.get())
                .as("the request reaches the handler untouched")
                .isEqualTo("/orders/42/actions/ship");
    }

    @Test
    @DisplayName("A3: a plain lambda over a router resolves no stream route, and the driver does not cast it")
    void plainLambdaResolvesNoStream() {
        AtomicBoolean ran = new AtomicBoolean();
        HttpRouter router = HttpRouter.builder()
                .streamRoute(HttpMethod.GET, "/orders/stream", exchange -> {
                    ran.set(true);
                    exchange.close();
                })
                .build();
        assertThat(router.resolveStream(HttpMethod.GET, "/orders/stream"))
                .as("precondition: the router itself has the route")
                .isNotNull();
        HttpHandler bound = router::handle;

        StreamMatch match = resolveAndRun(bound, HttpMethod.GET, "/orders/stream");

        assertThat(match)
                .as("a handler that does not implement StreamRouteResolver serves respond-once only")
                .isNull();
        assertThat(ran).isFalse();
    }

    @Test
    @DisplayName("A4: the driver hands the resolver the method and the path exactly as received")
    void driverPassesPathAsReceived() {
        AtomicReference<HttpMethod> seenMethod = new AtomicReference<>();
        AtomicReference<String> seenPath = new AtomicReference<>();
        HttpHandler bound = new RecordingResolver(seenMethod, seenPath);

        StreamMatch match = resolveAndRun(bound, HttpMethod.GET, "/orders/stream?since=5");

        assertThat(match).as("the recording resolver declines every request").isNull();
        assertThat(seenPath.get())
                .as("the resolver was consulted, with the query string still on the path")
                .isEqualTo("/orders/stream?since=5");
        assertThat(seenMethod.get()).isEqualTo(HttpMethod.GET);
    }

    @Test
    @DisplayName("A5: a wrapper that answers null hides the router's stream route")
    void wrapperThatDeclinesHidesTheRoute() {
        AtomicBoolean ran = new AtomicBoolean();
        HttpRouter router = HttpRouter.builder()
                .streamRoute(HttpMethod.GET, "/orders/stream", exchange -> {
                    ran.set(true);
                    exchange.close();
                })
                .build();
        assertThat(router.resolveStream(HttpMethod.GET, "/orders/stream"))
                .as("precondition: the router itself has the route")
                .isNotNull();

        StreamMatch match = resolveAndRun(
                new DecliningWrapper(router), HttpMethod.GET, "/orders/stream");

        assertThat(match).as("the bound handler's answer is the driver's answer").isNull();
        assertThat(ran).isFalse();
    }

    @Test
    @DisplayName("A6: a wrapper that wraps the returned handler reaches the stream handler with its binding")
    void wrapperScopeReachesStreamHandler() {
        AtomicBoolean ran = new AtomicBoolean();
        AtomicReference<String> seen = new AtomicReference<>();
        HttpRouter router = HttpRouter.builder()
                .streamRoute(HttpMethod.GET, "/orders/stream", exchange -> {
                    ran.set(true);
                    seen.set(PROBE.isBound() ? PROBE.get() : "<unbound>");
                    exchange.close();
                })
                .build();

        StreamMatch match = resolveAndRun(
                new BindingWrapper(router, "acme"), HttpMethod.GET, "/orders/stream");

        assertThat(match).isNotNull();
        assertThat(ran).isTrue();
        assertThat(seen.get())
                .as("ScopedValue bindings are lexical: the wrapper's binding reaches the stream handler "
                        + "only because the handler it returned runs inside it")
                .isEqualTo("acme");
        assertThat(PROBE.isBound()).as("the binding does not outlive the stream").isFalse();
    }

    @Test
    @DisplayName("A7: a wrapper that only delegates leaves its binding off the stream handler")
    void wrapperThatOnlyDelegatesLeavesScopeUnbound() {
        AtomicReference<Boolean> probeBound = new AtomicReference<>();
        HttpRouter router = HttpRouter.builder()
                .streamRoute(HttpMethod.GET, "/orders/stream", exchange -> {
                    probeBound.set(PROBE.isBound());
                    exchange.close();
                })
                .build();
        assertThat(PROBE.isBound()).as("precondition: the harness binds nothing").isFalse();

        StreamMatch match = resolveAndRun(
                new DelegatingWrapper(router), HttpMethod.GET, "/orders/stream");

        assertThat(match).isNotNull();
        assertThat(probeBound.get()).as("ran-guard: the stream handler ran").isNotNull();
        assertThat(probeBound.get())
                .as("resolution runs outside every binding the wrapper could establish in handle()")
                .isFalse();
    }

    /**
     * Resolves through the production dispatcher and, when a route resolved, opens the stream over a
     * discarding transport, which runs the stream handler inline.
     */
    private static StreamMatch resolveAndRun(HttpHandler bound, HttpMethod method, String path) {
        CommunityHttpStreamDispatcher dispatcher = new CommunityHttpStreamDispatcher(ALLOCATOR);
        HttpRequest request = HttpRequest.noBody(method, path, HttpVersion.HTTP_1_1, List.of());
        StreamMatch match = dispatcher.resolveStreamHandler(request, bound);
        if (match != null) {
            dispatcher.dispatchStream(request, new DiscardingTransportStream(), match);
        }
        return match;
    }

    /** What a forwarder over a router looks like: not a router, delegating both halves. */
    private static final class DelegatingWrapper implements HttpHandler, StreamRouteResolver {

        private final HttpRouter router;

        DelegatingWrapper(HttpRouter router) {
            this.router = router;
        }

        @Override
        public void handle(HttpExchange exchange) {
            router.handle(exchange);
        }

        @Override
        public StreamMatch resolveStream(HttpMethod method, String path) {
            return router.resolveStream(method, path);
        }
    }

    /** The recipe from {@link StreamRouteResolver}: wrap the returned handler, forward params. */
    private static final class BindingWrapper implements HttpHandler, StreamRouteResolver {

        private final HttpRouter router;
        private final String value;

        BindingWrapper(HttpRouter router, String value) {
            this.router = router;
            this.value = value;
        }

        @Override
        public void handle(HttpExchange exchange) {
            ScopedValue.where(PROBE, value).run(() -> router.handle(exchange));
        }

        @Override
        public StreamMatch resolveStream(HttpMethod method, String path) {
            StreamMatch match = router.resolveStream(method, path);
            if (match == null) {
                return null;
            }
            HttpStreamHandler route = match.handler();
            return new StreamMatch(
                    exchange -> ScopedValue.where(PROBE, value).run(() -> route.handle(exchange)),
                    match.params());
        }
    }

    /** A wrapper that serves the router's respond-once routes and none of its stream routes. */
    private static final class DecliningWrapper implements HttpHandler, StreamRouteResolver {

        private final HttpRouter router;

        DecliningWrapper(HttpRouter router) {
            this.router = router;
        }

        @Override
        public void handle(HttpExchange exchange) {
            router.handle(exchange);
        }

        @Override
        public StreamMatch resolveStream(HttpMethod method, String path) {
            return null;
        }
    }

    /** Records what the driver asked, and declines. */
    private static final class RecordingResolver implements HttpHandler, StreamRouteResolver {

        private final AtomicReference<HttpMethod> method;
        private final AtomicReference<String> path;

        RecordingResolver(AtomicReference<HttpMethod> method, AtomicReference<String> path) {
            this.method = method;
            this.path = path;
        }

        @Override
        public void handle(HttpExchange exchange) {
            throw new AssertionError("a stream probe must not reach respond-once dispatch");
        }

        @Override
        public StreamMatch resolveStream(HttpMethod requestMethod, String requestPath) {
            method.set(requestMethod);
            path.set(requestPath);
            return null;
        }
    }
}
