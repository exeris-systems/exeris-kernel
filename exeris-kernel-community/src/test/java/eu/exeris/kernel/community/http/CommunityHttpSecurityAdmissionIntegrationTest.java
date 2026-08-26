/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.community.security.CommunitySecurityProvider;
import eu.exeris.kernel.community.testkit.security.TestJwt;
import eu.exeris.kernel.core.http.routing.HttpRouter;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpKernelProviders;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpMode;
import eu.exeris.kernel.spi.http.HttpProvider;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpRoutePolicy;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpServerEngine;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.http.RouteRequirement;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same three admission outcomes the hardcoded {@code /secure} prefix used to produce — 401, 403,
 * 200 — now driven by a declared {@link HttpRoutePolicy} on paths that have nothing to do with that
 * prefix. That is the point of ADR-061: the routes are the application's, not the driver's.
 *
 * <p>The fourth case is the one the old code could not express at all. Under the prefix convention
 * {@code /api/internal} reached its handler with no identity bound, because it did not start with
 * {@code /secure}. It is now decided by the policy like any other route.
 */
@DisplayName("Community: HTTP route-policy admission integration (ADR-061)")
class CommunityHttpSecurityAdmissionIntegrationTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    @AfterAll
    @SuppressWarnings("unused")
    static void closeAllocator() {
        ALLOCATOR.close();
    }

    @Test
    @DisplayName("Protected path without Authorization returns 401 and does not invoke handler")
    void protectedPathWithoutAuthorizationReturnsUnauthorized() {
        AtomicBoolean handlerInvoked = new AtomicBoolean(false);

        withHttpSecurityScope(() -> {
            int port = nextFreePort();
            HttpProvider provider = new CommunityHttpProvider();

            try (HttpServerEngine server = provider.createServerEngine(serverConfig(port));
                 HttpClientEngine client = provider.createClientEngine(clientConfig(port))) {
                server.setHandler(exchange -> {
                    handlerInvoked.set(true);
                    exchange.respond(HttpResponse.noBody(HttpStatus.OK, exchange.request().version()));
                });

                server.start();
                client.start();

                HttpResponse response = client.send(HttpRequest.noBody(
                        HttpMethod.GET,
                        "/api/orders",
                        HttpVersion.HTTP_1_1,
                        List.of()));
                try {
                    assertThat(response.status().code()).isEqualTo(401);
                } finally {
                    if (response.body() != null) {
                        response.body().close();
                    }
                }
            }
        });

        assertThat(handlerInvoked.get()).isFalse();
    }

    @Test
    @DisplayName("Protected path with valid token but insufficient privileges returns 403 and does not invoke handler")
    void protectedPathWithInsufficientPrivilegesReturnsForbidden() {
        AtomicBoolean handlerInvoked = new AtomicBoolean(false);

        withHttpSecurityScope(() -> {
            int port = nextFreePort();
            HttpProvider provider = new CommunityHttpProvider();

            try (HttpServerEngine server = provider.createServerEngine(serverConfig(port));
                 HttpClientEngine client = provider.createClientEngine(clientConfig(port))) {
                server.setHandler(exchange -> {
                    handlerInvoked.set(true);
                    exchange.respond(HttpResponse.noBody(HttpStatus.OK, exchange.request().version()));
                });

                server.start();
                client.start();

                HttpResponse response = client.send(HttpRequest.noBody(
                        HttpMethod.GET,
                        "/api/admin",
                        HttpVersion.HTTP_1_1,
                        List.of(new HttpHeader("Authorization", "Bearer " + TestJwt.builder().claim("scope", "security:read").serialize()))));
                try {
                    assertThat(response.status().code()).isEqualTo(403);
                } finally {
                    if (response.body() != null) {
                        response.body().close();
                    }
                }
            }
        });

        assertThat(handlerInvoked.get()).isFalse();
    }

    @Test
    @DisplayName("Protected path with valid token and sufficient privileges returns 200 and invokes handler")
    void protectedPathWithSufficientPrivilegesReturnsOk() {
        AtomicBoolean handlerInvoked = new AtomicBoolean(false);
        AtomicBoolean principalBound = new AtomicBoolean(false);
        AtomicBoolean storageBound = new AtomicBoolean(false);

        withHttpSecurityScope(() -> {
            int port = nextFreePort();
            HttpProvider provider = new CommunityHttpProvider();

            try (HttpServerEngine server = provider.createServerEngine(serverConfig(port));
                 HttpClientEngine client = provider.createClientEngine(clientConfig(port))) {
                server.setHandler(exchange -> {
                    handlerInvoked.set(true);
                    principalBound.set(KernelProviders.PRINCIPAL_CONTEXT.isBound());
                    storageBound.set(KernelProviders.STORAGE_CONTEXT.isBound());
                    exchange.respond(HttpResponse.noBody(HttpStatus.OK, exchange.request().version()));
                });

                server.start();
                client.start();

                HttpResponse response = client.send(HttpRequest.noBody(
                        HttpMethod.GET,
                        "/api/orders",
                        HttpVersion.HTTP_1_1,
                    List.of(new HttpHeader("Authorization", "Bearer " + TestJwt.builder().claim("scope", "security:read").serialize()))));
                try {
                    assertThat(response.status().code()).isEqualTo(200);
                } finally {
                    if (response.body() != null) {
                        response.body().close();
                    }
                }
            }
        });

        assertThat(handlerInvoked.get()).isTrue();
        assertThat(principalBound.get()).isTrue();
        assertThat(storageBound.get()).isTrue();
    }

    /**
     * Every authenticated principal carries exactly {@code security:read} — {@code CommunityClaimsMapper}
     * assigns it and does not read the token's {@code scope} claim — so {@code security:write} is the
     * scope nothing grants, which is what makes the 403 case a genuine denial rather than a typo.
     */
    private static final HttpRoutePolicy ROUTE_POLICY = (method, path) -> switch (path) {
        case "/api/orders" -> RouteRequirement.requiringAnyScope(Set.of("security:read"));
        case "/api/admin" -> RouteRequirement.requiringAnyScope(Set.of("security:write"));
        case "/api/public" -> RouteRequirement.permitAll();
        default -> HttpRoutePolicy.unmatched();
    };

    @Test
    @DisplayName("A STREAMING route on a protected path is denied too — it used to bypass the gate entirely")
    void protectedStreamRouteWithoutAuthorizationReturnsUnauthorized() {
        AtomicBoolean streamHandlerInvoked = new AtomicBoolean(false);

        withHttpSecurityScope(() -> {
            int port = nextFreePort();
            HttpProvider provider = new CommunityHttpProvider();

            try (HttpServerEngine server = provider.createServerEngine(serverConfig(port));
                 HttpClientEngine client = provider.createClientEngine(clientConfig(port))) {
                // Same protected path as the respond-once case above, but reached through a stream
                // route. The dispatch branches apart well before authorization, which is exactly how
                // this went unnoticed: the sibling tests here all take the respond-once branch.
                server.setHandler(HttpRouter.builder()
                        .streamRoute(HttpMethod.GET, "/api/orders", exchange -> streamHandlerInvoked.set(true))
                        .build());

                server.start();
                client.start();

                HttpResponse response = client.send(HttpRequest.noBody(
                        HttpMethod.GET,
                        "/api/orders",
                        HttpVersion.HTTP_1_1,
                        List.of()));
                try {
                    assertThat(response.status().code())
                            .as("a stream open on a scope-protected path must be denied like any request")
                            .isEqualTo(401);
                } finally {
                    if (response.body() != null) {
                        response.body().close();
                    }
                }
            }
        });

        assertThat(streamHandlerInvoked.get())
                .as("the SSE handler ran without an identity — ADR-061 was not applied to the stream branch")
                .isFalse();
    }

    @Test
    @DisplayName("A token carrying the admin scope reaches the admin route — the 403 above was about the scope")
    void adminScopeReachesAdminRoute() {
        AtomicBoolean handlerInvoked = new AtomicBoolean(false);

        withHttpSecurityScope(() -> {
            int port = nextFreePort();
            HttpProvider provider = new CommunityHttpProvider();

            try (HttpServerEngine server = provider.createServerEngine(serverConfig(port));
                 HttpClientEngine client = provider.createClientEngine(clientConfig(port))) {
                server.setHandler(exchange -> {
                    handlerInvoked.set(true);
                    exchange.respond(HttpResponse.noBody(HttpStatus.OK, exchange.request().version()));
                });

                server.start();
                client.start();

                HttpResponse response = client.send(HttpRequest.noBody(
                        HttpMethod.GET,
                        "/api/admin",
                        HttpVersion.HTTP_1_1,
                        List.of(new HttpHeader("Authorization",
                                "Bearer " + TestJwt.builder().claim("scope", "security:write").serialize()))));
                try {
                    assertThat(response.status().code())
                            .as("this case was unwritable before the claims mapper read the token: "
                                    + "security:write was a scope nothing could grant, so the 403 above "
                                    + "would have passed even against a route nobody could ever reach")
                            .isEqualTo(200);
                } finally {
                    if (response.body() != null) {
                        response.body().close();
                    }
                }
            }
        });

        assertThat(handlerInvoked.get()).isTrue();
    }

    @Test
    @DisplayName("Undeclared path is decided by the policy, not waved through — the ADR-061 defect")
    void undeclaredPathIsDeniedWithoutIdentity() {
        AtomicBoolean handlerInvoked = new AtomicBoolean(false);

        withHttpSecurityScope(() -> {
            int port = nextFreePort();
            HttpProvider provider = new CommunityHttpProvider();

            try (HttpServerEngine server = provider.createServerEngine(serverConfig(port));
                 HttpClientEngine client = provider.createClientEngine(clientConfig(port))) {
                server.setHandler(exchange -> {
                    handlerInvoked.set(true);
                    exchange.respond(HttpResponse.noBody(HttpStatus.OK, exchange.request().version()));
                });

                server.start();
                client.start();

                HttpResponse response = client.send(HttpRequest.noBody(
                        HttpMethod.GET,
                        "/api/internal",
                        HttpVersion.HTTP_1_1,
                        List.of()));
                try {
                    assertThat(response.status().code())
                            .as("under the prefix convention this path reached the handler with no "
                                    + "identity bound, purely because it did not start with /secure")
                            .isEqualTo(401);
                } finally {
                    if (response.body() != null) {
                        response.body().close();
                    }
                }
            }
        });

        assertThat(handlerInvoked.get())
                .as("and the handler must never have run")
                .isFalse();
    }

    @Test
    @DisplayName("A path the policy declares public still reaches the handler without a token")
    void declaredPublicPathReachesHandler() {
        AtomicBoolean handlerInvoked = new AtomicBoolean(false);

        withHttpSecurityScope(() -> {
            int port = nextFreePort();
            HttpProvider provider = new CommunityHttpProvider();

            try (HttpServerEngine server = provider.createServerEngine(serverConfig(port));
                 HttpClientEngine client = provider.createClientEngine(clientConfig(port))) {
                server.setHandler(exchange -> {
                    handlerInvoked.set(true);
                    exchange.respond(HttpResponse.noBody(HttpStatus.OK, exchange.request().version()));
                });

                server.start();
                client.start();

                HttpResponse response = client.send(HttpRequest.noBody(
                        HttpMethod.GET,
                        "/api/public",
                        HttpVersion.HTTP_1_1,
                        List.of()));
                try {
                    assertThat(response.status().code())
                            .as("without this control the denial cases above would also pass against "
                                    + "a dispatcher that rejects every request")
                            .isEqualTo(200);
                } finally {
                    if (response.body() != null) {
                        response.body().close();
                    }
                }
            }
        });

        assertThat(handlerInvoked.get()).isTrue();
    }

    @Test
    @DisplayName("No policy bound: every route reaches the handler — the pre-0.11 compatibility guarantee")
    void noPolicyBoundAdmitsEverything() {
        AtomicBoolean handlerInvoked = new AtomicBoolean(false);

        // Deliberately NOT withHttpSecurityScope: this case is about the absence of HTTP_ROUTE_POLICY.
        // The release notes lean on "an application that declares nothing behaves as it did before",
        // and until now nothing exercised that branch end-to-end.
        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR)
            .where(KernelProviders.SECURITY_PROVIDER,
                new CommunitySecurityProvider(TestJwt.keySet(), TestJwt.EXPECTED_ISSUER, TestJwt.EXPECTED_AUDIENCE))
            .run(() -> {
                int port = nextFreePort();
                HttpProvider provider = new CommunityHttpProvider();

                try (HttpServerEngine server = provider.createServerEngine(serverConfig(port));
                     HttpClientEngine client = provider.createClientEngine(clientConfig(port))) {
                    server.setHandler(exchange -> {
                        handlerInvoked.set(true);
                        exchange.respond(HttpResponse.noBody(HttpStatus.OK, exchange.request().version()));
                    });

                    server.start();
                    client.start();

                    HttpResponse response = client.send(HttpRequest.noBody(
                            HttpMethod.GET,
                            "/api/orders",
                            HttpVersion.HTTP_1_1,
                            List.of()));
                    try {
                        assertThat(response.status().code())
                                .as("the same path answers 401 once a policy declares a scope for it, so "
                                        + "this 200 is the unbound-slot branch and nothing else")
                                .isEqualTo(200);
                    } finally {
                        if (response.body() != null) {
                            response.body().close();
                        }
                    }
                }
            });

        assertThat(handlerInvoked.get()).isTrue();
    }

    private static void withHttpSecurityScope(Runnable testCase) {
        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR)
            .where(KernelProviders.SECURITY_PROVIDER,
                new CommunitySecurityProvider(TestJwt.keySet(), TestJwt.EXPECTED_ISSUER, TestJwt.EXPECTED_AUDIENCE))
            .where(HttpKernelProviders.HTTP_ROUTE_POLICY, ROUTE_POLICY)
                .run(testCase);
    }

    private static HttpConfig serverConfig(int port) {
        return new HttpConfig(
                HttpMode.SERVER,
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
    }

    private static HttpConfig clientConfig(int port) {
        return new HttpConfig(
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
                "127.0.0.1" + ":" + port,
                HttpConfig.DEFAULT_MAX_HEADER_BLOCK_SIZE
        );
    }

    private static int nextFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to allocate free TCP port", ex);
        }
    }
}
