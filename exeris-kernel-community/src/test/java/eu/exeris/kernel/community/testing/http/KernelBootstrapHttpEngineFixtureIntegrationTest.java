/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.testing.http;

import eu.exeris.kernel.community.testkit.http.EmbeddedHttpEngineFixture;
import eu.exeris.kernel.community.testkit.http.EmbeddedHttpEngineFixtures;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpKernelProviders;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpServerEngine;
import eu.exeris.kernel.spi.http.HttpStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Community fixture: KernelBootstrap HTTP engine seam")
class KernelBootstrapHttpEngineFixtureIntegrationTest {

    private static final String WRITE_PROBE_BODY = "{\"probe\":1}";

    /** A handler that answers every request 200; the runInKernelScope cases send nothing to it. */
    private static final HttpHandler OK_HANDLER = exchange -> exchange.respond(HttpResponse.noBody(
            HttpStatus.OK,
            exchange.request().version()));

    @Test
    @DisplayName("start() exposes running engine, deterministic bound port, and serves provided handler")
    void startExposesRunningEngineAndRoundtrip() {
        try (EmbeddedHttpEngineFixture fixture = EmbeddedHttpEngineFixtures.kernelBootstrapFixture()) {
            fixture.start(exchange -> {
                HttpResponse response = switch (exchange.request().path()) {
                    case "/fixture" -> HttpResponse.noBody(
                            HttpStatus.OK,
                            exchange.request().version(),
                            List.of(new HttpHeader("X-Fixture-Handler", "active")));
                    default -> HttpResponse.noBody(HttpStatus.NOT_FOUND, exchange.request().version());
                };
                exchange.respond(response);
            });

            int boundPort = fixture.boundPort();
            assertThat(boundPort).isPositive();
            assertThat(fixture.engine().isRunning()).isTrue();
            assertThat(fixture.isRunning()).isTrue();

            String response = sendRequest(boundPort, "/fixture");
            assertThat(response).contains("HTTP/1.1 200 OK");
            assertThat(response).contains("X-Fixture-Handler: active");
        }
    }

    @Test
    @DisplayName("close() stops runtime and endpoint becomes unavailable")
    void closeStopsRuntimeAndEndpointBecomesUnavailable() {
        try (EmbeddedHttpEngineFixture fixture = EmbeddedHttpEngineFixtures.kernelBootstrapFixture()) {
            fixture.start(exchange -> exchange.respond(HttpResponse.noBody(
                    HttpStatus.OK,
                    exchange.request().version())));
            int port = fixture.boundPort();

            assertThat(sendRequest(port, "/health")).contains("HTTP/1.1 200 OK");

            fixture.close();
            assertThat(fixture.isRunning()).isFalse();

            assertThatThrownBy(() -> sendRequest(port, "/health"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("HTTP request failed");
        }
    }

    @Test
    @DisplayName("engine() and boundPort() throw before start()")
    void accessorsThrowBeforeStart() {
        try (EmbeddedHttpEngineFixture fixture = EmbeddedHttpEngineFixtures.kernelBootstrapFixture()) {
            assertThatThrownBy(fixture::engine)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("has not been started");
            assertThatThrownBy(fixture::boundPort)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("has not been started");
        }
    }

    @Test
    @DisplayName("runInKernelScope() runs off the caller's thread, inside the boot the engine was started in")
    void runInKernelScopeRunsInsideTheBoot() {
        try (EmbeddedHttpEngineFixture fixture = EmbeddedHttpEngineFixtures.kernelBootstrapFixture()) {
            fixture.start(OK_HANDLER);
            Thread caller = Thread.currentThread();
            AtomicReference<Thread> ranOn = new AtomicReference<>();
            AtomicReference<Boolean> configBound = new AtomicReference<>();
            AtomicReference<HttpServerEngine> engineBound = new AtomicReference<>();
            AtomicReference<HttpHandler> handlerBound = new AtomicReference<>();

            fixture.runInKernelScope(() -> {
                ranOn.set(Thread.currentThread());
                configBound.set(KernelProviders.CURRENT_CONFIG.isBound());
                engineBound.set(HttpKernelProviders.HTTP_SERVER_ENGINE.isBound()
                        ? HttpKernelProviders.HTTP_SERVER_ENGINE.get() : null);
                handlerBound.set(HttpKernelProviders.HTTP_SERVER_HANDLER.isBound()
                        ? HttpKernelProviders.HTTP_SERVER_HANDLER.get() : null);
            });

            assertThat(ranOn.get()).as("ran-guard: the body ran").isNotNull();
            assertThat(ranOn.get()).as("the body runs on the boot's thread, not the caller's").isNotSameAs(caller);
            assertThat(configBound.get()).as("CURRENT_CONFIG, bound around the whole boot").isTrue();
            assertThat(engineBound.get())
                    .as("HTTP_SERVER_ENGINE, bound by the HTTP subsystem, is the engine the fixture started")
                    .isSameAs(fixture.engine());
            assertThat(handlerBound.get())
                    .as("HTTP_SERVER_HANDLER is the handler start() was given")
                    .isSameAs(OK_HANDLER);
            assertThat(KernelProviders.CURRENT_CONFIG.isBound())
                    .as("the caller's thread is outside the boot")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("runInKernelScope() rethrows the body's exception on the caller's thread and keeps serving")
    void runInKernelScopeRethrowsOnTheCaller() {
        try (EmbeddedHttpEngineFixture fixture = EmbeddedHttpEngineFixtures.kernelBootstrapFixture()) {
            fixture.start(OK_HANDLER);
            IllegalArgumentException thrown = new IllegalArgumentException("thrown inside the boot");

            assertThatThrownBy(() -> fixture.runInKernelScope(() -> {
                throw thrown;
            })).isSameAs(thrown);

            AtomicReference<Boolean> ranAfter = new AtomicReference<>(false);
            fixture.runInKernelScope(() -> ranAfter.set(true));
            assertThat(ranAfter.get()).as("a body that threw does not stop the next one").isTrue();
        }
    }

    @Test
    @DisplayName("runInKernelScope() refuses before start() and after close()")
    void runInKernelScopeRefusesOutsideALiveBoot() {
        try (EmbeddedHttpEngineFixture fixture = EmbeddedHttpEngineFixtures.kernelBootstrapFixture()) {
            assertThatThrownBy(() -> fixture.runInKernelScope(() -> { }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("has not been started");

            fixture.start(OK_HANDLER);
            fixture.close();

            assertThatThrownBy(() -> fixture.runInKernelScope(() -> { }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("has not been started");
        }
    }

    @ParameterizedTest(name = "{0} with a body round-trips, and request scope is complete")
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    @DisplayName("a body-carrying request reaches the handler with the allocator and decoders bound")
    void writeMethodsCarryBodyAndRequestScope(String method) {
        try (EmbeddedHttpEngineFixture fixture = EmbeddedHttpEngineFixtures.kernelBootstrapFixture()) {
            fixture.start(exchange -> exchange.respond(HttpResponse.noBody(
                    HttpStatus.OK,
                    exchange.request().version(),
                    List.of(
                            new HttpHeader("X-Method", exchange.request().method().toString()),
                            new HttpHeader("X-Body-Size", bodySize(exchange.request().body())),
                            new HttpHeader("X-Allocator-Bound",
                                    String.valueOf(KernelProviders.MEMORY_ALLOCATOR.isBound())),
                            new HttpHeader("X-Decoders-Bound",
                                    String.valueOf(HttpKernelProviders
                                            .HTTP_REQUEST_BODY_DECODER_REGISTRY.isBound()))))));

            String response = sendWithBody(fixture.boundPort(), method, WRITE_PROBE_BODY);

            assertThat(response).contains("HTTP/1.1 200 OK");
            assertThat(response).contains("X-Method: " + method);
            // The body must arrive whole. A fixture that bound less than the production path would
            // either answer 4xx or hand the handler nothing, which is what this pins.
            assertThat(response).contains("X-Body-Size: " + WRITE_PROBE_BODY.length());
            assertThat(response)
                    .as("MEMORY_ALLOCATOR must be bound in request scope, not only in carrier scope")
                    .contains("X-Allocator-Bound: true");
            assertThat(response)
                    .as("the request-body decoder registry must be bound at handler time")
                    .contains("X-Decoders-Bound: true");
        }
    }

    private static String bodySize(LoanedBuffer body) {
        return body == null ? "absent" : String.valueOf(body.size());
    }

    private static String sendWithBody(int port, String method, String body) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
            socket.setSoTimeout(3_000);

            try (Writer writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII);
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))) {
                writer.write(method + " /fixture HTTP/1.1\r\n");
                writer.write("Host: 127.0.0.1\r\n");
                writer.write("Content-Type: application/json\r\n");
                writer.write("Content-Length: " + body.length() + "\r\n");
                writer.write("Connection: close\r\n");
                writer.write("\r\n");
                writer.write(body);
                writer.flush();

                StringBuilder response = new StringBuilder();
                String line = reader.readLine();
                while (line != null) {
                    response.append(line).append('\n');
                    line = reader.readLine();
                }
                return response.toString();
            }
        } catch (Exception exception) {
            throw new IllegalStateException(method + " request failed", exception);
        }
    }

    private static String sendRequest(int port, String path) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
            socket.setSoTimeout(2_000);

            try (Writer writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII);
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))) {
                writer.write("GET " + path + " HTTP/1.1\r\n");
                writer.write("Host: 127.0.0.1\r\n");
                writer.write("Connection: close\r\n");
                writer.write("\r\n");
                writer.flush();

                StringBuilder response = new StringBuilder();
                String line = reader.readLine();
                while (line != null) {
                    response.append(line).append("\n");
                    line = reader.readLine();
                }
                return response.toString();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("HTTP request failed for path: " + path, exception);
        }
    }
}
