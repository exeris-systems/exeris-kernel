/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.metrics;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpTypedResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PrometheusMetricsHandler")
class PrometheusMetricsHandlerTest {

    private static MemoryAllocator allocator;

    @BeforeAll
    static void setUpAllocator() {
        allocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    }

    @AfterAll
    static void tearDownAllocator() {
        allocator.close();
    }

    @Test
    @DisplayName("GET /metrics returns 200 with Prometheus body")
    void metricsScrapeReturnsExposition() {
        PrometheusMetricsSink sink = new PrometheusMetricsSink();
        sink.increment("kernel_bootstrap_total", 1L);
        sink.gauge("kernel_heap_bytes", 4096L);
        PrometheusMetricsHandler handler = new PrometheusMetricsHandler(sink, allocator);

        RecordingExchange exchange = invoke(handler, request(HttpMethod.GET, "/metrics"));

        assertThat(exchange.status()).isEqualTo(HttpStatus.OK);
        assertThat(exchange.header("Content-Type")).hasValue(PrometheusMetricsSink.CONTENT_TYPE);
        assertThat(exchange.body())
                .contains("kernel_bootstrap_total 1")
                .contains("kernel_heap_bytes 4096");
        assertThat(exchange.header("Content-Length"))
                .hasValueSatisfying(value ->
                        assertThat(Integer.parseInt(value)).isEqualTo(exchange.body().length()));
    }

    @Test
    @DisplayName("Non-matching path returns 404")
    void unknownPathReturnsNotFound() {
        PrometheusMetricsHandler handler = new PrometheusMetricsHandler(new PrometheusMetricsSink(), allocator);
        RecordingExchange exchange = invoke(handler, request(HttpMethod.GET, "/foo"));
        assertThat(exchange.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("POST /metrics returns 405 with Allow: GET")
    void postReturnsMethodNotAllowed() {
        PrometheusMetricsHandler handler = new PrometheusMetricsHandler(new PrometheusMetricsSink(), allocator);
        RecordingExchange exchange = invoke(handler, request(HttpMethod.POST, "/metrics"));

        assertThat(exchange.status()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(exchange.header("Allow")).hasValue("GET");
    }

    @Test
    @DisplayName("Custom path is honoured; default path then returns 404")
    void customPathIsHonoured() {
        PrometheusMetricsSink sink = new PrometheusMetricsSink();
        sink.increment("k", 1L);
        PrometheusMetricsHandler handler = new PrometheusMetricsHandler(sink, allocator, "/observability/metrics");

        RecordingExchange custom = invoke(handler, request(HttpMethod.GET, "/observability/metrics"));
        RecordingExchange defaultPath = invoke(handler, request(HttpMethod.GET, "/metrics"));

        assertThat(custom.status()).isEqualTo(HttpStatus.OK);
        assertThat(custom.body()).contains("k 1");
        assertThat(defaultPath.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("PR #98 review: body buffer is released when respond() throws (no leak)")
    void respondThrowsClosesBody() {
        PrometheusMetricsSink sink = new PrometheusMetricsSink();
        sink.increment("k", 1L);
        PrometheusMetricsHandler handler = new PrometheusMetricsHandler(sink, allocator);

        ThrowingRespondExchange exchange = new ThrowingRespondExchange(
                request(HttpMethod.GET, "/metrics"));

        assertThatThrownBy(() -> handler.handle(exchange))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated downstream failure");

        assertThat(exchange.capturedBody)
                .as("handler must have offered the body to respond()")
                .isNotNull();
        assertThat(exchange.capturedBody.refCount())
                .as("body refCount must be 0 after respond() throws — caller still owns it "
                        + "so the catch in handle() is responsible for releasing it")
                .isZero();
    }

    @Test
    @DisplayName("Constructor rejects null/blank/relative paths")
    void constructorValidation() {
        PrometheusMetricsSink sink = new PrometheusMetricsSink();
        assertThatThrownBy(() -> new PrometheusMetricsHandler(null, allocator))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PrometheusMetricsHandler(sink, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PrometheusMetricsHandler(sink, allocator, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PrometheusMetricsHandler(sink, allocator, "metrics"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static HttpRequest request(HttpMethod method, String path) {
        return HttpRequest.noBody(method, path, HttpVersion.HTTP_1_1, List.of());
    }

    private static RecordingExchange invoke(PrometheusMetricsHandler handler, HttpRequest request) {
        RecordingExchange exchange = new RecordingExchange(request);
        handler.handle(exchange);
        return exchange;
    }

    private static final class RecordingExchange implements HttpExchange {
        private final HttpRequest request;
        private HttpResponse response;
        private String capturedBody;

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
            if (response.body() != null) {
                int size = (int) response.body().size();
                byte[] bytes = new byte[size];
                MemorySegment.copy(response.body().segment(), ValueLayout.JAVA_BYTE, 0, bytes, 0, size);
                this.capturedBody = new String(bytes, StandardCharsets.UTF_8);
                response.body().close();
            } else {
                this.capturedBody = "";
            }
        }

        @Override
        public void respond(HttpTypedResponse response) {
            throw new UnsupportedOperationException();
        }

        HttpStatus status() {
            assertThat(response).isNotNull();
            return response.status();
        }

        String body() {
            assertThat(response).isNotNull();
            return capturedBody;
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

    /**
     * Exchange whose {@code respond(HttpResponse)} throws after capturing the body, modeling
     * the SPI contract where the engine never took ownership: the caller (handler) must
     * release the buffer in its catch block.
     */
    private static final class ThrowingRespondExchange implements HttpExchange {
        private final HttpRequest request;
        private LoanedBuffer capturedBody;

        ThrowingRespondExchange(HttpRequest request) {
            this.request = request;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public void respond(HttpResponse response) {
            this.capturedBody = response.body();
            throw new IllegalStateException("simulated downstream failure");
        }

        @Override
        public void respond(HttpTypedResponse response) {
            throw new UnsupportedOperationException();
        }
    }
}
