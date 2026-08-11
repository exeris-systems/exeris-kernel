/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.persistence.PersistenceSessionBox;
import eu.exeris.kernel.core.security.RouteAuthorizationEnforcer;
import eu.exeris.kernel.core.security.SecurityInterceptor;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpKernelProviders;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpRequestBodyDecoderRegistry;
import eu.exeris.kernel.spi.http.HttpRoutePolicy;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.http.RouteRequirement;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.TransactionIsolation;
import eu.exeris.kernel.spi.security.PrincipalContext;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

// CyclomaticComplexity: route dispatch table (auth, health, user handlers) — each branch is a
// terminal decision, not reducible without introducing opaque indirection.
// AvoidCatchingGenericException: catch-all wraps user-provided handler invocations; must absorb
// any handler exception at the HTTP boundary.
// TooManyMethods was suppressed here until ADR-061 removed the four path-convention helpers
// (requiresAdmission / isPublicPath / isAuthorized / requiresAdminScope); the class is now under the
// threshold on its own, and PMD flags the leftover suppression as unnecessary.
@SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.AvoidCatchingGenericException"})
final class CommunityHttpRequestDispatcher {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final MemoryAllocator allocator;
    private final SecurityInterceptor securityInterceptor;
    private final PersistenceEngine persistenceEngine;
    private final HttpRequestBodyDecoderRegistry requestBodyDecoderRegistry;
    private final HttpRoutePolicy routePolicy;

    /* default */ CommunityHttpRequestDispatcher(MemoryAllocator allocator,
                                   SecurityInterceptor securityInterceptor,
                                   PersistenceEngine persistenceEngine,
                                   HttpRequestBodyDecoderRegistry requestBodyDecoderRegistry,
                                   HttpRoutePolicy routePolicy) {
        this.allocator = Objects.requireNonNull(allocator, "allocator must not be null");
        this.securityInterceptor = securityInterceptor;
        this.persistenceEngine = persistenceEngine;
        this.requestBodyDecoderRegistry = requestBodyDecoderRegistry;
        this.routePolicy = routePolicy;
    }

    /* default */ void dispatch(HttpRequest request, HttpExchange exchange, HttpHandler handler) {
        HttpMethod method = request.method();

        if (persistenceEngine != null) {
            try {
                if (!persistenceEngine.canServiceRequest()) {
                    exchange.respond(backpressureResponse(request.version(), "1"));
                    return;
                }
            } catch (RuntimeException _) {
                exchange.respond(backpressureResponse(request.version(), "10"));
                return;
            }
        }

        if (!authorize(request, exchange,
                () -> handleWithinRequestSession(method, request, exchange, handler))) {
            return;
        }

        if (!isResponded(exchange)) {
            exchange.respond(HttpResponse.noBody(HttpStatus.INTERNAL_SERVER_ERROR, request.version()));
        }
    }

    /**
     * Applies the ADR-061 route requirement to a streaming open, running {@code openStream} only if the
     * request is admitted.
     *
     * <p>Separate entry point from {@link #dispatch} because a stream writes its own response head and
     * must not get this dispatcher's respond-once tail — but the authorization itself is the same code,
     * deliberately. Streaming routes previously reached the handler without passing any of it: the
     * stream branch returns before {@code dispatch} is ever called, so a bound {@link HttpRoutePolicy}
     * and the {@link SecurityInterceptor} were both simply skipped, and an SSE handler ran with no
     * {@code PRINCIPAL_CONTEXT} bound.
     *
     * <p>The binding holds for the stream's whole life, not just its open: the stream dispatcher runs
     * the handler's emit loop on the calling thread, so it executes inside
     * {@link SecurityInterceptor#intercept}'s scope.
     *
     * @param request    the parsed request that opened the stream
     * @param exchange   used only to write a denial; an admitted stream responds through its engine
     * @param openStream opens the stream and runs its emit loop
     */
    /* default */ void dispatchStream(HttpRequest request, HttpExchange exchange, Runnable openStream) {
        authorize(request, exchange, openStream);
    }

    /**
     * Resolves the route requirement and runs {@code admitted} if the request passes it.
     *
     * @return {@code false} if the request was denied and a response has already been written
     */
    private boolean authorize(HttpRequest request, HttpExchange exchange, Runnable admitted) {
        // A route the application never described about is decided by the policy, not by this
        // driver. With no policy bound the requirement is permit-all, which is exactly how the kernel
        // behaved before ADR-061 — declaring nothing changes nothing.
        RouteRequirement requirement = routePolicy == null
                ? RouteRequirement.permitAll()
                : routePolicy.requirementFor(request.method(), request.path());

        if (requirement != null && requirement.kind() == RouteRequirement.Kind.PERMIT_ALL) {
            admitted.run();
            return true;
        }
        // Identity is required — or the policy returned null, which is a defect the enforcer
        // turns into a denial rather than an admission.
        boolean intercepted = securityInterceptor != null
                && interceptRequest(request, () -> handleAuthorizedRequest(requirement, request, exchange, admitted));
        if (!intercepted) {
            exchange.respond(HttpResponse.noBody(HttpStatus.UNAUTHORIZED, request.version()));
            return false;
        }
        return true;
    }

    private boolean interceptRequest(HttpRequest request, Runnable admittedHandler) {
        if (securityInterceptor == null) {
            return false;
        }

        LoanedBuffer tokenBuffer = createBearerTokenBuffer(request.headers());
        if (tokenBuffer == null) {
            return false;
        }

        try (tokenBuffer) {
            return securityInterceptor.intercept(tokenBuffer, admittedHandler);
        }
    }

    private void handleAuthorizedRequest(RouteRequirement requirement,
                                         HttpRequest request,
                                         HttpExchange exchange,
                                         Runnable admitted) {
        PrincipalContext principal = KernelProviders.PRINCIPAL_CONTEXT.isBound()
                ? KernelProviders.PRINCIPAL_CONTEXT.get()
                : null;

        switch (RouteAuthorizationEnforcer.decide(requirement, principal)) {
            case ADMIT -> admitted.run();
            case FORBIDDEN ->
                    exchange.respond(HttpResponse.noBody(HttpStatus.FORBIDDEN, request.version()));
            case UNAUTHENTICATED ->
                    exchange.respond(HttpResponse.noBody(HttpStatus.UNAUTHORIZED, request.version()));
        }
    }

    private void handleWithinRequestSession(HttpMethod method,
                                            HttpRequest request,
                                            HttpExchange exchange,
                                            HttpHandler handler) {
        boolean readOnly = isReadOnlyMethod(method);
        PersistenceSessionBox box = new PersistenceSessionBox(
                persistenceEngine,
                TransactionIsolation.READ_COMMITTED,
                readOnly);

        Runnable invocation = () -> {
            try {
                handler.handle(exchange);
            } catch (RuntimeException _) {
                if (!isResponded(exchange)) {
                    exchange.respond(HttpResponse.noBody(HttpStatus.INTERNAL_SERVER_ERROR, request.version()));
                }
            } finally {
                box.release();
            }
        };

        // Re-establish request-scoped bindings on the transport reactor thread (which does not inherit
        // the kernel carrier scope): the per-request persistence session and — for write-over-HTTP —
        // the request-body decoder registry the generated handler resolves via
        // HttpKernelProviders.httpRequestBodyDecoderRegistry() (ADR-036 / W7 boot-path fix).
        if (requestBodyDecoderRegistry == null) {
            ScopedValue.where(CommunityHttpRequestProcessor.REQUEST_SESSION, box).run(invocation);
        } else {
            ScopedValue.where(CommunityHttpRequestProcessor.REQUEST_SESSION, box)
                    .where(HttpKernelProviders.HTTP_REQUEST_BODY_DECODER_REGISTRY, requestBodyDecoderRegistry)
                    .run(invocation);
        }
    }

    private LoanedBuffer createBearerTokenBuffer(List<HttpHeader> headers) {
        String token = extractBearerToken(headers);
        if (token == null) {
            return null;
        }

        byte[] tokenBytes = token.getBytes(StandardCharsets.UTF_8);
        if (tokenBytes.length == 0) {
            return null;
        }

        LoanedBuffer tokenBuffer = allocator.allocateNetwork(tokenBytes.length);
        MemorySegment.copy(
                MemorySegment.ofArray(tokenBytes), 0,
                tokenBuffer.segment(), 0,
                tokenBytes.length);
        tokenBuffer.setSize(tokenBytes.length);
        return tokenBuffer;
    }

    private static String extractBearerToken(List<HttpHeader> headers) {
        String found = "";
        boolean authorizationResolved = false;
        for (HttpHeader header : headers) {
            if (!authorizationResolved && header.nameEqualsIgnoreCase(AUTHORIZATION_HEADER)) {
                found = parseBearerTokenValue(header.value());
                authorizationResolved = true;
            }
        }
        return found.isEmpty() ? null : found;
    }

    private static String parseBearerTokenValue(String headerValue) {
        String value = headerValue.trim();
        if (!value.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return "";
        }
        String token = value.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? "" : token;
    }

    private static boolean isReadOnlyMethod(HttpMethod method) {
        return method == HttpMethod.GET || method == HttpMethod.HEAD;
    }

    private static HttpResponse backpressureResponse(HttpVersion version, String retryAfterSeconds) {
        List<HttpHeader> headers = List.of(new HttpHeader("Retry-After", retryAfterSeconds));
        return HttpResponse.noBody(HttpStatus.SERVICE_UNAVAILABLE, version, headers);
    }

    // Pattern-matches the concrete transport-side exchanges only. This is the exchange the dispatcher
    // constructs and hands to the handler — never a router decorator such as
    // eu.exeris.kernel.core.http.routing.PathParamHttpExchange, which the HttpRouter creates *inside*
    // handler.handle(...) wrapping one of these concretes. If a wrapper were ever the top-level exchange
    // here, this would report "not responded" after a successful response and trigger a spurious 500.
    /* default */ static boolean isResponded(HttpExchange exchange) {
        return (exchange instanceof CommunityHttpExchange communityExchange
                && communityExchange.isResponded())
                || (exchange instanceof InMemoryHttp2Exchange inMemoryHttp2Exchange
                && inMemoryHttp2Exchange.isResponded());
    }
}
