/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.health;

import eu.exeris.kernel.spi.bootstrap.HealthProbe;
import eu.exeris.kernel.spi.bootstrap.HealthProbe.ProbeSnapshot;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpTypedResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK: Abstract base for health-endpoint handler bindings.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@code GET <readinessPath>} returns {@code 200 OK} when readiness is healthy
 *       and {@code 503 Service Unavailable} otherwise.</li>
 *   <li>{@code GET <livenessPath>} returns {@code 200 OK} when liveness is healthy
 *       and {@code 503 Service Unavailable} otherwise.</li>
 *   <li>Non-matching path → {@code 404 Not Found}.</li>
 *   <li>Non-{@code GET} method on a probe path → {@code 405 Method Not Allowed}
 *       with {@code Allow: GET} header.</li>
 *   <li>Status header is mirrored from the underlying probe snapshot's textual status.</li>
 * </ul>
 *
 * <h2>How to use</h2>
 * {@snippet lang="java" :
 * class CommunityHealthEndpointTckTest extends AbstractHealthEndpointTck {
 *     @Override
 *     protected HttpHandler newHandler(HealthProbe probe) {
 *         return new HealthEndpointHandler(probe);
 *     }
 * }
 * }
 *
 * <p>The abstract drives state transitions through a stub {@link HealthProbe}; bindings
 * supply only the handler factory.
 *
 * @since 0.7
 */
public abstract class AbstractHealthEndpointTck {

    /** Default readiness path used by the abstract. Bindings using a custom path must override. */
    protected static final String READINESS_PATH = "/healthz/readiness";

    /** Default liveness path used by the abstract. Bindings using a custom path must override. */
    protected static final String LIVENESS_PATH = "/healthz/liveness";

    /** Header name carrying the textual status (mirrored from the probe snapshot). */
    protected static final String STATUS_HEADER = "X-Exeris-Health";

    /**
     * Creates the contract; subclasses supply the handler binding via {@link #newHandler(HealthProbe)}.
     */
    public AbstractHealthEndpointTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Produces a fresh handler bound to the given probe. Bindings construct their
     * concrete handler and return it.
     *
     * @param probe non-null probe to drive
     * @return non-null handler under test
     */
    protected abstract HttpHandler newHandler(HealthProbe probe);

    @Test
    @DisplayName("Readiness 200 when probe is healthy; status header mirrors snapshot")
    void readinessReturnsOkWhenProbeIsHealthy() {
        StubHealthProbe probe = StubHealthProbe.healthy();

        RecordingHttpExchange exchange = invoke(newHandler(probe), get(READINESS_PATH));

        assertThat(exchange.status()).isEqualTo(HttpStatus.OK);
        assertThat(exchange.headerValue(STATUS_HEADER)).hasValue("READY");
    }

    @Test
    @DisplayName("Readiness 503 when probe reports not healthy")
    void readinessReturnsServiceUnavailableWhenProbeIsUnhealthy() {
        StubHealthProbe probe = new StubHealthProbe(
                new ProbeSnapshot("STARTING", false),
                new ProbeSnapshot("UP", true));

        RecordingHttpExchange exchange = invoke(newHandler(probe), get(READINESS_PATH));

        assertThat(exchange.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.headerValue(STATUS_HEADER)).hasValue("STARTING");
    }

    @Test
    @DisplayName("Liveness 200 when probe is healthy")
    void livenessReturnsOkWhenProbeIsHealthy() {
        StubHealthProbe probe = StubHealthProbe.healthy();

        RecordingHttpExchange exchange = invoke(newHandler(probe), get(LIVENESS_PATH));

        assertThat(exchange.status()).isEqualTo(HttpStatus.OK);
        assertThat(exchange.headerValue(STATUS_HEADER)).hasValue("UP");
    }

    @Test
    @DisplayName("Liveness 503 when probe reports DOWN")
    void livenessReturnsServiceUnavailableWhenProbeIsUnhealthy() {
        StubHealthProbe probe = new StubHealthProbe(
                new ProbeSnapshot("READY", true),
                new ProbeSnapshot("DOWN", false));

        RecordingHttpExchange exchange = invoke(newHandler(probe), get(LIVENESS_PATH));

        assertThat(exchange.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.headerValue(STATUS_HEADER)).hasValue("DOWN");
    }

    @Test
    @DisplayName("Degraded path: liveness 200 / readiness 503 surfaced independently")
    void degradedSurfacesLivenessUpAndReadinessDown() {
        StubHealthProbe probe = new StubHealthProbe(
                new ProbeSnapshot("STARTING", false),
                new ProbeSnapshot("UP", true));

        HttpHandler handler = newHandler(probe);

        RecordingHttpExchange readiness = invoke(handler, get(READINESS_PATH));
        RecordingHttpExchange liveness = invoke(handler, get(LIVENESS_PATH));

        assertThat(readiness.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(liveness.status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Degraded required subsystem: readiness 503 with DEGRADED status, liveness 200 UP")
    void degradedRequiredSubsystemReadiness503LivenessOk() {
        StubHealthProbe probe = new StubHealthProbe(
                new ProbeSnapshot("DEGRADED", false),
                new ProbeSnapshot("UP", true));

        HttpHandler handler = newHandler(probe);

        RecordingHttpExchange readiness = invoke(handler, get(READINESS_PATH));
        RecordingHttpExchange liveness = invoke(handler, get(LIVENESS_PATH));

        assertThat(readiness.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(readiness.headerValue(STATUS_HEADER)).hasValue("DEGRADED");
        assertThat(liveness.status()).isEqualTo(HttpStatus.OK);
        assertThat(liveness.headerValue(STATUS_HEADER)).hasValue("UP");
    }

    @Test
    @DisplayName("Both probes 503 when probe is fully unhealthy (FAILED kernel)")
    void bothProbesReturnUnavailableOnFailedKernel() {
        StubHealthProbe probe = new StubHealthProbe(
                new ProbeSnapshot("FAILED", false),
                new ProbeSnapshot("DOWN", false));

        HttpHandler handler = newHandler(probe);

        RecordingHttpExchange readiness = invoke(handler, get(READINESS_PATH));
        RecordingHttpExchange liveness = invoke(handler, get(LIVENESS_PATH));

        assertThat(readiness.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(readiness.headerValue(STATUS_HEADER)).hasValue("FAILED");
        assertThat(liveness.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(liveness.headerValue(STATUS_HEADER)).hasValue("DOWN");
    }

    @Test
    @DisplayName("Non-matching path returns 404 without invoking the probe")
    void unknownPathReturnsNotFound() {
        StubHealthProbe probe = StubHealthProbe.healthy();

        RecordingHttpExchange exchange = invoke(newHandler(probe), get("/api/users"));

        assertThat(exchange.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(probe.readinessCalls).isZero();
        assertThat(probe.livenessCalls).isZero();
    }

    @Test
    @DisplayName("Non-GET method on probe path returns 405 with Allow: GET header")
    void postOnProbePathReturnsMethodNotAllowed() {
        StubHealthProbe probe = StubHealthProbe.healthy();

        RecordingHttpExchange exchange = invoke(newHandler(probe),
                HttpRequest.noBody(HttpMethod.POST, READINESS_PATH, HttpVersion.HTTP_1_1, List.of()));

        assertThat(exchange.status()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(exchange.headerValue("Allow")).hasValue("GET");
        assertThat(probe.readinessCalls).isZero();
    }

    private static HttpRequest get(String path) {
        return HttpRequest.noBody(HttpMethod.GET, path, HttpVersion.HTTP_1_1, List.of());
    }

    private static RecordingHttpExchange invoke(HttpHandler handler, HttpRequest request) {
        RecordingHttpExchange exchange = new RecordingHttpExchange(request);
        handler.handle(exchange);
        return exchange;
    }

    /**
     * Simple {@link HealthProbe} stub the abstract drives directly. Counts probe calls
     * so contract tests can assert the handler short-circuits non-matching paths.
     */
    protected static final class StubHealthProbe implements HealthProbe {

        private final ProbeSnapshot readinessSnapshot;
        private final ProbeSnapshot livenessSnapshot;
        private int readinessCalls;
        private int livenessCalls;

        StubHealthProbe(ProbeSnapshot readiness, ProbeSnapshot liveness) {
            this.readinessSnapshot = readiness;
            this.livenessSnapshot = liveness;
        }

        static StubHealthProbe healthy() {
            return new StubHealthProbe(
                    new ProbeSnapshot("READY", true),
                    new ProbeSnapshot("UP", true));
        }

        @Override
        public ProbeSnapshot readiness() {
            readinessCalls++;
            return readinessSnapshot;
        }

        @Override
        public ProbeSnapshot liveness() {
            livenessCalls++;
            return livenessSnapshot;
        }
    }

    /**
     * Minimal {@link HttpExchange} that records the response written by the handler.
     * Self-contained so that bindings need not provide a wire-level recorder for this TCK.
     */
    protected static final class RecordingHttpExchange implements HttpExchange {

        private final HttpRequest request;
        private HttpResponse response;

        RecordingHttpExchange(HttpRequest request) {
            this.request = request;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public void respond(HttpResponse response) {
            if (this.response != null) {
                throw new IllegalStateException("respond() already called");
            }
            this.response = response;
        }

        @Override
        public void respond(HttpTypedResponse response) {
            throw new UnsupportedOperationException("Health TCK uses bodyless responses only");
        }

        HttpStatus status() {
            assertThat(response).as("handler must call respond()").isNotNull();
            return response.status();
        }

        Optional<String> headerValue(String name) {
            assertThat(response).as("handler must call respond()").isNotNull();
            for (HttpHeader header : response.headers()) {
                if (header.nameEqualsIgnoreCase(name)) {
                    return Optional.of(header.value());
                }
            }
            return Optional.empty();
        }
    }
}
