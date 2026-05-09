/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.health;

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
