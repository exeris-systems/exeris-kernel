/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.crypto.CommunityKernelCryptoProvider;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.community.transport.MapConfigProvider;
import eu.exeris.kernel.community.transport.TlsTestCertificate;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpMode;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpServerEngine;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.http.StreamEvent;
import eu.exeris.kernel.spi.http.StreamMatch;
import eu.exeris.kernel.spi.http.StreamRouteResolver;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A stream route is reachable over HTTP/2 on a real server, by each way a connection reaches HTTP/2: TLS
 * with ALPN {@code h2}, cleartext prior knowledge, and an {@code h2c} upgrade. Each case emits three
 * events and closes, and the client reads to END_STREAM, so nothing in it depends on a timing window
 * and it can run in every build; the held-open, parked and failing cases belong to the stream bindings.
 *
 * <p>The server is the production one — {@code CommunityHttpProvider#createServerEngine} — so the route is
 * resolved by the request loop the kernel ships, not by a harness. Fails rather than skips when OpenSSL
 * cannot be loaded, because a skipped case reports success.
 */
@DisplayName("Community: stream routes over HTTP/2 on a real server")
class CommunityHttp2StreamRouteIntegrationTest {

    private static final String HOST = "127.0.0.1";
    private static final int EVENTS = 3;

    @TempDir
    /* default */ static Path tlsMaterialDir;

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    private static CommunityKernelCryptoProvider crypto;
    private static TlsTestCertificate certificate;
    private static SSLContext clientTrust;

    @BeforeAll
    static void loadOpenSslAndMaterial() throws Exception {
        crypto = new CommunityKernelCryptoProvider();
        certificate = TlsTestCertificate.generateInto(tlsMaterialDir);
        clientTrust = Http2TestClient.trusting(certificate.certificate());
    }

    @AfterAll
    static void closeAllocator() {
        ALLOCATOR.close();
    }

    @Test
    @DisplayName("TLS with ALPN h2 reaches the stream route")
    void tlsWithAlpnReachesTheStreamRoute() {
        Started server = startServer(true);
        try (Http2TestClient client = Http2TestClient.connectTls(
                server.port(), clientTrust, "/stream", 65_535)) {
            assertStreamServed(client);
        } finally {
            server.engine().close();
        }
    }

    @Test
    @DisplayName("cleartext prior knowledge reaches the stream route")
    void priorKnowledgeReachesTheStreamRoute() {
        Started server = startServer(false);
        try (Http2TestClient client = Http2TestClient.connectPlain(
                server.port(), "/stream", 65_535, Http2TestClient.Start.PRIOR_KNOWLEDGE)) {
            assertStreamServed(client);
        } finally {
            server.engine().close();
        }
    }

    @Test
    @DisplayName("an h2c upgrade followed by a request on stream 3 reaches the stream route")
    void h2cUpgradeReachesTheStreamRouteOnANewStream() {
        Started server = startServer(false);
        try (Http2TestClient client = Http2TestClient.connectPlain(
                server.port(), "/stream", 65_535, Http2TestClient.Start.H2C_UPGRADE)) {
            assertStreamServed(client);
            assertThat(client.upgradeHead()).startsWith("HTTP/1.1 101 Switching Protocols");
        } finally {
            server.engine().close();
        }
    }

    private static void assertStreamServed(Http2TestClient client) {
        List<StreamEvent> events = client.awaitEvents(EVENTS);
        assertThat(client.awaitEndOfStream()).as("END_STREAM after the last event").isTrue();

        assertThat(client.awaitHead()).contains(
                Map.entry(":status", "200"),
                Map.entry("content-type", "text/event-stream; charset=utf-8"),
                Map.entry("cache-control", "no-cache"));
        assertThat(events).hasSize(EVENTS);
        for (int i = 0; i < EVENTS; i++) {
            assertThat(events.get(i).event()).isEqualTo("tick");
            assertThat(events.get(i).data()).isEqualTo(Integer.toString(i));
        }
        assertThat(client.resetCode()).as("the stream ended, it was not reset").isEqualTo(-1);
        assertThat(client.goAwayReceived()).isFalse();
    }

    private static Started startServer(boolean tls) {
        int port = freePort();
        HttpConfig config = new HttpConfig(
                HttpMode.SERVER,
                HOST,
                port,
                HttpConfig.DEFAULT_MAX_CONNECTIONS,
                HttpConfig.DEFAULT_IDLE_TIMEOUT_MS,
                HttpConfig.DEFAULT_MAX_HEADER_COUNT,
                HttpConfig.DEFAULT_MAX_HEADER_SIZE,
                HttpConfig.DEFAULT_MAX_REQUEST_BODY_BYTES,
                true,
                HttpVersion.HTTP_2,
                null,
                HttpConfig.DEFAULT_MAX_HEADER_BLOCK_SIZE,
                HttpConfig.DEFAULT_MAX_HEADER_LIST_SIZE,
                HttpConfig.DEFAULT_MAX_STRING_LITERAL_SIZE);
        HttpServerEngine[] holder = new HttpServerEngine[1];
        ScopedValue.Carrier scope = ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR);
        if (tls) {
            MapConfigProvider kernelConfig = new MapConfigProvider(
                    Map.of("transport.certPath", certificate.certificate().toString(),
                            "transport.keyPath", certificate.privateKey().toString()),
                    Map.of());
            scope = scope.where(KernelProviders.CRYPTO_PROVIDER, crypto)
                    .where(KernelProviders.CURRENT_CONFIG, kernelConfig);
        }
        scope.run(() -> holder[0] = new CommunityHttpProvider().createServerEngine(config));
        holder[0].setHandler(new Routes());
        holder[0].start();
        return new Started(holder[0], port);
    }

    private record Started(HttpServerEngine engine, int port) {
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to allocate free TCP port", ex);
        }
    }

    /** Serves {@code GET /stream} as a stream route that emits three events and closes. */
    private static final class Routes implements HttpHandler, StreamRouteResolver {

        @Override
        public void handle(HttpExchange exchange) {
            exchange.respond(HttpResponse.noBody(HttpStatus.NOT_FOUND, HttpVersion.HTTP_2));
        }

        @Override
        public StreamMatch resolveStream(HttpMethod method, String path) {
            if (method != HttpMethod.GET || !"/stream".equals(path)) {
                return null;
            }
            return StreamMatch.exact(exchange -> {
                for (int i = 0; i < EVENTS; i++) {
                    exchange.emit(StreamEvent.of("tick", Integer.toString(i)));
                }
                exchange.close();
            });
        }
    }
}
