/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.community.crypto.CommunityKernelCryptoProvider;
import eu.exeris.kernel.community.http.CommunityHttpProvider;
import eu.exeris.kernel.community.transport.TlsTestCertificate;
import eu.exeris.kernel.core.bootstrap.KernelBootstrap;
import eu.exeris.kernel.core.http.client.KernelWebClient;
import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException;
import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpKernelProviders;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpMode;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpRequestBodyEncoderRegistry;
import eu.exeris.kernel.spi.http.HttpResponseBodyDecoderRegistry;
import eu.exeris.kernel.spi.http.HttpVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
    void httpSubsystemServesHealthEndpointOverTls(@TempDir Path tlsMaterialDir) throws Exception {
        CommunityKernelCryptoProvider provider = createProviderOrSkip();
        provider.close();

        TlsTestCertificate certificate = TlsTestCertificate.generateInto(tlsMaterialDir);
        Path certPath = certificate.certificate();
        Path keyPath = certificate.privateKey();

        int port = nextFreePort();
        String previousMode = System.getProperty("exeris.http.mode");
        String previousBindHost = System.getProperty("exeris.http.bindHost");
        String previousPort = System.getProperty("exeris.http.port");
        String previousCert = System.getProperty("exeris.transport.certPath");
        String previousKey = System.getProperty("exeris.transport.keyPath");
        String previousTrust = System.getProperty("exeris.crypto.tls.client.trustFile");

        System.setProperty("exeris.http.mode", "SERVER");
        System.setProperty("exeris.http.bindHost", "127.0.0.1");
        System.setProperty("exeris.http.port", Integer.toString(port));
        System.setProperty("exeris.transport.certPath", certPath.toString());
        System.setProperty("exeris.transport.keyPath", keyPath.toString());
        // The client verifies the server: it trusts the server's self-signed certificate, and dials
        // 127.0.0.1, the certificate's IP subject alternative name.
        System.setProperty("exeris.crypto.tls.client.trustFile", certPath.toString());

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
                                HttpVersion.HTTP_1_1,
                                "127.0.0.1:" + port,
                                HttpConfig.DEFAULT_MAX_HEADER_BLOCK_SIZE,
                                HttpConfig.DEFAULT_MAX_HEADER_LIST_SIZE,
                                HttpConfig.DEFAULT_MAX_STRING_LITERAL_SIZE
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
            restoreProperty("exeris.crypto.tls.client.trustFile", previousTrust);
        }
    }

    @Test
    @DisplayName("a booted client's enricher sees the configured default peer of an unaddressed request (ADR-074)")
    void bootedClientEnricherSeesConfiguredDefaultAuthority() throws Exception {
        AtomicReference<String> seenByEnricher = new AtomicReference<>();

        try (StubPeer peer = new StubPeer()) {
            String defaultAuthority = peer.authority();

            bootClient(defaultAuthority, () ->
                    bootedClient(seenByEnricher).get("/orders", Void.class));

            assertThat(seenByEnricher.get())
                    .as("the engine a booted kernel binds must report the configured default before enrichment")
                    .isEqualTo(defaultAuthority);
            assertThat(peer.served())
                    .as("and the request must reach the peer the enricher was shown")
                    .isOne();
        }
    }

    @Test
    @DisplayName("a booted client's per-request authority wins over the configured default (ADR-074)")
    void bootedClientExplicitAuthorityWinsOverDefault() throws Exception {
        AtomicReference<String> seenByEnricher = new AtomicReference<>();

        try (StubPeer defaultPeer = new StubPeer(); StubPeer namedPeer = new StubPeer()) {
            String explicitAuthority = namedPeer.authority();

            bootClient(defaultPeer.authority(), () ->
                    bootedClient(seenByEnricher).withAuthority(explicitAuthority).get("/orders", Void.class));

            assertThat(seenByEnricher.get())
                    .as("an authority the caller named must reach the enricher unchanged")
                    .isEqualTo(explicitAuthority);
            assertThat(namedPeer.served()).as("the named peer receives the request").isOne();
            assertThat(defaultPeer.served()).as("the configured default is not dialled").isZero();
        }
    }

    // CLIENT mode: the kernel binds a client engine whose default peer is http.client.defaultAuthority
    // — the engine an application reaches through HttpKernelProviders.httpClientEngine().
    private static void bootClient(String defaultAuthority, Runnable insideKernel) throws Exception {
        String previousMode = System.getProperty("exeris.http.mode");
        String previousDefaultAuthority = System.getProperty("exeris.http.client.defaultAuthority");

        System.setProperty("exeris.http.mode", "CLIENT");
        System.setProperty("exeris.http.client.defaultAuthority", defaultAuthority);

        try {
            KernelBootstrap.builder()
                    .selector(BootstrapSelector.forNames("http"))
                    .build()
                    .boot(insideKernel);
        } finally {
            restoreProperty("exeris.http.mode", previousMode);
            restoreProperty("exeris.http.client.defaultAuthority", previousDefaultAuthority);
        }
    }

    // Must be called inside the booted scope: the engine and allocator are the kernel's bindings.
    private static KernelWebClient bootedClient(AtomicReference<String> seenByEnricher) {
        HttpClientEngine engine = HttpKernelProviders.httpClientEngine()
                .orElseThrow(() -> new AssertionError("CLIENT mode must bind HTTP_CLIENT_ENGINE"));
        return new KernelWebClient(
                engine,
                KernelProviders.MEMORY_ALLOCATOR.get(),
                HttpRequestBodyEncoderRegistry.of(List.of()),
                HttpResponseBodyDecoderRegistry.of(List.of()),
                request -> {
                    seenByEnricher.set(request.authority());
                    return request;
                });
    }

    /**
     * A loopback HTTP/1.1 peer that answers every request {@code 200} with an empty body and closes
     * the connection, counting the requests it read. Bound to an OS-assigned port before the kernel
     * boots, so the authority it reports is already listening.
     */
    private static final class StubPeer implements AutoCloseable {

        private static final byte[] RESPONSE =
                "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII);

        private final ServerSocket socket;
        private final AtomicInteger served = new AtomicInteger();
        private final Thread acceptor;

        StubPeer() throws IOException {
            socket = new ServerSocket();
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            acceptor = Thread.ofVirtual().name("stub-peer-" + socket.getLocalPort()).start(this::serve);
        }

        String authority() {
            return "127.0.0.1:" + socket.getLocalPort();
        }

        int served() {
            return served.get();
        }

        private void serve() {
            while (!socket.isClosed()) {
                try (Socket connection = socket.accept()) {
                    connection.setSoTimeout(2_000);
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
                    String line = reader.readLine();
                    while (line != null && !line.isEmpty()) {
                        line = reader.readLine();
                    }
                    served.incrementAndGet();
                    OutputStream out = connection.getOutputStream();
                    out.write(RESPONSE);
                    out.flush();
                } catch (IOException _) {
                    // accept() fails once close() has closed the socket; anything else ends one exchange.
                }
            }
        }

        @Override
        public void close() throws Exception {
            socket.close();
            acceptor.join(Duration.ofSeconds(5));
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
