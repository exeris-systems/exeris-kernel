/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.routing;

import com.sun.management.ThreadMXBean;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpStreamHandler;
import eu.exeris.kernel.spi.http.StreamMatch;
import eu.exeris.kernel.spi.http.StreamRouteResolver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HttpRouter#resolveStream} names when the router allocates on a miss, within what the
 * Allocation line of {@link StreamRouteResolver} admits, and every request of an application that
 * serves stream routes pays that call. These cases hold the router to what it states, measured
 * through a forwarder of the shape an application binds to {@code HTTP_SERVER_HANDLER}, on a table of
 * the shape a generated application registers.
 *
 * <p>Bytes are exact per-thread counts from {@link ThreadMXBean#getCurrentThreadAllocatedBytes()}.
 * The allocation that contract admits, a query-bearing miss on a method that has stream routes,
 * is measured here too, so a counter that reads zero for everything cannot pass the other cases.
 */
@DisplayName("Stream resolution: allocation on a miss")
class StreamResolutionMissAllocationTest {

    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final int WARMUP = 20_000;
    private static final int MEASURED = 100_000;
    private static final int ENTITIES = 10;

    private static StreamRouteResolver forwarder;
    private static long sink;

    @BeforeAll
    static void buildGeneratedShapeTable() {
        // Asserted rather than assumed: every JDK this build runs on supports the counter, and a
        // skipped allocation case would report green while measuring nothing.
        assertThat(THREADS.isThreadAllocatedMemorySupported())
                .as("per-thread allocation counting is what these cases measure with")
                .isTrue();
        THREADS.setThreadAllocatedMemoryEnabled(true);

        HttpHandler ok = exchange -> exchange.respond(HttpStatus.OK);
        HttpStreamHandler stream = exchange -> { };
        HttpRouter.Builder builder = HttpRouter.builder();
        for (int i = 0; i < ENTITIES; i++) {
            String base = "/api/e" + i;
            builder.route(HttpMethod.GET, base, ok)
                    .route(HttpMethod.POST, base, ok)
                    .route(HttpMethod.GET, base + "/{id}", ok)
                    .route(HttpMethod.PUT, base + "/{id}", ok)
                    .route(HttpMethod.DELETE, base + "/{id}", ok)
                    .streamRoute(HttpMethod.GET, base + "/stream", stream)
                    .streamRoute(HttpMethod.POST, base + "/{id}/actions/ship", stream);
        }
        forwarder = new Forwarder(new AtomicReference<>(builder.build()));
    }

    @Test
    @DisplayName("a miss without a query string allocates nothing, on a method that has stream routes")
    void noQueryMissAllocatesNothing() {
        assertMissAllocatesNothing(HttpMethod.GET, "/api/e1");
        assertMissAllocatesNothing(HttpMethod.POST, "/api/e1");
        assertMissAllocatesNothing(HttpMethod.GET, "/api/e1/42");
    }

    @Test
    @DisplayName("a query-bearing miss allocates nothing when the method has no stream route")
    void queryOnMethodWithoutStreamRoutesAllocatesNothing() {
        assertMissAllocatesNothing(HttpMethod.PUT, "/api/e1/42?x=1");
        assertMissAllocatesNothing(HttpMethod.DELETE, "/api/e1/42?x=1");
    }

    @Test
    @DisplayName("a query-bearing miss on a method that has stream routes copies the path once")
    void queryOnMethodWithStreamRoutesCopiesThePath() {
        assertThat(forwarder.resolveStream(HttpMethod.GET, "/api/e1/42?x=1")).isNull();

        assertThat(bytesPerCall(HttpMethod.GET, "/api/e1/42?x=1"))
                .as("the one allocation the contract admits on a miss; a counter that sees it "
                        + "is a counter that would see any other")
                .isGreaterThan(0L);
    }

    private static void assertMissAllocatesNothing(HttpMethod method, String path) {
        assertThat(forwarder.resolveStream(method, path))
                .as("%s %s is a miss on this table", method, path)
                .isNull();
        assertThat(bytesPerCall(method, path))
                .as("bytes per call for %s %s", method, path)
                .isZero();
    }

    private static long bytesPerCall(HttpMethod method, String path) {
        for (int i = 0; i < WARMUP; i++) {
            consume(forwarder.resolveStream(method, path));
        }
        long before = THREADS.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < MEASURED; i++) {
            consume(forwarder.resolveStream(method, path));
        }
        return (THREADS.getCurrentThreadAllocatedBytes() - before) / MEASURED;
    }

    private static void consume(StreamMatch match) {
        if (match != null) {
            sink++;
        }
    }

    /** The shape an application binds to {@code HTTP_SERVER_HANDLER} over a router built at boot. */
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
}
