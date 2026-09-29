/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.routing;

import com.sun.management.ThreadMXBean;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpStreamHandler;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.http.StreamMatch;
import eu.exeris.kernel.spi.http.StreamRouteResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * RESEARCH — what routing one request costs, before the handler runs.
 *
 * <p>Measures the pair the transport tier actually runs per request: {@code resolveStream}, which it
 * consults first to decide between streaming and respond-once dispatch, and {@code handle}, which
 * resolves the respond-once route. Both strip the query and both split the path on {@code '/'}.
 *
 * <p>{@link #generatedShapeCost()} measures the stream probe alone on the table a generated
 * application builds, reached the way such an application binds it: through a forwarding handler
 * over an {@link AtomicReference}, which implements {@link StreamRouteResolver} by delegating. It
 * reports bytes and nanoseconds per call for the forwarder and for the router bound directly, for
 * 10 and 30 entities. The nanoseconds are the best of two interleaved passes of best-of-5 over a
 * warmed loop that reuses one path {@code String}, so its hash is cached; they compare shapes, and
 * are not a JMH result.
 *
 * <p>Prints tables; asserts nothing. A process is one sample: compare runs from fresh JVMs.
 */
@DisplayName("RESEARCH: HTTP routing allocation")
class HttpRoutingAllocationResearch {

    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final int WARMUP = 20_000;
    private static final int MEASURED = 50_000;
    private static final int TIMED_WARMUP = 500_000;
    private static final int TIMED = 500_000;
    private static final int TIMED_ROUNDS = 5;

    /** Consumed by every timed loop, so the resolution under measurement cannot be discarded. */
    private static long sink;

    @Test
    @DisplayName("bytes per request for real route shapes")
    void routingAllocation() {
        HttpRouter router = realisticTable();
        System.out.println("=== HTTP routing allocation (exact per-thread bytes) ===");
        System.out.printf("%-34s %-10s %-16s%n", "request", "outcome", "bytes/request");

        report(router, "GET /health", HttpMethod.GET, "/health");
        report(router, "GET /api/orders/42", HttpMethod.GET, "/api/orders/42");
        report(router, "GET /api/orders/42?expand=items", HttpMethod.GET, "/api/orders/42?expand=items");
        report(router, "GET /api/orders/42/lines/7", HttpMethod.GET, "/api/orders/42/lines/7");
        report(router, "POST /api/orders", HttpMethod.POST, "/api/orders");
        report(router, "GET /nope/missing", HttpMethod.GET, "/nope/missing");
        report(router, "GET /events/live (stream)", HttpMethod.GET, "/events/live");
    }

    @Test
    @DisplayName("stream probe cost on a generated-shape table, through a forwarder and direct")
    void generatedShapeCost() {
        for (int entities : new int[] {10, 30}) {
            HttpRouter router = generatedTable(entities);
            StreamRouteResolver forwarder = new Forwarder(new AtomicReference<>(router));
            System.out.printf("=== stream probe, generated shape, %d entities ===%n", entities);
            System.out.printf("%-38s %-8s %8s %9s %9s%n",
                    "request", "outcome", "B/fwd", "ns/fwd", "ns/direct");
            probe(forwarder, router, HttpMethod.GET, "/api/e0");
            probe(forwarder, router, HttpMethod.GET, "/api/e0?page=2&size=20");
            probe(forwarder, router, HttpMethod.GET, "/api/e0/42");
            probe(forwarder, router, HttpMethod.POST, "/api/e0");
            probe(forwarder, router, HttpMethod.POST, "/api/e9/42/actions/approve");
            probe(forwarder, router, HttpMethod.PUT, "/api/e0/42");
            probe(forwarder, router, HttpMethod.PUT, "/api/e0/42?x=1");
            probe(forwarder, router, HttpMethod.DELETE, "/api/e0/42?x=1");
            probe(forwarder, router, HttpMethod.GET, "/api/e0/stream");
            probe(forwarder, router, HttpMethod.POST, "/api/e0/42/actions/ship");
        }
        System.out.println("sink=" + (sink & 1));
    }

    /**
     * What a generated application registers per entity: respond-once CRUD and one respond-once
     * action, an exact {@code GET {base}/stream} and a per-action {@code POST} stream template.
     */
    private static HttpRouter generatedTable(int entities) {
        HttpHandler ok = exchange -> exchange.respond(HttpStatus.OK);
        HttpStreamHandler stream = exchange -> { };
        HttpRouter.Builder builder = HttpRouter.builder();
        for (int i = 0; i < entities; i++) {
            String base = "/api/e" + i;
            builder.route(HttpMethod.GET, base, ok)
                    .route(HttpMethod.POST, base, ok)
                    .route(HttpMethod.GET, base + "/{id}", ok)
                    .route(HttpMethod.PUT, base + "/{id}", ok)
                    .route(HttpMethod.DELETE, base + "/{id}", ok)
                    .route(HttpMethod.POST, base + "/{id}/actions/approve", ok)
                    .streamRoute(HttpMethod.GET, base + "/stream", stream)
                    .streamRoute(HttpMethod.POST, base + "/{id}/actions/ship", stream);
        }
        return builder.build();
    }

    private static void probe(StreamRouteResolver forwarder, HttpRouter router, HttpMethod method,
                              String path) {
        String outcome = forwarder.resolveStream(method, path) != null ? "stream" : "miss";
        long bytes = bytesPerCall(() -> forwarder.resolveStream(method, path));
        // Interleaved passes, best kept: a hit allocates, and whichever loop runs first after the
        // byte count absorbs the collector settling, so a single pass reads the order, not the shape.
        double viaForwarder = Double.MAX_VALUE;
        double direct = Double.MAX_VALUE;
        for (int pass = 0; pass < 2; pass++) {
            viaForwarder = Math.min(viaForwarder,
                    nanosPerCall(() -> forwarder.resolveStream(method, path)));
            direct = Math.min(direct, nanosPerCall(() -> router.resolveStream(method, path)));
        }
        System.out.printf("%-38s %-8s %8d %9.1f %9.1f%n",
                method + " " + path, outcome, bytes, viaForwarder, direct);
    }

    private static long bytesPerCall(Supplier<StreamMatch> call) {
        for (int i = 0; i < WARMUP; i++) {
            consume(call.get());
        }
        long[] samples = new long[3];
        for (int window = 0; window < samples.length; window++) {
            long before = THREADS.getCurrentThreadAllocatedBytes();
            for (int i = 0; i < MEASURED; i++) {
                consume(call.get());
            }
            samples[window] = (THREADS.getCurrentThreadAllocatedBytes() - before) / MEASURED;
        }
        Arrays.sort(samples);
        return samples[1];
    }

    private static double nanosPerCall(Supplier<StreamMatch> call) {
        for (int i = 0; i < TIMED_WARMUP; i++) {
            consume(call.get());
        }
        double best = Double.MAX_VALUE;
        for (int round = 0; round < TIMED_ROUNDS; round++) {
            long start = System.nanoTime();
            for (int i = 0; i < TIMED; i++) {
                consume(call.get());
            }
            best = Math.min(best, (System.nanoTime() - start) / (double) TIMED);
        }
        return best;
    }

    private static void consume(StreamMatch match) {
        if (match != null) {
            sink++;
        }
    }

    /** The shape a generated application binds to {@code HTTP_SERVER_HANDLER}. */
    private static final class Forwarder implements HttpHandler, StreamRouteResolver {

        private final AtomicReference<HttpHandler> slot;

        private Forwarder(AtomicReference<HttpHandler> slot) {
            this.slot = slot;
        }

        @Override
        public void handle(HttpExchange exchange) {
            HttpHandler handler = slot.get();
            if (handler == null) {
                exchange.respond(HttpStatus.SERVICE_UNAVAILABLE);
                return;
            }
            handler.handle(exchange);
        }

        @Override
        public StreamMatch resolveStream(HttpMethod method, String path) {
            return slot.get() instanceof StreamRouteResolver resolver
                    ? resolver.resolveStream(method, path)
                    : null;
        }
    }

    /**
     * The shape a generated application produces: a handful of exact routes, several templates, and a
     * streaming template — so the stream table is non-empty and every request pays its lookup.
     */
    private static HttpRouter realisticTable() {
        HttpHandler ok = exchange -> exchange.respond(HttpStatus.OK);
        return HttpRouter.builder()
                .route(HttpMethod.GET, "/health", ok)
                .route(HttpMethod.GET, "/metrics", ok)
                .route(HttpMethod.GET, "/api/orders", ok)
                .route(HttpMethod.POST, "/api/orders", ok)
                .route(HttpMethod.GET, "/api/orders/{id}", ok)
                .route(HttpMethod.PUT, "/api/orders/{id}", ok)
                .route(HttpMethod.DELETE, "/api/orders/{id}", ok)
                .route(HttpMethod.GET, "/api/orders/{id}/lines/{line}", ok)
                .route(HttpMethod.GET, "/api/customers/{id}", ok)
                .streamRoute(HttpMethod.GET, "/events/{topic}", exchange -> { })
                .build();
    }

    private static void report(HttpRouter router, String label, HttpMethod method, String path) {
        HttpExchange exchange = new StubExchange(
                HttpRequest.noBody(method, path, HttpVersion.HTTP_1_1, List.of()));
        String outcome = router.resolveStream(method, path) != null ? "stream" : "respond";
        long perRequest = measure(router, method, path, exchange);
        System.out.printf("%-34s %-10s %-16d%n", label, outcome, perRequest);
    }

    private static long measure(HttpRouter router, HttpMethod method, String path,
                                HttpExchange exchange) {
        for (int i = 0; i < WARMUP; i++) {
            routeOnce(router, method, path, exchange);
        }
        long[] samples = new long[3];
        for (int window = 0; window < samples.length; window++) {
            long before = THREADS.getCurrentThreadAllocatedBytes();
            for (int i = 0; i < MEASURED; i++) {
                routeOnce(router, method, path, exchange);
            }
            samples[window] = (THREADS.getCurrentThreadAllocatedBytes() - before) / MEASURED;
        }
        Arrays.sort(samples);
        return samples[1];
    }

    /** Both halves, in the order the transport runs them: stream probe first, then dispatch. */
    private static void routeOnce(HttpRouter router, HttpMethod method, String path,
                                  HttpExchange exchange) {
        StreamMatch stream = router.resolveStream(method, path);
        if (stream == null) {
            router.handle(exchange);
        }
    }

    private static final class StubExchange implements HttpExchange {

        private final HttpRequest request;

        private StubExchange(HttpRequest request) {
            this.request = request;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public void respond(HttpResponse response) {
            // routing research: the handler's response is not the subject
        }
    }
}
