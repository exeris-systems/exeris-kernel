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
import eu.exeris.kernel.spi.http.HttpVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;

/**
 * RESEARCH — what routing one request costs, before the handler runs.
 *
 * <p>Measures the pair the transport tier actually runs per request: {@code resolveStream}, which it
 * consults first to decide between streaming and respond-once dispatch, and {@code handle}, which
 * resolves the respond-once route. Both strip the query and both split the path on {@code '/'}.
 *
 * <p>Prints a table; asserts nothing.
 */
@DisplayName("RESEARCH: HTTP routing allocation")
class HttpRoutingAllocationResearch {

    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final int WARMUP = 20_000;
    private static final int MEASURED = 50_000;

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
        HttpRouter.StreamMatch stream = router.resolveStream(method, path);
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
