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
 * @since 0.5
 */
public abstract class AbstractHttpProviderLoopbackTck {

    /**
     * Creates the {@link HttpProvider} under test.
     *
     * @return the provider under test; never {@code null}
     * @implSpec The same provider instance backs both {@link #createServerEngine} and
     *           {@link #createClientEngine} within a given test, so the provider's server and
     *           client engines must interoperate over its own transport.
     */
    protected abstract HttpProvider createProvider();

    /**
     * Returns the loopback address the fixture's server binds to and the client dials.
     *
     * @return a loopback host address; defaults to {@code "127.0.0.1"}
     * @apiNote Override to exercise a different loopback interface; the value must resolve
     *          locally for both the server bind and the client connect.
     */
    protected String loopbackHost() {
        return "127.0.0.1";
    }

    /**
     * Returns the request path the fixture sends and the server handler answers.
     *
     * @return a request path; defaults to {@code "/health"}
     * @apiNote Override to exercise routing behaviour specific to a driver's engine.
     */
    protected String requestPath() {
        return "/health";
    }

    /**
     * Returns the HTTP version the fixture's request is sent with.
     *
     * @return the request's HTTP version; defaults to {@link HttpVersion#HTTP_1_1}
     * @apiNote Override to verify the loopback round-trip over a different negotiated version.
     */
    protected HttpVersion requestVersion() {
        return HttpVersion.HTTP_1_1;
    }

    /**
     * Returns the status the fixture's server handler responds with and the test asserts.
     *
     * @return the expected response status; defaults to {@link HttpStatus#OK}
     * @apiNote Override together with {@link #serverHandler()} to exercise a different status.
     */
    protected HttpStatus expectedStatus() {
        return HttpStatus.OK;
    }

    /**
     * Returns the {@link HttpConfig} used to create the fixture's server engine.
     *
     * @param host the address the server binds to
     * @param port the port the server binds to
     * @return a server-mode configuration with HTTP/2 negotiation enabled and the module's
     *         default connection, timeout, and header limits
     */
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

    /**
     * Returns the {@link HttpConfig} used to create the fixture's client engine.
     *
     * @param host the server's address, used as the client's default dial authority
     * @param port the server's port, used as the client's default dial authority
     * @return a client-mode configuration whose default authority is {@code host:port}
     * @apiNote The default authority is a dial address supplied to the client (ADR-074),
     *          distinct from a server's bind address; a request naming its own authority
     *          overrides it.
     */
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
                host + ":" + port,
                HttpConfig.DEFAULT_MAX_HEADER_BLOCK_SIZE,
                HttpConfig.DEFAULT_MAX_HEADER_LIST_SIZE,
                HttpConfig.DEFAULT_MAX_STRING_LITERAL_SIZE
        );
    }

    /**
     * Returns the {@link HttpHandler} the fixture's server serves the loopback request with.
     *
     * @return a handler that responds with {@link #expectedStatus()} and no body
     * @apiNote Override together with {@link #expectedStatus()} to exercise a different
     *          response shape.
     */
    protected HttpHandler serverHandler() {
        return exchange -> exchange.respond(HttpResponse.noBody(expectedStatus(), exchange.request().version()));
    }

    /**
     * Creates the fixture's server engine from the given provider and configuration.
     *
     * @param provider the provider under test
     * @param config   the server configuration to create the engine from
     * @return a fresh, not-yet-started server engine; never {@code null}
     * @apiNote Delegates to {@link HttpProvider#createServerEngine(HttpConfig)}; override only
     *          to wrap or instrument the engine a driver under test produces.
     */
    protected HttpServerEngine createServerEngine(HttpProvider provider, HttpConfig config) {
        return provider.createServerEngine(config);
    }

    /**
     * Creates the fixture's client engine from the given provider and configuration.
     *
     * @param provider the provider under test
     * @param config   the client configuration to create the engine from
     * @return a fresh, not-yet-started client engine; never {@code null}
     * @apiNote Delegates to {@link HttpProvider#createClientEngine(HttpConfig)}; override only
     *          to wrap or instrument the engine a driver under test produces.
     */
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
