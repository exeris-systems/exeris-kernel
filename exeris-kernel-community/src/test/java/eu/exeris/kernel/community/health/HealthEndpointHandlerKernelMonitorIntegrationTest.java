/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.health;

import eu.exeris.kernel.community.bootstrap.CommunitySubsystemHealthWatcher;
import eu.exeris.kernel.core.bootstrap.health.KernelHealthMonitor;
import eu.exeris.kernel.spi.bootstrap.HealthProbe;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test pinning the end-to-end flow:
 * {@link KernelHealthMonitor} (Core, implements {@link HealthProbe})
 * → {@link HealthEndpointHandler} (Community)
 * → operator-visible response.
 *
 * <p>The {@link AbstractHealthEndpointTck} subclass already covers the handler
 * contract with a stub probe; this test verifies that the real Core monitor
 * surfaces the right state through the handler.
 */
@DisplayName("HealthEndpointHandler + KernelHealthMonitor integration")
class HealthEndpointHandlerKernelMonitorIntegrationTest {

    private static final String READINESS = HealthEndpointHandler.DEFAULT_READINESS_PATH;
    private static final String LIVENESS = HealthEndpointHandler.DEFAULT_LIVENESS_PATH;

    @Test
    @DisplayName("Full bootstrap: pre-START 503; after START + RUNNING 200")
    void surfacesReadinessThroughBootstrapTransitions() {
        KernelHealthMonitor monitor = new KernelHealthMonitor();
        monitor.registerSubsystem("memory", true);
        HealthEndpointHandler handler = new HealthEndpointHandler(monitor);

        RecordingExchange before = invoke(handler, READINESS);
        assertThat(before.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(before.header(HealthEndpointHandler.STATUS_HEADER)).hasValue("STARTING");

        monitor.markKernelState(KernelHealthMonitor.KernelState.INITIALIZED);
        monitor.markKernelState(KernelHealthMonitor.KernelState.STARTED);
        monitor.markSubsystemState("memory", KernelHealthMonitor.SubsystemState.RUNNING);

        RecordingExchange after = invoke(handler, READINESS);
        assertThat(after.status()).isEqualTo(HttpStatus.OK);
        assertThat(after.header(HealthEndpointHandler.STATUS_HEADER)).hasValue("READY");
    }

    @Test
    @DisplayName("Liveness reports DOWN after kernel marked FAILED")
    void livenessSurfacesFailedKernel() {
        KernelHealthMonitor monitor = new KernelHealthMonitor();
        monitor.markKernelState(KernelHealthMonitor.KernelState.INITIALIZED);
        HealthEndpointHandler handler = new HealthEndpointHandler(monitor);

        assertThat(invoke(handler, LIVENESS).status()).isEqualTo(HttpStatus.OK);

        monitor.markKernelState(KernelHealthMonitor.KernelState.FAILED);

        RecordingExchange afterFailure = invoke(handler, LIVENESS);
        assertThat(afterFailure.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(afterFailure.header(HealthEndpointHandler.STATUS_HEADER)).hasValue("DOWN");
    }

    @Test
    @DisplayName("Health watcher drives readiness RUNNING→DEGRADED→RUNNING through the HTTP probe")
    void watcherDrivesReadinessDegradedAndBack() {
        KernelHealthMonitor monitor = new KernelHealthMonitor();
        monitor.registerSubsystem("persistence", true);
        monitor.markSubsystemState("persistence", KernelHealthMonitor.SubsystemState.RUNNING);
        monitor.markKernelState(KernelHealthMonitor.KernelState.INITIALIZED);
        monitor.markKernelState(KernelHealthMonitor.KernelState.STARTED);
        HealthEndpointHandler handler = new HealthEndpointHandler(monitor);

        // Stands in for CommunityPersistenceEngine#canServiceRequest(): the live health signal the
        // watcher reconciles and the same signal that deterministically denies requests (ADR-012).
        AtomicBoolean serving = new AtomicBoolean(true);
        CommunitySubsystemHealthWatcher watcher =
                new CommunitySubsystemHealthWatcher(monitor, TimeUnit.MILLISECONDS.toNanos(5));
        watcher.register("persistence", serving::get);

        assertThat(invoke(handler, READINESS).status()).isEqualTo(HttpStatus.OK);

        watcher.start();
        try {
            serving.set(false);
            awaitReadinessStatus(handler, "DEGRADED");
            assertThat(invoke(handler, READINESS).status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(invoke(handler, LIVENESS).status()).isEqualTo(HttpStatus.OK);

            serving.set(true);
            awaitReadinessStatus(handler, "READY");
            assertThat(invoke(handler, READINESS).status()).isEqualTo(HttpStatus.OK);
        } finally {
            watcher.stop();
        }
    }

    private static void awaitReadinessStatus(HttpHandler handler, String expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (invoke(handler, READINESS).header(HealthEndpointHandler.STATUS_HEADER)
                    .filter(expected::equals).isPresent()) {
                return;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }
        assertThat(invoke(handler, READINESS).header(HealthEndpointHandler.STATUS_HEADER))
                .as("readiness status reaches %s within deadline", expected)
                .hasValue(expected);
    }

    private static RecordingExchange invoke(HttpHandler handler, String path) {
        HttpRequest request = HttpRequest.noBody(HttpMethod.GET, path, HttpVersion.HTTP_1_1, List.of());
        RecordingExchange exchange = new RecordingExchange(request);
        handler.handle(exchange);
        return exchange;
    }

    private static final class RecordingExchange implements HttpExchange {
        private final HttpRequest request;
        private HttpResponse response;

        RecordingExchange(HttpRequest request) {
            this.request = request;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public void respond(HttpResponse response) {
            this.response = response;
        }

        @Override
        public void respond(HttpTypedResponse response) {
            throw new UnsupportedOperationException();
        }

        HttpStatus status() {
            assertThat(response).isNotNull();
            return response.status();
        }

        Optional<String> header(String name) {
            assertThat(response).isNotNull();
            for (HttpHeader header : response.headers()) {
                if (header.nameEqualsIgnoreCase(name)) {
                    return Optional.of(header.value());
                }
            }
            return Optional.empty();
        }
    }
}
