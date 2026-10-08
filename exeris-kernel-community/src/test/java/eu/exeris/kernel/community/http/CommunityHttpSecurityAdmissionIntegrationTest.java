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
 * The three admission outcomes — 401, 403, 200 — driven by a declared {@link HttpRoutePolicy} on
 * paths that share no prefix. That is the point of ADR-061: the routes are the application's, not the
 * driver's.
 *
 * <p>The fourth case is one a path-prefix convention cannot express: {@code /api/internal} does not
 * start with {@code /secure}, so such a convention would reach its handler with no identity bound.
 * The policy decides it like any other route.
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
    @DisplayName("A STREAMING route on a protected path is denied too — streaming does not bypass the gate")
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
    @DisplayName("No policy bound: every route reaches the handler")
    void noPolicyBoundAdmitsEverything() {
        AtomicBoolean handlerInvoked = new AtomicBoolean(false);

        // Deliberately NOT withHttpSecurityScope: this case is about the absence of HTTP_ROUTE_POLICY.
        // This pins end-to-end that an application declaring no route policy behaves as one that
        // declares nothing, which the release notes promise.
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

    /**
     * A fail-open policy: one declared route, and {@code permitAll()} for every route it does not
     * recognise. Under it, any request the policy fails to recognise as {@code /api/orders} while the
     * router still dispatches it there reaches the handler unauthenticated — so this is the policy that
     * makes a mismatch between what the policy is asked and what the router serves observable.
     */
    private static final HttpRoutePolicy FAIL_OPEN_POLICY = (method, path) ->
            method == HttpMethod.GET && "/api/orders".equals(path)
                    ? RouteRequirement.requiringAnyScope(Set.of("security:read"))
                    : RouteRequirement.permitAll();

    @Test
    @DisplayName("A query string does not move a request off its route's requirement")
    void queryStringDoesNotBypassPathMatchedRequirement() {
        AtomicBoolean handlerInvoked = new AtomicBoolean(false);

        int status = statusWithoutToken(FAIL_OPEN_POLICY, HttpMethod.GET, "/api/orders?page=1",
                handlerInvoked);

        assertThat(status)
                .as("the router dispatches /api/orders?page=1 to /api/orders, so the policy must be "
                        + "asked about /api/orders and not fall through to its permit-all answer")
                .isEqualTo(401);
        assertThat(handlerInvoked.get()).isFalse();
    }

    @Test
    @DisplayName("HEAD is decided by the GET requirement it is served under")
    void headIsDecidedByTheGetRequirement() {
        AtomicBoolean handlerInvoked = new AtomicBoolean(false);

        int status = statusWithoutToken(FAIL_OPEN_POLICY, HttpMethod.HEAD, "/api/orders",
                handlerInvoked);

        assertThat(status)
                .as("HEAD /api/orders runs the GET /api/orders handler, so it carries GET's requirement "
                        + "rather than the policy's permit-all answer for an undeclared method")
                .isEqualTo(401);
        assertThat(handlerInvoked.get()).isFalse();
    }

    @Test
    @DisplayName("A query string does not move a public route onto the unmatched denial")
    void queryStringKeepsPublicRoutePublic() {
        AtomicBoolean handlerInvoked = new AtomicBoolean(false);

        int status = statusWithoutToken(ROUTE_POLICY, HttpMethod.GET, "/api/public?page=1",
                handlerInvoked);

        assertThat(status)
                .as("the other direction of the same mismatch: under a fail-closed policy a query string "
                        + "must not turn a declared public route into an unmatched, denied one")
                .isEqualTo(200);
        assertThat(handlerInvoked.get()).isTrue();
    }

    private static int statusWithoutToken(HttpRoutePolicy policy, HttpMethod method, String target,
                                          AtomicBoolean handlerInvoked) {
        int[] status = new int[1];
        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR)
            .where(KernelProviders.SECURITY_PROVIDER,
                new CommunitySecurityProvider(TestJwt.keySet(), TestJwt.EXPECTED_ISSUER, TestJwt.EXPECTED_AUDIENCE))
            .where(HttpKernelProviders.HTTP_ROUTE_POLICY, policy)
            .run(() -> {
                int port = nextFreePort();
                HttpProvider provider = new CommunityHttpProvider();

                try (HttpServerEngine server = provider.createServerEngine(serverConfig(port));
                     HttpClientEngine client = provider.createClientEngine(clientConfig(port))) {
                    server.setHandler(HttpRouter.builder()
                            .route(HttpMethod.GET, "/api/orders", exchange -> {
                                handlerInvoked.set(true);
                                exchange.respond(HttpResponse.noBody(HttpStatus.OK, exchange.request().version()));
                            })
                            .route(HttpMethod.GET, "/api/public", exchange -> {
                                handlerInvoked.set(true);
                                exchange.respond(HttpResponse.noBody(HttpStatus.OK, exchange.request().version()));
                            })
                            .build());

                    server.start();
                    client.start();

                    HttpResponse response = client.send(HttpRequest.noBody(
                            method, target, HttpVersion.HTTP_1_1, List.of()));
                    try {
                        status[0] = response.status().code();
                    } finally {
                        if (response.body() != null) {
                            response.body().close();
                        }
                    }
                }
            });
        return status[0];
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
                HttpConfig.DEFAULT_MAX_HEADER_BLOCK_SIZE,
                HttpConfig.DEFAULT_MAX_HEADER_LIST_SIZE,
                HttpConfig.DEFAULT_MAX_STRING_LITERAL_SIZE
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
