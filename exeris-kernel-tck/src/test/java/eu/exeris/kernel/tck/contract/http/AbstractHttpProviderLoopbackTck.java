/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.http;

import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpProvider;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpServerEngine;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK: Provider-level request/response loopback contract over implementation transport.
 *
 * <p>This contract verifies that an {@link HttpProvider} can build a server/client pair
 * that performs a real request/response round-trip using SPI engines rather than fixture-only
 * lifecycle checks.
 *
 * @since 0.5.0
 */
public abstract class AbstractHttpProviderLoopbackTck {

    protected abstract HttpProvider createProvider();

    protected String loopbackHost() {
        return "127.0.0.1";
    }

    protected String requestPath() {
        return "/health";
    }

    protected HttpVersion requestVersion() {
        return HttpVersion.HTTP_1_1;
    }

    protected HttpStatus expectedStatus() {
        return HttpStatus.OK;
    }

    protected HttpConfig serverConfig(String host, int port) {
        return new HttpConfig(
                eu.exeris.kernel.spi.http.HttpMode.SERVER,
                host,
                port,
                HttpConfig.DEFAULT_MAX_CONNECTIONS,
                HttpConfig.DEFAULT_IDLE_TIMEOUT_MS,
                HttpConfig.DEFAULT_MAX_HEADER_COUNT,
                HttpConfig.DEFAULT_MAX_HEADER_SIZE,
                HttpConfig.DEFAULT_MAX_REQUEST_BODY_BYTES,
                true,
                HttpVersion.HTTP_2
        );
    }

    protected HttpConfig clientConfig(String host, int port) {
        // ADR-074: the peer is now a DIAL address the client is given, not the SERVER/DUAL listener
        // address it used to read out of bindHost. This fixture happened to work before only
        // because the two were the same value — a coincidence, now a setting. bindHost/port stay
        // populated so the rest of the fixture is unchanged; defaultAuthority is what the client
        // actually dials.
        return new HttpConfig(
                eu.exeris.kernel.spi.http.HttpMode.CLIENT,
                host,
                port,
                HttpConfig.DEFAULT_MAX_CONNECTIONS,
                HttpConfig.DEFAULT_IDLE_TIMEOUT_MS,
                HttpConfig.DEFAULT_MAX_HEADER_COUNT,
                HttpConfig.DEFAULT_MAX_HEADER_SIZE,
                HttpConfig.DEFAULT_MAX_REQUEST_BODY_BYTES,
                false,
                HttpVersion.HTTP_1_1,
                host + ":" + port
        );
    }

    protected HttpHandler serverHandler() {
        return exchange -> exchange.respond(HttpResponse.noBody(expectedStatus(), exchange.request().version()));
    }

    protected HttpServerEngine createServerEngine(HttpProvider provider, HttpConfig config) {
        return provider.createServerEngine(config);
    }

    protected HttpClientEngine createClientEngine(HttpProvider provider, HttpConfig config) {
        return provider.createClientEngine(config);
    }

    @Test
    @DisplayName("Provider supports request/response loopback via server+client engines")
    void providerSupportsRequestResponseLoopback() {
        HttpProvider provider = createProvider();
        String host = loopbackHost();
        int port = nextFreePort();

        try (HttpServerEngine serverEngine = createServerEngine(provider, serverConfig(host, port));
             HttpClientEngine clientEngine = createClientEngine(provider, clientConfig(host, port))) {
            serverEngine.setHandler(serverHandler());
            serverEngine.start();
            clientEngine.start();

            HttpResponse response = clientEngine.send(HttpRequest.noBody(
                    HttpMethod.GET,
                    requestPath(),
                    requestVersion(),
                    List.of()));

            assertThat(response.status().code()).isEqualTo(expectedStatus().code());

            if (response.body() != null) {
                response.body().close();
            }
        }
    }

    @Test
    @DisplayName("A request's authority overrides the engine's configured default peer (ADR-074)")
    void requestAuthorityOverridesTheConfiguredDefaultPeer() {
        HttpProvider provider = createProvider();
        String host = loopbackHost();
        int defaultPort = nextFreePort();
        int addressedPort = nextFreePort();

        // Two servers. The client is configured to default to the FIRST and the request names the
        // SECOND, so only a client that reads the request's authority can reach it. Before ADR-074
        // the engine took its peer from HttpConfig.bindHost at construction and never looked at the
        // request at all — this case is therefore unreachable on the previous behaviour rather than
        // merely differently-answered.
        AtomicReference<String> reachedBy = new AtomicReference<>();
        HttpHandler defaultHandler = exchange -> {
            reachedBy.set("default");
            exchange.respond(HttpResponse.noBody(expectedStatus(), exchange.request().version()));
        };
        HttpHandler addressedHandler = exchange -> {
            reachedBy.set("addressed");
            exchange.respond(HttpResponse.noBody(expectedStatus(), exchange.request().version()));
        };

        try (HttpServerEngine defaultServer = createServerEngine(provider, serverConfig(host, defaultPort));
             HttpServerEngine addressedServer = createServerEngine(provider, serverConfig(host, addressedPort));
             HttpClientEngine clientEngine = createClientEngine(provider, clientConfig(host, defaultPort))) {
            defaultServer.setHandler(defaultHandler);
            addressedServer.setHandler(addressedHandler);
            defaultServer.start();
            addressedServer.start();
            clientEngine.start();

            HttpResponse response = clientEngine.send(
                    HttpRequest.noBody(HttpMethod.GET, requestPath(), requestVersion(), List.of())
                            .withAuthority(host + ":" + addressedPort));

            assertThat(response.status().code()).isEqualTo(expectedStatus().code());
            if (response.body() != null) {
                response.body().close();
            }
        }

        assertThat(reachedBy.get())
                .as("the request named the second peer, so the second peer must be the one reached")
                .isEqualTo("addressed");
    }

    @Test
    @DisplayName("Provider terminates response read on Content-Length: 0")
    void providerTerminatesOnContentLengthZero() {
        HttpProvider provider = createProvider();
        String host = loopbackHost();
        int port = nextFreePort();

        HttpHandler handler = exchange -> exchange.respond(
                new HttpResponse(
                        HttpStatus.NO_CONTENT,
                        exchange.request().version(),
                        List.of(new HttpHeader("Content-Length", "0")),
                        null));

        try (HttpServerEngine serverEngine = createServerEngine(provider, serverConfig(host, port));
             HttpClientEngine clientEngine = createClientEngine(provider, clientConfig(host, port))) {
            serverEngine.setHandler(handler);
            serverEngine.start();
            clientEngine.start();

            HttpResponse response = clientEngine.send(HttpRequest.noBody(
                    HttpMethod.GET,
                    requestPath(),
                    requestVersion(),
                    List.of()));

            assertThat(response.status().code()).isEqualTo(204);
            if (response.body() != null) { response.body().close(); }
        }
    }

    private static int nextFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to allocate free TCP port", ex);
        }
    }
}
