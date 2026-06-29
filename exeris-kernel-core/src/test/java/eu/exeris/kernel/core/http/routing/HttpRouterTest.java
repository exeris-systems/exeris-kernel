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
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    class PathParameterMatch {

        @Test
        void templateRouteResolvesAndCapturesParam() {
            AtomicReference<Map<String, String>> captured = new AtomicReference<>();
            HttpRouter router = HttpRouter.builder()
                    .route(HttpMethod.GET, "/x/{id}", e -> {
                        captured.set(e.pathParams());
                        e.respond(HttpStatus.OK);
                    })
                    .build();
            CapturingExchange exchange = CapturingExchange.get("/x/42");
            router.handle(exchange);
            assertEquals(HttpStatus.OK, exchange.status());
            assertEquals("42", captured.get().get("id"));
        }

        @Test
        void collectionRouteAndByIdTemplateCoexist() {
            HttpRouter router = HttpRouter.builder()
                    .route(HttpMethod.GET, "/x", e -> e.respond(HttpStatus.OK))
                    .route(HttpMethod.GET, "/x/{id}", e -> e.respond(HttpStatus.ACCEPTED))
                    .build();

            CapturingExchange collection = CapturingExchange.get("/x");
            router.handle(collection);
            assertEquals(HttpStatus.OK, collection.status());

            CapturingExchange byId = CapturingExchange.get("/x/7");
            router.handle(byId);
            assertEquals(HttpStatus.ACCEPTED, byId.status());
        }

        @Test
        void exactRouteTakesPrecedenceOverTemplate() {
            HttpRouter router = HttpRouter.builder()
                    .route(HttpMethod.GET, "/x/latest", e -> e.respond(HttpStatus.CREATED))
                    .route(HttpMethod.GET, "/x/{id}", e -> e.respond(HttpStatus.OK))
                    .build();
            CapturingExchange exchange = CapturingExchange.get("/x/latest");
            router.handle(exchange);
            assertEquals(HttpStatus.CREATED, exchange.status());
        }

        @Test
        void templateDoesNotMatchDifferentSegmentCount() {
            HttpRouter router = HttpRouter.builder()
                    .route(HttpMethod.GET, "/x/{id}", e -> e.respond(HttpStatus.OK))
                    .build();
            // One segment too few — the collection path is not a by-id match.
            CapturingExchange exchange = CapturingExchange.get("/x");
            router.handle(exchange);
            assertEquals(HttpStatus.NOT_FOUND, exchange.status());
        }

        @Test
        void emptyParameterSegmentDoesNotMatch() {
            HttpRouter router = HttpRouter.builder()
                    .route(HttpMethod.GET, "/x/{id}", e -> e.respond(HttpStatus.OK))
                    .build();
            // Trailing slash leaves an empty {id} segment — must not capture an empty id.
            CapturingExchange exchange = CapturingExchange.get("/x/");
            router.handle(exchange);
            assertEquals(HttpStatus.NOT_FOUND, exchange.status());
        }

        @Test
        void multipleParametersAreCaptured() {
            AtomicReference<Map<String, String>> captured = new AtomicReference<>();
            HttpRouter router = HttpRouter.builder()
                    .route(HttpMethod.GET, "/a/{x}/b/{y}", e -> {
                        captured.set(e.pathParams());
                        e.respond(HttpStatus.OK);
                    })
                    .build();
            CapturingExchange exchange = CapturingExchange.get("/a/one/b/two");
            router.handle(exchange);
            assertEquals(HttpStatus.OK, exchange.status());
            assertEquals("one", captured.get().get("x"));
            assertEquals("two", captured.get().get("y"));
        }

        @Test
        void queryStringIsStrippedBeforeTemplateMatching() {
            AtomicReference<Map<String, String>> captured = new AtomicReference<>();
            HttpRouter router = HttpRouter.builder()
                    .route(HttpMethod.GET, "/x/{id}", e -> {
                        captured.set(e.pathParams());
                        e.respond(HttpStatus.OK);
                    })
                    .build();
            CapturingExchange exchange = CapturingExchange.get("/x/42?expand=true");
            router.handle(exchange);
            assertEquals(HttpStatus.OK, exchange.status());
            assertEquals("42", captured.get().get("id"));
        }

        @Test
        void byIdTemplateResolvesAcrossWriteMethods() {
            HttpRouter router = HttpRouter.builder()
                    .route(e -> e.respond(HttpStatus.OK), "/x/{id}",
                            HttpMethod.GET, HttpMethod.PUT, HttpMethod.DELETE)
                    .build();
            for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.PUT, HttpMethod.DELETE)) {
                CapturingExchange exchange = CapturingExchange.of(method, "/x/42");
                router.handle(exchange);
                assertEquals(HttpStatus.OK, exchange.status(), "by-id route must resolve for " + method);
            }
        }

        @Test
        void headFallsBackToTemplateGetHandler() {
            AtomicReference<Map<String, String>> captured = new AtomicReference<>();
            HttpRouter router = HttpRouter.builder()
                    .route(HttpMethod.GET, "/x/{id}", e -> {
                        captured.set(e.pathParams());
                        e.respond(HttpStatus.OK);
                    })
                    .build();
            CapturingExchange exchange = CapturingExchange.of(HttpMethod.HEAD, "/x/42");
            router.handle(exchange);
            assertEquals(HttpStatus.OK, exchange.status());
            assertEquals("42", captured.get().get("id"));
        }

        @Test
        void nonTemplateRouteExposesEmptyPathParams() {
            AtomicReference<Map<String, String>> captured = new AtomicReference<>();
            HttpRouter router = HttpRouter.builder()
                    .route(HttpMethod.GET, "/health", e -> {
                        captured.set(e.pathParams());
                        e.respond(HttpStatus.OK);
                    })
                    .build();
            CapturingExchange exchange = CapturingExchange.get("/health");
            router.handle(exchange);
            assertEquals(HttpStatus.OK, exchange.status());
            assertTrue(captured.get().isEmpty());
        }

        @Test
        void prefixRouteUnregressedWhenTemplatePresent() {
            HttpRouter router = HttpRouter.builder()
                    .route(HttpMethod.GET, "/x/{id}", e -> e.respond(HttpStatus.ACCEPTED))
                    .prefixRoute(HttpMethod.GET, "/static", e -> e.respond(HttpStatus.OK))
                    .build();
            CapturingExchange exchange = CapturingExchange.get("/static/css/app.css");
            router.handle(exchange);
            assertEquals(HttpStatus.OK, exchange.status());
        }

        @Test
        void streamRouteResolutionUnregressedWhenTemplatePresent() {
            HttpRouter router = HttpRouter.builder()
                    .route(HttpMethod.GET, "/x/{id}", e -> e.respond(HttpStatus.OK))
                    .streamRoute(HttpMethod.GET, "/events", exchange -> { })
                    .build();
            assertTrue(router.isStreamRoute(HttpMethod.GET, "/events"));
            assertNull(router.resolveStream(HttpMethod.GET, "/x/42"));
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

    @Nested
    class StreamingRegistration {

        @Test
        void streamRouteResolvesToStreamHandler() {
            AtomicBoolean ran = new AtomicBoolean(false);
            HttpRouter router = HttpRouter.builder()
                    .streamRoute(HttpMethod.GET, "/events", exchange -> ran.set(true))
                    .build();

            assertTrue(router.isStreamRoute(HttpMethod.GET, "/events"));
            assertTrue(router.resolveStream(HttpMethod.GET, "/events") != null);
            // Query is stripped before matching.
            assertTrue(router.resolveStream(HttpMethod.GET, "/events?since=5") != null);
        }

        @Test
        void streamRouteNeverDeliversRespondOnce() {
            // ADR-043 obligation 7: a streaming route is invisible to the respond-once handle() path.
            HttpRouter router = HttpRouter.builder()
                    .streamRoute(HttpMethod.GET, "/events", exchange -> { })
                    .build();
            CapturingExchange exchange = CapturingExchange.get("/events");
            router.handle(exchange);
            // No respond-once route matched → default 404; the stream handler was NOT invoked here.
            assertEquals(HttpStatus.NOT_FOUND, exchange.status());
        }

        @Test
        void respondOnceRouteIsNotAStreamRoute() {
            HttpRouter router = HttpRouter.builder()
                    .route(HttpMethod.GET, "/health", e -> e.respond(HttpStatus.OK))
                    .build();
            assertEquals(false, router.isStreamRoute(HttpMethod.GET, "/health"));
            assertEquals(null, router.resolveStream(HttpMethod.GET, "/health"));
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
