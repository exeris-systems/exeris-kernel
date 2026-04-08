/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.http.routing;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpRouterTest {

    @Nested
    class ExactMatch {

        @Test
        void registeredPathAndMethodReturnsOk() {
            HttpRouter router = HttpRouter.builder()
                    .route(HttpMethod.GET, "/health", e -> e.respond(HttpStatus.OK))
                    .build();
            CapturingExchange exchange = CapturingExchange.get("/health");
            router.handle(exchange);
            assertEquals(HttpStatus.OK, exchange.status());
        }

        @Test
        void unregisteredExactPathReturns404() {
            HttpRouter router = HttpRouter.builder()
                    .route(HttpMethod.GET, "/health", e -> e.respond(HttpStatus.OK))
                    .build();
            CapturingExchange exchange = CapturingExchange.get("/missing");
            router.handle(exchange);
            assertEquals(HttpStatus.NOT_FOUND, exchange.status());
        }

        @Test
        void wrongMethodReturns404() {
            HttpRouter router = HttpRouter.builder()
                    .route(HttpMethod.GET, "/health", e -> e.respond(HttpStatus.OK))
                    .build();
            CapturingExchange exchange = CapturingExchange.of(HttpMethod.POST, "/health");
            router.handle(exchange);
            assertEquals(HttpStatus.NOT_FOUND, exchange.status());
        }

        @Test
        void queryStringIsStrippedBeforeMatching() {
            HttpRouter router = HttpRouter.builder()
                    .route(HttpMethod.GET, "/health", e -> e.respond(HttpStatus.OK))
                    .build();
            CapturingExchange exchange = CapturingExchange.get("/health?ready=true");
            router.handle(exchange);
            assertEquals(HttpStatus.OK, exchange.status());
        }
    }

    @Nested
    class PrefixMatch {

        @Test
        void prefixMatchesExactPath() {
            HttpRouter router = HttpRouter.builder()
                    .prefixRoute(HttpMethod.GET, "/api", e -> e.respond(HttpStatus.OK))
                    .build();
            CapturingExchange exchange = CapturingExchange.get("/api");
            router.handle(exchange);
            assertEquals(HttpStatus.OK, exchange.status());
        }

        @Test
        void prefixMatchesChildPath() {
            HttpRouter router = HttpRouter.builder()
                    .prefixRoute(HttpMethod.GET, "/api", e -> e.respond(HttpStatus.OK))
                    .build();
            CapturingExchange exchange = CapturingExchange.get("/api/users");
            router.handle(exchange);
            assertEquals(HttpStatus.OK, exchange.status());
        }

        @Test
        void prefixDoesNotMatchWithoutBoundary() {
            HttpRouter router = HttpRouter.builder()
                    .prefixRoute(HttpMethod.GET, "/api", e -> e.respond(HttpStatus.OK))
                    .build();
            CapturingExchange exchange = CapturingExchange.get("/apiv2");
            router.handle(exchange);
            assertEquals(HttpStatus.NOT_FOUND, exchange.status());
        }

        @Test
        void prefixWithTrailingWildcardIsNormalized() {
            HttpRouter router = HttpRouter.builder()
                    .prefixRoute(HttpMethod.GET, "/api/*", e -> e.respond(HttpStatus.OK))
                    .build();
            CapturingExchange exchange = CapturingExchange.get("/api/users");
            router.handle(exchange);
            assertEquals(HttpStatus.OK, exchange.status());
        }

        @Test
        void exactRouteTakesPrecedenceOverPrefix() {
            HttpRouter router = HttpRouter.builder()
                    .route(HttpMethod.GET, "/api/special", e -> e.respond(HttpStatus.CREATED))
                    .prefixRoute(HttpMethod.GET, "/api", e -> e.respond(HttpStatus.OK))
                    .build();
            CapturingExchange exchange = CapturingExchange.get("/api/special");
            router.handle(exchange);
            assertEquals(HttpStatus.CREATED, exchange.status());
        }
    }

    @Nested
    class HeadFallback {

        @Test
        void headFallsBackToGetHandler() {
            HttpRouter router = HttpRouter.builder()
                    .route(HttpMethod.GET, "/resource", e -> e.respond(HttpStatus.OK))
                    .build();
            CapturingExchange exchange = CapturingExchange.of(HttpMethod.HEAD, "/resource");
            router.handle(exchange);
            assertEquals(HttpStatus.OK, exchange.status());
        }

        @Test
        void explicitHeadHandlerTakesPrecedenceOverGetFallback() {
            AtomicBoolean headCalled = new AtomicBoolean(false);
            HttpRouter router = HttpRouter.builder()
                    .route(HttpMethod.HEAD, "/resource", e -> {
                        headCalled.set(true);
                        e.respond(HttpStatus.NO_CONTENT);
                    })
                    .route(HttpMethod.GET, "/resource", e -> e.respond(HttpStatus.OK))
                    .build();
            CapturingExchange exchange = CapturingExchange.of(HttpMethod.HEAD, "/resource");
            router.handle(exchange);
            assertTrue(headCalled.get());
            assertEquals(HttpStatus.NO_CONTENT, exchange.status());
        }

        @Test
        void headOnUnregisteredPathReturns404() {
            HttpRouter router = HttpRouter.builder()
                    .route(HttpMethod.GET, "/other", e -> e.respond(HttpStatus.OK))
                    .build();
            CapturingExchange exchange = CapturingExchange.of(HttpMethod.HEAD, "/missing");
            router.handle(exchange);
            assertEquals(HttpStatus.NOT_FOUND, exchange.status());
        }
    }

    @Nested
    class CustomNotFound {

        @Test
        void customNotFoundHandlerIsInvokedForUnregisteredPaths() {
            HttpRouter router = HttpRouter.builder()
                    .route(HttpMethod.GET, "/health", e -> e.respond(HttpStatus.OK))
                    .notFound(e -> e.respond(HttpStatus.SERVICE_UNAVAILABLE))
                    .build();
            CapturingExchange exchange = CapturingExchange.get("/missing");
            router.handle(exchange);
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.status());
        }
    }

    @Nested
    class BuilderValidation {

        @Test
        void nullMethodThrowsNpe() {
            assertThrows(NullPointerException.class, () ->
                    HttpRouter.builder().route(null, "/path", e -> {})
            );
        }

        @Test
        void nullPathThrowsNpe() {
            assertThrows(NullPointerException.class, () ->
                    HttpRouter.builder().route(HttpMethod.GET, null, e -> {})
            );
        }

        @Test
        void nullHandlerThrowsNpe() {
            assertThrows(NullPointerException.class, () ->
                    HttpRouter.builder().route(HttpMethod.GET, "/path", null)
            );
        }
    }

    // Minimal exchange capture - only responds to HttpResponse (which is what all default methods call)
    private static final class CapturingExchange implements HttpExchange {

        private final HttpRequest request;
        private HttpStatus capturedStatus;

        private CapturingExchange(HttpRequest request) {
            this.request = request;
        }

        static CapturingExchange get(String path) {
            return of(HttpMethod.GET, path);
        }

        static CapturingExchange of(HttpMethod method, String path) {
            return new CapturingExchange(
                    HttpRequest.noBody(method, path, HttpVersion.HTTP_1_1, List.of()));
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public void respond(HttpResponse response) {
            this.capturedStatus = response.status();
        }

        HttpStatus status() {
            return capturedStatus;
        }
    }
}
