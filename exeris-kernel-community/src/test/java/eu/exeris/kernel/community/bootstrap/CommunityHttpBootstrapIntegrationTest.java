/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.community.crypto.CommunityKernelCryptoProvider;
import eu.exeris.kernel.community.http.CommunityHttpProvider;
import eu.exeris.kernel.core.bootstrap.KernelBootstrap;
import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException;
import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpKernelProviders;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpMode;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@DisplayName("Community: HTTP bootstrap integration")
class CommunityHttpBootstrapIntegrationTest {

    @Test
    @DisplayName("KernelBootstrap with HTTP subsystem binds server engine and serves /health over TCP")
    void httpSubsystemServesHealthEndpoint() throws Exception {
        int port = nextFreePort();
        String previousMode = System.getProperty("exeris.http.mode");
        String previousBindHost = System.getProperty("exeris.http.bindHost");
        String previousPort = System.getProperty("exeris.http.port");

        System.setProperty("exeris.http.mode", "SERVER");
        System.setProperty("exeris.http.bindHost", "127.0.0.1");
        System.setProperty("exeris.http.port", Integer.toString(port));

        try {
            KernelBootstrap.builder()
                    .selector(BootstrapSelector.forNames("http"))
                    .build()
                    .boot(() -> {
                        assertThat(HttpKernelProviders.HTTP_PROVIDER.isBound()).isTrue();
                        assertThat(HttpKernelProviders.HTTP_SERVER_ENGINE.isBound()).isTrue();
                        assertThat(HttpKernelProviders.httpServerEngine().isRunning()).isTrue();

                        String healthResponse = sendRequest(port, "/health");
                        assertThat(healthResponse).contains("HTTP/1.1 200 OK");

                        String liveResponse = sendRequest(port, "/health/live");
                        assertThat(liveResponse).contains("HTTP/1.1 200 OK");

                        String readyResponse = sendRequest(port, "/health/ready");
                        assertThat(readyResponse).contains("HTTP/1.1 200 OK");
                    });
        } finally {
            restoreProperty("exeris.http.mode", previousMode);
            restoreProperty("exeris.http.bindHost", previousBindHost);
            restoreProperty("exeris.http.port", previousPort);
        }
    }

    @Test
    @DisplayName("KernelBootstrap with HTTP subsystem stays disabled when neither mode nor port is configured")
    void httpSubsystemRemainsDisabledWithoutExplicitModeOrPort() throws Exception {
        String previousMode = System.getProperty("exeris.http.mode");
        String previousHttpPort = System.getProperty("exeris.http.port");
        String previousNetworkPort = System.getProperty("exeris.network.port");
        String previousBindHost = System.getProperty("exeris.http.bindHost");

        System.clearProperty("exeris.http.mode");
        System.clearProperty("exeris.http.port");
        System.clearProperty("exeris.network.port");
        System.clearProperty("exeris.http.bindHost");

        try {
            KernelBootstrap.builder()
                    .selector(BootstrapSelector.forNames("http"))
                    .build()
                    .boot(() -> {
                        assertThat(HttpKernelProviders.HTTP_PROVIDER.isBound()).isFalse();
                        assertThat(HttpKernelProviders.HTTP_SERVER_ENGINE.isBound()).isFalse();
                        assertThat(HttpKernelProviders.HTTP_CLIENT_ENGINE.isBound()).isFalse();
                    });
        } finally {
            restoreProperty("exeris.http.mode", previousMode);
            restoreProperty("exeris.http.port", previousHttpPort);
            restoreProperty("exeris.network.port", previousNetworkPort);
            restoreProperty("exeris.http.bindHost", previousBindHost);
        }
    }

    @Test
    @DisplayName("KernelBootstrap with HTTP subsystem serves health when only network.port is configured")
    void httpSubsystemServesHealthEndpointWhenNetworkPortIsConfiguredWithoutExplicitMode() throws Exception {
        int port = nextFreePort();
        String previousMode = System.getProperty("exeris.http.mode");
        String previousNetworkPort = System.getProperty("exeris.network.port");

        System.clearProperty("exeris.http.mode");
        System.setProperty("exeris.network.port", Integer.toString(port));

        try {
            KernelBootstrap.builder()
                    .selector(BootstrapSelector.forNames("http"))
                    .build()
                    .boot(() -> {
                        assertThat(HttpKernelProviders.HTTP_SERVER_ENGINE.isBound()).isTrue();
                        assertThat(HttpKernelProviders.httpServerEngine().isRunning()).isTrue();

                        String healthResponse = sendRequest(port, "/health");
                        assertThat(healthResponse).contains("HTTP/1.1 200 OK");
                    });
        } finally {
            restoreProperty("exeris.http.mode", previousMode);
            restoreProperty("exeris.network.port", previousNetworkPort);
        }
    }

    @Test
    @DisplayName("HTTP ScopedValues are unbound after boot returns")
    void httpScopedValuesAreUnboundAfterBoot() throws Exception {
        int port = nextFreePort();
        String previousMode = System.getProperty("exeris.http.mode");
        String previousBindHost = System.getProperty("exeris.http.bindHost");
        String previousPort = System.getProperty("exeris.http.port");
        AtomicBoolean boundInside = new AtomicBoolean(false);

        System.setProperty("exeris.http.mode", "SERVER");
        System.setProperty("exeris.http.bindHost", "127.0.0.1");
        System.setProperty("exeris.http.port", Integer.toString(port));

        try {
            KernelBootstrap.builder()
                    .selector(BootstrapSelector.forNames("http"))
                    .build()
                    .boot(() -> boundInside.set(HttpKernelProviders.HTTP_SERVER_ENGINE.isBound()));
        } finally {
            restoreProperty("exeris.http.mode", previousMode);
            restoreProperty("exeris.http.bindHost", previousBindHost);
            restoreProperty("exeris.http.port", previousPort);
        }

        assertThat(boundInside.get()).isTrue();
        assertThat(HttpKernelProviders.HTTP_PROVIDER.isBound()).isFalse();
        assertThat(HttpKernelProviders.HTTP_SERVER_ENGINE.isBound()).isFalse();
    }

    @Test
    @DisplayName("KernelBootstrap with crypto+http serves /health over TLS to Community HttpClientEngine")
    void httpSubsystemServesHealthEndpointOverTls() throws Exception {
        CommunityKernelCryptoProvider provider = createProviderOrSkip();
        provider.close();

        Path certPath = Path.of("..", "native-libs", "certs", "server.crt").normalize();
        Path keyPath = Path.of("..", "native-libs", "certs", "server.key").normalize();
        assumeTrue(Files.isRegularFile(certPath) && Files.isRegularFile(keyPath),
                "TLS test cert/key not found — skipping HTTPS bootstrap test");

        int port = nextFreePort();
        String previousMode = System.getProperty("exeris.http.mode");
        String previousBindHost = System.getProperty("exeris.http.bindHost");
        String previousPort = System.getProperty("exeris.http.port");
        String previousCert = System.getProperty("exeris.transport.certPath");
        String previousKey = System.getProperty("exeris.transport.keyPath");

        System.setProperty("exeris.http.mode", "SERVER");
        System.setProperty("exeris.http.bindHost", "127.0.0.1");
        System.setProperty("exeris.http.port", Integer.toString(port));
        System.setProperty("exeris.transport.certPath", certPath.toString());
        System.setProperty("exeris.transport.keyPath", keyPath.toString());

        try {
            KernelBootstrap.builder()
                    .selector(BootstrapSelector.forNames("crypto", "http"))
                    .build()
                    .boot(() -> {
                        HttpConfig clientConfig = new HttpConfig(
                                HttpMode.CLIENT,
                                "127.0.0.1",
                                port,
                                HttpConfig.DEFAULT_MAX_CONNECTIONS,
                                HttpConfig.DEFAULT_IDLE_TIMEOUT_MS,
                                HttpConfig.DEFAULT_MAX_HEADER_COUNT,
                                HttpConfig.DEFAULT_MAX_HEADER_SIZE,
                                HttpConfig.DEFAULT_MAX_REQUEST_BODY_BYTES,
                                false,
                                HttpVersion.HTTP_1_1
                        );
                        try (HttpClientEngine client = new CommunityHttpProvider().createClientEngine(clientConfig)) {
                            client.start();
                            var response = client.send(HttpRequest.noBody(
                                    HttpMethod.GET,
                                    "/health",
                                    HttpVersion.HTTP_1_1,
                                    java.util.List.of()));
                            assertThat(response.status().code()).isEqualTo(200);
                            if (response.body() != null) {
                                response.body().close();
                            }

                            var liveResponse = client.send(HttpRequest.noBody(
                                    HttpMethod.GET,
                                    "/health/live",
                                    HttpVersion.HTTP_1_1,
                                    java.util.List.of()));
                            assertThat(liveResponse.status().code()).isEqualTo(200);
                            if (liveResponse.body() != null) {
                                liveResponse.body().close();
                            }

                            var readyResponse = client.send(HttpRequest.noBody(
                                    HttpMethod.GET,
                                    "/health/ready",
                                    HttpVersion.HTTP_1_1,
                                    java.util.List.of()));
                            assertThat(readyResponse.status().code()).isEqualTo(200);
                            if (readyResponse.body() != null) {
                                readyResponse.body().close();
                            }
                        }
                    });
        } finally {
            restoreProperty("exeris.http.mode", previousMode);
            restoreProperty("exeris.http.bindHost", previousBindHost);
            restoreProperty("exeris.http.port", previousPort);
            restoreProperty("exeris.transport.certPath", previousCert);
            restoreProperty("exeris.transport.keyPath", previousKey);
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
        } catch (Exception ex) {
            throw new IllegalStateException("HTTP request failed for path: " + path, ex);
        }
    }

    private static int nextFreePort() {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to allocate free TCP port", ex);
        }
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
            return;
        }
        System.setProperty(key, value);
    }

    private static CommunityKernelCryptoProvider createProviderOrSkip() {
        try {
            return new CommunityKernelCryptoProvider();
        } catch (CryptoBootstrapException exception) {
            assumeTrue(false, "OpenSSL 3.x not available on this host — skipping TLS bootstrap test");
            throw new IllegalStateException("unreachable", exception);
        }
    }
}
