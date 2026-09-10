/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.health;

import eu.exeris.kernel.spi.bootstrap.HealthProbe;
import eu.exeris.kernel.spi.bootstrap.HealthProbe.ProbeSnapshot;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;

import java.util.List;
import java.util.Objects;

/**
 * Community {@link HttpHandler} that surfaces a {@link HealthProbe} as
 * Kubernetes-friendly readiness and liveness endpoints.
 *
 * <h2>Routing</h2>
 * <ul>
 *   <li>{@code GET <readinessPath>} → {@code 200 OK} when readiness is healthy,
 *       {@code 503 Service Unavailable} otherwise.</li>
 *   <li>{@code GET <livenessPath>} → {@code 200 OK} when liveness is healthy,
 *       {@code 503 Service Unavailable} otherwise.</li>
 *   <li>Any other path → {@code 404 Not Found}.</li>
 *   <li>Non-{@code GET} method on a probe path → {@code 405 Method Not Allowed}.</li>
 * </ul>
 *
 * <p>Default paths are {@value #DEFAULT_READINESS_PATH} and {@value #DEFAULT_LIVENESS_PATH}.
 * Both responses are bodyless — Kubernetes readiness/liveness probes evaluate the
 * status code only. The textual status reported by the monitor (e.g. {@code "READY"},
 * {@code "STARTING"}, {@code "FAILED"}, {@code "DOWN"}) is mirrored into the
 * {@value #STATUS_HEADER} response header for human diagnostics.
 *
 * <h2>No magic DI</h2>
 * <p>Constructor-injected — no field injection, no framework. Callers supply any
 * {@link HealthProbe} implementation; tests exercise this handler against both a
 * stub probe and {@code KernelHealthMonitor} (Core), asserting the response shape
 * for each probe outcome.
 *
 * @since 0.7
 */
public final class HealthEndpointHandler implements HttpHandler {

    /** Default URI for Kubernetes-style readiness probes. */
    public static final String DEFAULT_READINESS_PATH = "/healthz/readiness";

    /** Default URI for Kubernetes-style liveness probes. */
    public static final String DEFAULT_LIVENESS_PATH = "/healthz/liveness";

    /** Response header that mirrors the textual status string from the probe snapshot. */
    public static final String STATUS_HEADER = "X-Exeris-Health";

    private final HealthProbe probe;
    private final String readinessPath;
    private final String livenessPath;

    /**
     * Creates a handler with the default probe paths.
     *
     * @param probe non-null kernel health probe
     * @throws NullPointerException if {@code probe} is {@code null}
     */
    public HealthEndpointHandler(HealthProbe probe) {
        this(probe, DEFAULT_READINESS_PATH, DEFAULT_LIVENESS_PATH);
    }

    /**
     * Creates a handler with custom probe paths.
     *
     * @param probe         non-null kernel health probe
     * @param readinessPath non-null, non-blank readiness probe path (must start with {@code '/'})
     * @param livenessPath  non-null, non-blank liveness probe path (must start with {@code '/'})
     * @throws NullPointerException     if {@code probe}, {@code readinessPath} or {@code livenessPath} is {@code null}
     * @throws IllegalArgumentException if either path is blank, does not start with {@code '/'},
     *                                  or the two paths are equal
     */
    public HealthEndpointHandler(HealthProbe probe, String readinessPath, String livenessPath) {
        this.probe = Objects.requireNonNull(probe, "probe");
        this.readinessPath = validatePath(readinessPath, "readinessPath");
        this.livenessPath = validatePath(livenessPath, "livenessPath");
        if (this.readinessPath.equals(this.livenessPath)) {
            throw new IllegalArgumentException("readinessPath and livenessPath must differ");
        }
    }

    /**
     * Routes {@code exchange} per the class-level routing table and, on a match,
     * responds with the readiness or liveness status from the wrapped probe.
     *
     * @param exchange the exchange to handle; never {@code null}
     */
    @Override
    public void handle(HttpExchange exchange) {
        HttpRequest request = exchange.request();
        String path = request.path();
        boolean isReadiness = readinessPath.equals(path);
        boolean isLiveness = livenessPath.equals(path);

        if (!isReadiness && !isLiveness) {
            exchange.respond(HttpResponse.noBody(HttpStatus.NOT_FOUND, request.version()));
            return;
        }

        if (request.method() != HttpMethod.GET) {
            exchange.respond(HttpResponse.noBody(HttpStatus.METHOD_NOT_ALLOWED, request.version(),
                    List.of(new HttpHeader("Allow", HttpMethod.GET.name()))));
            return;
        }

        ProbeSnapshot snapshot = isReadiness ? probe.readiness() : probe.liveness();
        HttpStatus status = snapshot.healthy() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        exchange.respond(HttpResponse.noBody(status, request.version(),
                List.of(new HttpHeader(STATUS_HEADER, snapshot.status()))));
    }

    private static String validatePath(String path, String parameterName) {
        Objects.requireNonNull(path, parameterName);
        if (path.isBlank()) {
            throw new IllegalArgumentException(parameterName + " must not be blank");
        }
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException(parameterName + " must start with '/'");
        }
        return path;
    }
}
