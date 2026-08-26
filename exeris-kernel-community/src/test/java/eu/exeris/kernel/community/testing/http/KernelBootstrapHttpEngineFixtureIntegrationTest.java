/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.testing.http;

import eu.exeris.kernel.community.testkit.http.EmbeddedHttpEngineFixture;
import eu.exeris.kernel.community.testkit.http.EmbeddedHttpEngineFixtures;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Community fixture: KernelBootstrap HTTP engine seam")
class KernelBootstrapHttpEngineFixtureIntegrationTest {

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
