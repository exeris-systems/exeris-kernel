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
import eu.exeris.kernel.core.security.SecurityInterceptor;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpKernelProviders;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpRequestBodyDecoderRegistry;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
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
// TooManyMethods: one method per HTTP verb/route category — mirrors the CommunityHttpSubsystem route surface.
// AvoidCatchingGenericException: catch-all wraps user-provided handler invocations; must absorb
// any handler exception at the HTTP boundary.
@SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.TooManyMethods", "PMD.AvoidCatchingGenericException"})
final class CommunityHttpRequestDispatcher {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String HEALTH_PATH = "/health";
    private static final String HEALTH_LIVE_PATH = "/health/live";
    private static final String HEALTH_READY_PATH = "/health/ready";
    private static final String DB_PING_PATH = "/db/ping";
    private static final String DB_ROUNDTRIP_PATH = "/db/roundtrip";
    private static final String SECURE_PATH_PREFIX = "/secure";
    private static final String ADMIN_PATH_PREFIX = "/secure/admin";
    private static final String READ_SCOPE = "security:read";
    private static final String WRITE_SCOPE = "security:write";

    private final MemoryAllocator allocator;
    private final SecurityInterceptor securityInterceptor;
    private final PersistenceEngine persistenceEngine;
    private final HttpRequestBodyDecoderRegistry requestBodyDecoderRegistry;

    /* default */ CommunityHttpRequestDispatcher(MemoryAllocator allocator,
                                   SecurityInterceptor securityInterceptor,
                                   PersistenceEngine persistenceEngine,
                                   HttpRequestBodyDecoderRegistry requestBodyDecoderRegistry) {
        this.allocator = Objects.requireNonNull(allocator, "allocator must not be null");
        this.securityInterceptor = securityInterceptor;
        this.persistenceEngine = persistenceEngine;
        this.requestBodyDecoderRegistry = requestBodyDecoderRegistry;
    }

    /* default */ void dispatch(HttpRequest request, HttpExchange exchange, HttpHandler handler) {
        String path = request.path();
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

        if (requiresAdmission(path)) {
            boolean admitted = securityInterceptor != null
                    && interceptRequest(
                    request,
                    () -> handleAuthorizedRequest(path, method, request, exchange, handler));
            if (!admitted) {
                exchange.respond(HttpResponse.noBody(HttpStatus.UNAUTHORIZED, request.version()));
                return;
            }
        } else {
            handleWithinRequestSession(method, request, exchange, handler);
        }

        if (!isResponded(exchange)) {
            exchange.respond(HttpResponse.noBody(HttpStatus.INTERNAL_SERVER_ERROR, request.version()));
        }
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

    private void handleAuthorizedRequest(String path,
                                         HttpMethod method,
                                         HttpRequest request,
                                         HttpExchange exchange,
                                         HttpHandler handler) {
        if (!isAuthorized(path)) {
            exchange.respond(HttpResponse.noBody(HttpStatus.FORBIDDEN, request.version()));
            return;
        }
        handleWithinRequestSession(method, request, exchange, handler);
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

        // Re-establish request-scoped bindings on the transport reactor thread (which does not inherit
        // the kernel carrier scope): the per-request persistence session and — for write-over-HTTP —
        // the request-body decoder registry the generated handler resolves via
        // HttpKernelProviders.httpRequestBodyDecoderRegistry() (ADR-036 / W7 boot-path fix).
        ScopedValue.Carrier carrier =
                ScopedValue.where(CommunityHttpRequestProcessor.REQUEST_SESSION, box);
        if (requestBodyDecoderRegistry != null) {
            carrier = carrier.where(
                    HttpKernelProviders.HTTP_REQUEST_BODY_DECODER_REGISTRY, requestBodyDecoderRegistry);
        }
        carrier.run(() -> {
            try {
                handler.handle(exchange);
            } catch (RuntimeException _) {
                if (!isResponded(exchange)) {
                    exchange.respond(HttpResponse.noBody(HttpStatus.INTERNAL_SERVER_ERROR, request.version()));
                }
            } finally {
                box.release();
            }
        });
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

    private static boolean requiresAdmission(String path) {
        return path != null && path.startsWith(SECURE_PATH_PREFIX) && !isPublicPath(path);
    }

    private static boolean isPublicPath(String path) {
        if (path == null) {
            return false;
        }
        return HEALTH_PATH.equals(path)
                || HEALTH_LIVE_PATH.equals(path)
                || HEALTH_READY_PATH.equals(path)
                || DB_PING_PATH.equals(path)
                || path.startsWith(DB_ROUNDTRIP_PATH);
    }

    private static boolean isAuthorized(String path) {
        if (!KernelProviders.PRINCIPAL_CONTEXT.isBound()) {
            return false;
        }

        PrincipalContext principal = KernelProviders.PRINCIPAL_CONTEXT.get();
        if (requiresAdminScope(path)) {
            return principal.hasAnyScope(WRITE_SCOPE);
        }
        return principal.hasAnyScope(READ_SCOPE);
    }

    private static boolean requiresAdminScope(String path) {
        return path != null && path.startsWith(ADMIN_PATH_PREFIX);
    }

    private static boolean isReadOnlyMethod(HttpMethod method) {
        return method == HttpMethod.GET || method == HttpMethod.HEAD;
    }

    private static HttpResponse backpressureResponse(HttpVersion version, String retryAfterSeconds) {
        List<HttpHeader> headers = List.of(new HttpHeader("Retry-After", retryAfterSeconds));
        return HttpResponse.noBody(HttpStatus.SERVICE_UNAVAILABLE, version, headers);
    }

    /* default */ static boolean isResponded(HttpExchange exchange) {
        return (exchange instanceof CommunityHttpExchange communityExchange
                && communityExchange.isResponded())
                || (exchange instanceof InMemoryHttp2Exchange inMemoryHttp2Exchange
                && inMemoryHttp2Exchange.isResponded());
    }
}
