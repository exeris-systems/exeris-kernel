/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.community.security.CommunitySecurityProvider;
import eu.exeris.kernel.community.testkit.security.TestJwt;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpMode;
import eu.exeris.kernel.spi.http.HttpProvider;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpServerEngine;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Community: HTTP security admission integration")
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
                        "/secure",
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
                        "/secure/admin",
                        HttpVersion.HTTP_1_1,
                        List.of(new HttpHeader("Authorization", "Bearer " + TestJwt.builder().serialize()))));
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
                        "/secure",
                        HttpVersion.HTTP_1_1,
                    List.of(new HttpHeader("Authorization", "Bearer " + TestJwt.builder().serialize()))));
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

    private static void withHttpSecurityScope(Runnable testCase) {
        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR)
            .where(KernelProviders.SECURITY_PROVIDER,
                new CommunitySecurityProvider(TestJwt.keySet(), TestJwt.EXPECTED_ISSUER, TestJwt.EXPECTED_AUDIENCE))
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
                HttpVersion.HTTP_1_1
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
