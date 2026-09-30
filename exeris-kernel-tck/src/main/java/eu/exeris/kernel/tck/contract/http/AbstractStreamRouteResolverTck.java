/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.http;

import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpStreamExchange;
import eu.exeris.kernel.spi.http.HttpStreamHandler;
import eu.exeris.kernel.spi.http.StreamEvent;
import eu.exeris.kernel.spi.http.StreamMatch;
import eu.exeris.kernel.spi.http.StreamRouteResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * TCK: contract for {@link StreamRouteResolver}, the per-request question a driver asks the bound
 * server handler before dispatch (ADR-043 obligation 7, amendment A1).
 *
 * <h2>What this suite is built to catch</h2>
 * <p>A resolver that answers {@code null} for everything is a legal-looking implementation that
 * silently turns every stream route into a 404, and one that answers a match for everything turns
 * every respond-once request into a stream. So every miss below is paired with the hit on the same
 * table that proves the miss was a decision, and every hit with the neighbouring request that must
 * miss. A resolver that ignores templates, ignores the method, drops captured parameters or lets a
 * template shadow an exact route fails at least one case.
 *
 * <h2>The route table</h2>
 * <p>The SPI declares no registration API — how routes reach a resolver is the implementation's
 * business — so the binding receives the table as {@link StreamRoute} and {@link RespondOnceRoute}
 * descriptions and builds a resolver that serves exactly those routes, registered in list order.
 * The TCK supplies the handlers, so it can tell which route a match runs. Every test calls
 * {@link #resolverFor} once, on this table:
 * <table class="striped">
 *   <caption>Routes registered by every test, in this order</caption>
 *   <thead><tr><th>Kind</th><th>Method</th><th>Path</th></tr></thead>
 *   <tbody>
 *     <tr><td>stream</td><td>GET</td><td>{@code /events}</td></tr>
 *     <tr><td>stream</td><td>GET</td><td>{@code /feed}</td></tr>
 *     <tr><td>stream</td><td>GET</td><td>{@code /orders/{id}/events}</td></tr>
 *     <tr><td>stream</td><td>GET</td><td>{@code /orders/live/events}</td></tr>
 *     <tr><td>stream</td><td>GET</td><td>{@code /tenants/{tenant}/orders/{id}/events}</td></tr>
 *     <tr><td>respond-once</td><td>POST</td><td>{@code /events}</td></tr>
 *     <tr><td>respond-once</td><td>GET</td><td>{@code /orders}</td></tr>
 *     <tr><td>respond-once</td><td>GET</td><td>{@code /reports/{id}}</td></tr>
 *   </tbody>
 * </table>
 * <p>The template {@code /orders/{id}/events} is listed before the exact {@code /orders/live/events}
 * it also matches, so a resolver that lets the first registration win fails the precedence case
 * instead of passing it by accident of order.
 *
 * <h2>What is not held here</h2>
 * <p>A miss allocating nothing beyond the one copy that drops a query string is part of the
 * contract's Allocation line, and it is not measured here: a portable suite cannot count bytes on
 * every tier's JVM and threading model. The Core router holds it in its own allocation test, which
 * measures exact per-thread bytes through a forwarder of the shape an application binds. Where the
 * resolver runs — before authorization, reading no {@link ScopedValue} — is a driver obligation
 * observed through the driver's dispatch tests, not through the resolver alone.
 *
 * <h2>How to bind</h2>
 * {@snippet lang="java" :
 * class CoreStreamRouteResolverTckTest extends AbstractStreamRouteResolverTck {
 *     @Override
 *     protected StreamRouteResolver resolverFor(List<StreamRoute> streamRoutes,
 *                                               List<RespondOnceRoute> respondOnceRoutes) {
 *         HttpRouter.Builder builder = HttpRouter.builder();
 *         streamRoutes.forEach(r -> builder.streamRoute(r.method(), r.path(), r.handler()));
 *         respondOnceRoutes.forEach(r -> builder.route(r.method(), r.path(), r.handler()));
 *         return builder.build();
 *     }
 * }
 * }
 *
 * @since 0.12
 */
@DisplayName("TCK: StreamRouteResolver (ADR-043 obligation 7, amendment A1)")
public abstract class AbstractStreamRouteResolverTck {

    /**
     * One stream route the binding registers.
     *
     * @param method  the request method it serves
     * @param path    an exact path, or a template with {@code {name}} segments
     * @param handler the handler a match on it must run
     */
    public record StreamRoute(HttpMethod method, String path, HttpStreamHandler handler) {

        /**
         * Rejects an incomplete route.
         *
         * @throws NullPointerException if any component is {@code null}
         */
        public StreamRoute {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(handler, "handler");
        }
    }

    /**
     * One respond-once route the binding registers next to the stream routes; none of them may ever
     * resolve as a stream.
     *
     * @param method  the request method it serves
     * @param path    an exact path, or a template with {@code {name}} segments
     * @param handler its respond-once handler
     */
    public record RespondOnceRoute(HttpMethod method, String path, HttpHandler handler) {

        /**
         * Rejects an incomplete route.
         *
         * @throws NullPointerException if any component is {@code null}
         */
        public RespondOnceRoute {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(handler, "handler");
        }
    }

    private static final String ID = "id";
    private static final String TENANT = "tenant";

    private final RecordingHandler events = new RecordingHandler("GET /events");
    private final RecordingHandler feed = new RecordingHandler("GET /feed");
    private final RecordingHandler orderEvents = new RecordingHandler("GET /orders/{id}/events");
    private final RecordingHandler liveEvents = new RecordingHandler("GET /orders/live/events");
    private final RecordingHandler tenantOrderEvents =
            new RecordingHandler("GET /tenants/{tenant}/orders/{id}/events");

    private final AtomicReference<RecordingHandler> ran = new AtomicReference<>();

    private StreamRouteResolver resolver;

    /**
     * Creates the contract; subclasses supply the resolver under test via {@link #resolverFor}.
     */
    public AbstractStreamRouteResolverTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Builds the resolver under test, serving exactly the given routes.
     *
     * @param streamRoutes      the stream routes, to be registered in list order
     * @param respondOnceRoutes the respond-once routes, to be registered next to them
     * @return a resolver serving those routes; never {@code null}
     */
    protected abstract StreamRouteResolver resolverFor(List<StreamRoute> streamRoutes,
                                                       List<RespondOnceRoute> respondOnceRoutes);

    @BeforeEach
    void buildTheTable() {
        HttpHandler respondOnce = exchange -> exchange.respond(HttpStatus.OK);
        resolver = resolverFor(
                List.of(new StreamRoute(HttpMethod.GET, "/events", events),
                        new StreamRoute(HttpMethod.GET, "/feed", feed),
                        new StreamRoute(HttpMethod.GET, "/orders/{id}/events", orderEvents),
                        new StreamRoute(HttpMethod.GET, "/orders/live/events", liveEvents),
                        new StreamRoute(HttpMethod.GET, "/tenants/{tenant}/orders/{id}/events",
                                tenantOrderEvents)),
                List.of(new RespondOnceRoute(HttpMethod.POST, "/events", respondOnce),
                        new RespondOnceRoute(HttpMethod.GET, "/orders", respondOnce),
                        new RespondOnceRoute(HttpMethod.GET, "/reports/{id}", respondOnce)));
        assertThat(resolver).as("resolverFor must return a resolver").isNotNull();
    }

    @Nested
    @DisplayName("Misses — each paired with the hit that proves it was decided")
    class Misses {

        @Test
        @DisplayName("an unknown path answers null")
        void unknownPathIsNotAStream() {
            assertThat(resolver.resolveStream(HttpMethod.GET, "/nowhere")).isNull();
            assertThat(resolver.resolveStream(HttpMethod.GET, "/eventsx"))
                    .as("a path that merely starts with a stream route's path")
                    .isNull();
            assertThat(resolver.resolveStream(HttpMethod.GET, "/events"))
                    .as("the control: the same table does resolve its stream route, so the misses "
                            + "above are not a resolver that answers null for everything")
                    .isNotNull();
        }

        @Test
        @DisplayName("the right path under the wrong method answers null")
        void wrongMethodIsNotAStream() {
            assertThat(resolver.resolveStream(HttpMethod.DELETE, "/events"))
                    .as("a method with no route on that path at all")
                    .isNull();
            assertThat(resolver.resolveStream(HttpMethod.PUT, "/orders/42/events"))
                    .as("a template path under a method it is not registered for")
                    .isNull();
            assertThat(resolver.resolveStream(HttpMethod.GET, "/orders/42/events"))
                    .as("the control: the same template path under its own method")
                    .isNotNull();
        }

        @Test
        @DisplayName("a respond-once route never resolves as a stream")
        void respondOnceRouteIsNotAStream() {
            assertThat(resolver.resolveStream(HttpMethod.POST, "/events"))
                    .as("POST /events is respond-once; its path is a stream route under GET, so a "
                            + "resolver that ignores the method would open a stream here")
                    .isNull();
            assertThat(resolver.resolveStream(HttpMethod.GET, "/orders"))
                    .as("an exact respond-once route")
                    .isNull();
            assertThat(resolver.resolveStream(HttpMethod.GET, "/reports/7"))
                    .as("a templated respond-once route")
                    .isNull();
        }

        @Test
        @DisplayName("a path with the wrong number of segments does not match a template")
        void segmentCountMismatchIsNotAStream() {
            assertThat(resolver.resolveStream(HttpMethod.GET, "/orders/42")).isNull();
            assertThat(resolver.resolveStream(HttpMethod.GET, "/orders/42/events/extra")).isNull();
            assertThat(resolver.resolveStream(HttpMethod.GET, "/orders//events"))
                    .as("an empty segment captures nothing, so it does not match a placeholder")
                    .isNull();
            assertThat(resolver.resolveStream(HttpMethod.GET, "/orders/42/events"))
                    .as("the control: the well-formed path does match")
                    .isNotNull();
        }

        @Test
        @DisplayName("degenerate paths answer null and do not throw")
        void degeneratePathsAnswerNull() {
            for (String path : List.of("", "/", "?", "//", "/?events")) {
                assertThatCode(() -> resolver.resolveStream(HttpMethod.GET, path))
                        .as("the contract says a resolver does not throw; path \"%s\"", path)
                        .doesNotThrowAnyException();
                assertThat(resolver.resolveStream(HttpMethod.GET, path))
                        .as("path \"%s\"", path)
                        .isNull();
            }
        }
    }

    @Nested
    @DisplayName("Hits — each resolving to the route registered for it")
    class Hits {

        @Test
        @DisplayName("an exact stream route resolves, runs its own handler, and captures nothing")
        void exactRouteResolves() {
            StreamMatch match = resolver.resolveStream(HttpMethod.GET, "/events");

            assertThat(match).isNotNull();
            assertThat(match.params()).as("an exact route captures nothing").isEmpty();
            assertThat(runs(match))
                    .as("the returned handler runs the handler registered for GET /events")
                    .isSameAs(events);
            assertThat(runs(resolver.resolveStream(HttpMethod.GET, "/feed")))
                    .as("and a second exact route runs its own, so the first was not a resolver that "
                            + "hands out one handler for every hit")
                    .isSameAs(feed);
        }

        @Test
        @DisplayName("a template route resolves with its captured parameter in params()")
        void templateRouteCapturesItsParameter() {
            StreamMatch match = resolver.resolveStream(HttpMethod.GET, "/orders/42/events");

            assertThat(match).isNotNull();
            assertThat(match.params())
                    .as("the driver hands the route exactly these as HttpStreamExchange.pathParams(); a "
                            + "resolver that drops them resolves the route and starves its handler")
                    .isEqualTo(Map.of(ID, "42"));
            assertThat(runs(match)).isSameAs(orderEvents);
            assertThat(resolver.resolveStream(HttpMethod.GET, "/orders/43/events").params())
                    .as("a different request segment captures a different value, so the capture is "
                            + "read from the request and not remembered")
                    .isEqualTo(Map.of(ID, "43"));
        }

        @Test
        @DisplayName("a template with two placeholders captures both, each under its own name")
        void templateRouteCapturesEveryParameter() {
            StreamMatch match =
                    resolver.resolveStream(HttpMethod.GET, "/tenants/acme/orders/7/events");

            assertThat(match).isNotNull();
            assertThat(match.params()).isEqualTo(Map.of(TENANT, "acme", ID, "7"));
            assertThat(runs(match)).isSameAs(tenantOrderEvents);
        }

        @Test
        @DisplayName("an exact route beats a template that also matches, whatever the order")
        void exactRouteBeatsTemplate() {
            StreamMatch literal = resolver.resolveStream(HttpMethod.GET, "/orders/live/events");

            assertThat(literal).isNotNull();
            assertThat(runs(literal))
                    .as("/orders/live/events matches both routes; the exact one is registered after "
                            + "the template and must still win")
                    .isSameAs(liveEvents);
            assertThat(literal.params())
                    .as("the exact route captures nothing, so an id=live here means the template won")
                    .isEmpty();
            assertThat(runs(resolver.resolveStream(HttpMethod.GET, "/orders/dead/events")))
                    .as("the control: a neighbour the exact route does not cover still reaches the "
                            + "template, so the exact route is not shadowing it wholesale")
                    .isSameAs(orderEvents);
        }
    }

    @Nested
    @DisplayName("A query string takes no part in matching")
    class QueryString {

        @Test
        @DisplayName("a query string does not stop an exact route from resolving")
        void queryOnExactRoute() {
            StreamMatch match = resolver.resolveStream(HttpMethod.GET, "/events?since=5");

            assertThat(match).as("the path is received as sent, query included").isNotNull();
            assertThat(runs(match)).isSameAs(events);
        }

        @Test
        @DisplayName("a query string is not captured into a template's last parameter")
        void queryOnTemplateRoute() {
            StreamMatch match = resolver.resolveStream(HttpMethod.GET, "/orders/42/events?since=5");

            assertThat(match).isNotNull();
            assertThat(match.params())
                    .as("the last segment is 'events'; had the query stayed on it, the literal would "
                            + "not match at all")
                    .isEqualTo(Map.of(ID, "42"));
            assertThat(resolver.resolveStream(HttpMethod.GET,
                    "/tenants/acme/orders/7/events?x=1").params())
                    .isEqualTo(Map.of(TENANT, "acme", ID, "7"));
        }

        @Test
        @DisplayName("a query string does not turn a miss into a hit")
        void queryDoesNotCreateAMatch() {
            assertThat(resolver.resolveStream(HttpMethod.GET, "/nowhere?path=/events"))
                    .as("a stream route's path inside the query is not the request's path")
                    .isNull();
            assertThat(resolver.resolveStream(HttpMethod.GET, "/orders?stream=1"))
                    .as("a respond-once route stays one with a query")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("Concurrency")
    class Concurrency {

        private static final int THREADS = 8;
        private static final int PER_THREAD = 2_000;

        @Test
        @DisplayName("concurrent resolutions each get the parameters of their own request")
        void concurrentCallsDoNotShareState() throws InterruptedException {
            // A resolver that captured into a shared, reused map would pass every single-threaded case
            // above; with several callers the values of one request leak into another's match.
            CountDownLatch start = new CountDownLatch(1);
            ConcurrentLinkedQueue<String> wrong = new ConcurrentLinkedQueue<>();
            List<Thread> threads = new ArrayList<>(THREADS);
            for (int t = 0; t < THREADS; t++) {
                String tenant = "t" + t;
                threads.add(Thread.ofVirtual().start(() -> {
                    awaitQuietly(start);
                    for (int i = 0; i < PER_THREAD; i++) {
                        String id = Integer.toString(i);
                        StreamMatch match = resolver.resolveStream(HttpMethod.GET,
                                "/tenants/" + tenant + "/orders/" + id + "/events");
                        Map<String, String> expected = Map.of(TENANT, tenant, ID, id);
                        if (match == null || !expected.equals(match.params())) {
                            wrong.add(tenant + "/" + id + " -> "
                                    + (match == null ? "null" : match.params()));
                        }
                    }
                }));
            }
            start.countDown();
            for (Thread thread : threads) {
                thread.join();
            }

            assertThat(wrong)
                    .as("%d threads x %d resolutions on one resolver", THREADS, PER_THREAD)
                    .isEmpty();
        }

        private static void awaitQuietly(CountDownLatch latch) {
            try {
                latch.await();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Runs a match's handler on a stub exchange and returns the registered handler that ran.
     *
     * <p>Identity of the returned handler is not the contract: a wrapper may return a handler of its
     * own around the route's, and may hand the route a decorated exchange. What is the contract is
     * that running it runs the registered one, so each registered handler reports itself here rather
     * than through the exchange it is given.
     */
    private RecordingHandler runs(StreamMatch match) {
        assertThat(match).as("expected a stream match").isNotNull();
        ran.set(null);
        match.handler().handle(new StubExchange());
        assertThat(ran.get())
                .as("running the returned handler must run a registered stream handler")
                .isNotNull();
        return ran.get();
    }

    /** A registered stream handler that reports itself when it runs. */
    private final class RecordingHandler implements HttpStreamHandler {

        private final String route;

        private RecordingHandler(String route) {
            this.route = route;
        }

        @Override
        public void handle(HttpStreamExchange exchange) {
            RecordingHandler previous = ran.getAndSet(this);
            assertThat(previous)
                    .as("a second registered handler ran for one match: %s after %s", this, previous)
                    .isNull();
        }

        @Override
        public String toString() {
            return route;
        }
    }

    /** An exchange that is never written to: the registered handlers here only report that they ran. */
    private static final class StubExchange implements HttpStreamExchange {

        @Override
        public HttpRequest request() {
            throw new UnsupportedOperationException("the TCK's stub exchange carries no request");
        }

        @Override
        public void emit(StreamEvent event) {
            throw new UnsupportedOperationException("the TCK's stub exchange is never written to");
        }

        @Override
        public void close() {
            // Nothing to release: the stub holds no stream.
        }
    }
}
