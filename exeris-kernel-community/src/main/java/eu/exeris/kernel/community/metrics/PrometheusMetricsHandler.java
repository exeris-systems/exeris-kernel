/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.metrics;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Community {@link HttpHandler} that serves the Prometheus exposition snapshot
 * captured by a {@link PrometheusMetricsSink} as the body of a {@code GET /metrics}
 * response.
 *
 * <h2>Routing</h2>
 * <ul>
 *   <li>{@code GET <metricsPath>} → {@code 200 OK} with Prometheus exposition body.</li>
 *   <li>Any other path → {@code 404 Not Found}.</li>
 *   <li>Non-{@code GET} method on the metrics path → {@code 405 Method Not Allowed}
 *       with {@code Allow: GET}.</li>
 * </ul>
 *
 * <p>Default path is {@value #DEFAULT_METRICS_PATH}. Pair this handler with the
 * Community {@code HealthEndpointHandler} on the same HTTP engine to expose both
 * Kubernetes probes and Prometheus scrape data side by side.
 *
 * <h2>Allocation</h2>
 * <p>The exposition body is materialised as a {@link String}, copied into a
 * {@link LoanedBuffer} sized to the encoded byte length, then transferred to the
 * engine via {@link HttpExchange#respond(HttpResponse)}. The engine takes
 * ownership of the buffer. Allocations occur per request — operators concerned
 * about scrape overhead should size the {@link MemoryAllocator}'s network slab
 * pool above their typical exposition size.
 *
 * @since 0.7.0
 */
@SuppressWarnings({
    "PMD.CloseResource",                  // body ownership transfers to the engine on respond()
    "PMD.AvoidCatchingGenericException"   // any failure encoding the body must release the buffer
})
public final class PrometheusMetricsHandler implements HttpHandler {

    /** Default URI for Prometheus scrapes. */
    public static final String DEFAULT_METRICS_PATH = "/metrics";

    private final PrometheusMetricsSink sink;
    private final MemoryAllocator allocator;
    private final String metricsPath;

    public PrometheusMetricsHandler(PrometheusMetricsSink sink, MemoryAllocator allocator) {
        this(sink, allocator, DEFAULT_METRICS_PATH);
    }

    public PrometheusMetricsHandler(PrometheusMetricsSink sink, MemoryAllocator allocator, String metricsPath) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.allocator = Objects.requireNonNull(allocator, "allocator");
        Objects.requireNonNull(metricsPath, "metricsPath");
        if (metricsPath.isBlank()) {
            throw new IllegalArgumentException("metricsPath must not be blank");
        }
        if (!metricsPath.startsWith("/")) {
            throw new IllegalArgumentException("metricsPath must start with '/'");
        }
        this.metricsPath = metricsPath;
    }

    @Override
    public void handle(HttpExchange exchange) {
        HttpRequest request = exchange.request();
        if (!metricsPath.equals(request.path())) {
            exchange.respond(HttpResponse.noBody(HttpStatus.NOT_FOUND, request.version()));
            return;
        }
        if (request.method() != HttpMethod.GET) {
            exchange.respond(HttpResponse.noBody(HttpStatus.METHOD_NOT_ALLOWED, request.version(),
                    List.of(new HttpHeader("Allow", HttpMethod.GET.name()))));
            return;
        }

        byte[] payload = sink.exposeText().getBytes(StandardCharsets.UTF_8);
        LoanedBuffer body = allocator.allocateNetwork(payload.length);
        try {
            MemorySegment segment = body.segment();
            MemorySegment.copy(payload, 0, segment, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, payload.length);
            body.setSize(payload.length);
            // Ownership of `body` transfers to the engine only on a successful entry into
            // the write path (HttpExchange.respond contract: "engine takes ownership ...
            // after the write completes"). If respond() throws — e.g.,
            // IllegalStateException for a second call — the engine never took ownership,
            // so the catch below must close the buffer to avoid a leak.
            exchange.respond(new HttpResponse(
                    HttpStatus.OK,
                    request.version(),
                    List.of(
                            new HttpHeader("Content-Type", PrometheusMetricsSink.CONTENT_TYPE),
                            new HttpHeader("Content-Length", Integer.toString(payload.length))),
                    body));
        } catch (RuntimeException releaseAndRethrow) {
            body.close();
            throw releaseAndRethrow;
        }
    }
}
