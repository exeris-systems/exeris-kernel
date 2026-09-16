/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.http;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.FaultOrigin;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
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
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
     * Creates the contract; subclasses supply the provider under test via {@link #createProvider()}.
     */
    public AbstractHttpProviderLoopbackTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

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

    @Test
    @DisplayName("Provider handles sequential requests on the same client engine")
    void providerHandlesSequentialRequests() {
        HttpProvider provider = createProvider();
        String host = loopbackHost();
        int port = nextFreePort();

        AtomicInteger requestsServed = new AtomicInteger(0);
        HttpHandler handler = exchange -> {
            requestsServed.incrementAndGet();
            exchange.respond(HttpResponse.noBody(expectedStatus(), exchange.request().version()));
        };

        try (HttpServerEngine serverEngine = createServerEngine(provider, serverConfig(host, port));
             HttpClientEngine clientEngine = createClientEngine(provider, clientConfig(host, port))) {
            serverEngine.setHandler(handler);
            serverEngine.start();
            clientEngine.start();

            for (int i = 0; i < 3; i++) {
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
        assertThat(requestsServed.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("Sequential 204 No Content responses do not hang and complete cleanly")
    void sequentialNoContentResponsesCompleteCleanly() {
        HttpProvider provider = createProvider();
        String host = loopbackHost();
        int port = nextFreePort();

        HttpHandler handler = exchange -> exchange.respond(
                HttpResponse.noBody(HttpStatus.NO_CONTENT, exchange.request().version()));

        try (HttpServerEngine serverEngine = createServerEngine(provider, serverConfig(host, port));
             HttpClientEngine clientEngine = createClientEngine(provider, clientConfig(host, port))) {
            serverEngine.setHandler(handler);
            serverEngine.start();
            clientEngine.start();

            for (int i = 0; i < 2; i++) {
                HttpResponse response = clientEngine.send(HttpRequest.noBody(
                        HttpMethod.GET,
                        requestPath(),
                        requestVersion(),
                        List.of()));
                assertThat(response.status().code()).isEqualTo(204);
                if (response.body() != null) {
                    response.body().close();
                }
            }
        }
    }

    @Test
    @DisplayName("Server response with Connection: close is handled cleanly across sequential calls")
    void serverConnectionCloseHandledCleanly() {
        HttpProvider provider = createProvider();
        String host = loopbackHost();
        int port = nextFreePort();

        HttpHandler handler = exchange -> exchange.respond(new HttpResponse(
                expectedStatus(),
                exchange.request().version(),
                List.of(new HttpHeader("Connection", "close")),
                null));

        try (HttpServerEngine serverEngine = createServerEngine(provider, serverConfig(host, port));
             HttpClientEngine clientEngine = createClientEngine(provider, clientConfig(host, port))) {
            serverEngine.setHandler(handler);
            serverEngine.start();
            clientEngine.start();

            for (int i = 0; i < 2; i++) {
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
    }

    @Test
    @DisplayName("HEAD request followed by GET request succeeds without corruption")
    void headRequestFollowedByGetRequest() {
        HttpProvider provider = createProvider();
        String host = loopbackHost();
        int port = nextFreePort();

        HttpHandler handler = exchange -> exchange.respond(
                HttpResponse.noBody(expectedStatus(), exchange.request().version()));

        try (HttpServerEngine serverEngine = createServerEngine(provider, serverConfig(host, port));
             HttpClientEngine clientEngine = createClientEngine(provider, clientConfig(host, port))) {
            serverEngine.setHandler(handler);
            serverEngine.start();
            clientEngine.start();

            HttpResponse headResponse = clientEngine.send(HttpRequest.noBody(
                    HttpMethod.HEAD,
                    requestPath(),
                    requestVersion(),
                    List.of()));
            assertThat(headResponse.status().code()).isEqualTo(expectedStatus().code());
            if (headResponse.body() != null) {
                headResponse.body().close();
            }

            HttpResponse getResponse = clientEngine.send(HttpRequest.noBody(
                    HttpMethod.GET,
                    requestPath(),
                    requestVersion(),
                    List.of()));
            assertThat(getResponse.status().code()).isEqualTo(expectedStatus().code());
            if (getResponse.body() != null) {
                getResponse.body().close();
            }
        }
    }

    @Test
    @DisplayName("Client request with Connection: close is handled cleanly across sequential calls")
    void clientConnectionCloseHandledCleanly() {
        HttpProvider provider = createProvider();
        String host = loopbackHost();
        int port = nextFreePort();
        AtomicInteger serverHits = new AtomicInteger(0);

        HttpHandler handler = exchange -> {
            serverHits.incrementAndGet();
            exchange.respond(HttpResponse.noBody(expectedStatus(), exchange.request().version()));
        };

        try (HttpServerEngine serverEngine = createServerEngine(provider, serverConfig(host, port));
             HttpClientEngine clientEngine = createClientEngine(provider, clientConfig(host, port))) {
            serverEngine.setHandler(handler);
            serverEngine.start();
            clientEngine.start();

            for (int i = 0; i < 2; i++) {
                HttpResponse response = clientEngine.send(HttpRequest.noBody(
                        HttpMethod.GET,
                        requestPath(),
                        requestVersion(),
                        List.of(new HttpHeader("Connection", "close"))));
                assertThat(response.status().code()).isEqualTo(expectedStatus().code());
                if (response.body() != null) {
                    response.body().close();
                }
            }
            assertThat(serverHits.get()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("304 Not Modified response is handled cleanly across sequential calls")
    void notModifiedResponseHandledCleanlyAcrossSequentialCalls() {
        HttpProvider provider = createProvider();
        String host = loopbackHost();
        int port = nextFreePort();

        HttpHandler handler = exchange -> exchange.respond(
                HttpResponse.noBody(HttpStatus.NOT_MODIFIED, exchange.request().version()));

        try (HttpServerEngine serverEngine = createServerEngine(provider, serverConfig(host, port));
             HttpClientEngine clientEngine = createClientEngine(provider, clientConfig(host, port))) {
            serverEngine.setHandler(handler);
            serverEngine.start();
            clientEngine.start();

            for (int i = 0; i < 2; i++) {
                HttpResponse response = clientEngine.send(HttpRequest.noBody(
                        HttpMethod.GET,
                        requestPath(),
                        requestVersion(),
                        List.of()));
                assertThat(response.status().code()).isEqualTo(304);
                if (response.body() != null) {
                    response.body().close();
                }
            }
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("Client request on unreachable target throws fail-fast without implicit retry")
    void closedTargetThrowsWithoutImplicitRetry() {
        HttpProvider provider = createProvider();
        String host = loopbackHost();
        int port = nextFreePort();

        try (HttpClientEngine clientEngine = createClientEngine(provider, clientConfig(host, port))) {
            clientEngine.start();
            assertThatThrownBy(() -> clientEngine.send(HttpRequest.noBody(
                    HttpMethod.GET,
                    requestPath(),
                    requestVersion(),
                    List.of())))
                    .isInstanceOf(ExerisKernelException.class)
                    .satisfies(ex -> {
                        ExerisKernelException eke = (ExerisKernelException) ex;
                        assertThat(eke.errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4009);
                        assertThat(eke.faultOrigin()).isEqualTo(FaultOrigin.SYSTEM);
                    });
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("Client request on severed server connection throws fail-fast without transparent retry")
    void stalePooledConnectionThrowsFailFastWhenServerSevered() {
        HttpProvider provider = createProvider();
        String host = loopbackHost();
        int port = nextFreePort();

        HttpHandler handler = exchange -> exchange.respond(
                HttpResponse.noBody(expectedStatus(), exchange.request().version()));

        try (HttpServerEngine serverEngine = createServerEngine(provider, serverConfig(host, port));
             HttpClientEngine clientEngine = createClientEngine(provider, clientConfig(host, port))) {
            serverEngine.setHandler(handler);
            serverEngine.start();
            clientEngine.start();

            // First request succeeds and returns warm connection to client pool
            HttpResponse response = clientEngine.send(HttpRequest.noBody(
                    HttpMethod.GET,
                    requestPath(),
                    requestVersion(),
                    List.of()));
            assertThat(response.status().code()).isEqualTo(expectedStatus().code());
            if (response.body() != null) {
                response.body().close();
            }

            // Close server to sever the TCP connection
            serverEngine.close();

            // Second request on now-stale pooled connection must fail-fast without transparent retry
            assertThatThrownBy(() -> clientEngine.send(HttpRequest.noBody(
                    HttpMethod.GET,
                    requestPath(),
                    requestVersion(),
                    List.of())))
                    .isInstanceOf(ExerisKernelException.class)
                    .satisfies(ex -> {
                        ExerisKernelException eke = (ExerisKernelException) ex;
                        assertThat(eke.errorCode()).isIn(
                                KernelErrorCodes.EX_HTTP_4009,
                                KernelErrorCodes.EX_NET_4002);
                        assertThat(eke.faultOrigin()).isEqualTo(FaultOrigin.SYSTEM);
                    });
        }
    }

    @Test
    @DisplayName("Client engine does not implicitly retry on server failure (ADR-045 / ADR-026)")
    void clientDoesNotImplicitlyRetryOnServerFailure() {
        HttpProvider provider = createProvider();
        String host = loopbackHost();
        int port = nextFreePort();
        AtomicInteger serverHits = new AtomicInteger(0);

        HttpHandler handler = exchange -> {
            serverHits.incrementAndGet();
            exchange.respond(HttpResponse.noBody(HttpStatus.SERVICE_UNAVAILABLE, exchange.request().version()));
        };

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
            assertThat(response.status().code()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.code());
            if (response.body() != null) {
                response.body().close();
            }

            // Active server verifies client executed exactly one attempt without transparent retry
            assertThat(serverHits.get()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("Conflicting Content-Length headers in server response throws fail-fast (RFC 9112 §6.3)")
    void conflictingContentLengthHeadersThrowsFailFast() {
        HttpProvider provider = createProvider();
        String host = loopbackHost();
        int port = nextFreePort();

        HttpHandler handler = exchange -> exchange.respond(new HttpResponse(
                HttpStatus.OK,
                exchange.request().version(),
                List.of(new HttpHeader("Content-Length", "10"), new HttpHeader("Content-Length", "20")),
                null));

        try (HttpServerEngine serverEngine = createServerEngine(provider, serverConfig(host, port));
             HttpClientEngine clientEngine = createClientEngine(provider, clientConfig(host, port))) {
            serverEngine.setHandler(handler);
            serverEngine.start();
            clientEngine.start();

            assertThatThrownBy(() -> clientEngine.send(HttpRequest.noBody(
                    HttpMethod.GET,
                    requestPath(),
                    requestVersion(),
                    List.of())))
                    .isInstanceOf(ExerisKernelException.class)
                    .satisfies(ex -> {
                        ExerisKernelException eke = (ExerisKernelException) ex;
                        assertThat(eke.errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004);
                        assertThat(eke.faultOrigin()).isEqualTo(FaultOrigin.SYSTEM);
                    });
        }
    }

    @Test
    @DisplayName("Negative Content-Length header in server response throws fail-fast (RFC 9110 §8.6)")
    void negativeContentLengthHeaderThrowsFailFast() {
        HttpProvider provider = createProvider();
        String host = loopbackHost();
        int port = nextFreePort();

        HttpHandler handler = exchange -> exchange.respond(new HttpResponse(
                HttpStatus.OK,
                exchange.request().version(),
                List.of(new HttpHeader("Content-Length", "-5")),
                null));

        try (HttpServerEngine serverEngine = createServerEngine(provider, serverConfig(host, port));
             HttpClientEngine clientEngine = createClientEngine(provider, clientConfig(host, port))) {
            serverEngine.setHandler(handler);
            serverEngine.start();
            clientEngine.start();

            assertThatThrownBy(() -> clientEngine.send(HttpRequest.noBody(
                    HttpMethod.GET,
                    requestPath(),
                    requestVersion(),
                    List.of())))
                    .isInstanceOf(ExerisKernelException.class)
                    .satisfies(ex -> {
                        ExerisKernelException eke = (ExerisKernelException) ex;
                        assertThat(eke.errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004);
                        assertThat(eke.faultOrigin()).isEqualTo(FaultOrigin.SYSTEM);
                    });
        }
    }

    /**
     * Allocates an ephemeral TCP port for loopback testing.
     *
     * <p>Note: Opening and immediately closing a {@link ServerSocket} on port 0 carries an inherent TOCTOU race
     * where another concurrent test or container process may claim the port before the engine binds it.
     * Full resolution at the SPI layer requires an ephemeral port binding contract (port 0 binding with
     * an {@code engine.localPort()} accessor), deferred to a future SPI revision.
     */
    private static int nextFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to allocate free TCP port", ex);
        }
    }
}
