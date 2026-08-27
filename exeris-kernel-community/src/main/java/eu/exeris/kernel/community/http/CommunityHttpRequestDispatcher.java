/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
import java.util.function.Supplier;

// CyclomaticComplexity: route dispatch table (auth, health, user handlers) — each branch is a
// terminal decision, not reducible without introducing opaque indirection.
// AvoidCatchingGenericException: catch-all wraps user-provided handler invocations; must absorb
// any handler exception at the HTTP boundary.
// TooManyMethods was suppressed here until ADR-061 removed the four path-convention helpers
// (requiresAdmission / isPublicPath / isAuthorized / requiresAdminScope); the class is now under the
// threshold on its own, and PMD flags the leftover suppression as unnecessary.
// TooManyMethods: fourteen, one over the threshold, and ADR-077's resolveRequirement() is what
// crossed it. Suppressed rather than refactored here, with the real finding written down instead of
// annotated away: the class does hold a cluster that does not belong to dispatching at all —
// createBearerTokenBuffer / extractBearerToken / parseBearerTokenValue are Authorization-header
// parsing, self-contained, and would leave the count at eleven. Extracting them is the right change
// and the wrong PR: it is security-adjacent code, and moving it inside a slice about connection
// lifetime puts the blame surface in the wrong place if it goes wrong. Owner: whoever next opens
// this class for its own reasons.
@SuppressWarnings({
    "PMD.CyclomaticComplexity", "PMD.AvoidCatchingGenericException", "PMD.TooManyMethods"})
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

        // Resolved here rather than inside authorize(), because ADR-077's execution facet rides the
        // same carrier the authorization decision reads and the handling branch needs it too. One
        // resolution, one path match: asking the policy twice would let two answers disagree about
        // what a route is.
        RouteRequirement requirement = resolveRequirement(request);
        boolean longRunning = requirement != null
                && requirement.execution() == RouteRequirement.Execution.LONG_RUNNING;

        if (!authorize(request, requirement, () -> exchange,
                () -> handleRequest(method, request, exchange, handler, longRunning))) {
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
     * @param denial     supplies an exchange used ONLY to write a denial. A supplier rather than an
     *                   exchange because the admitted case — the common one — never needs it, and
     *                   building one per stream open would spend an allocation on the path whose
     *                   whole point is that it hands the socket to the stream engine instead
     * @param openStream opens the stream and runs its emit loop
     */
    /* default */ void dispatchStream(HttpRequest request, Supplier<HttpExchange> denial,
                                      Runnable openStream) {
        // The streaming counterpart of what handleRequest establishes for a PROMPT route, and deliberately
        // not the same set. The reactor thread inherits no kernel carrier binding, and a stream
        // handler runs INLINE for the whole life of the stream (CommunityHttpStreamDispatcher calls
        // handler.handle(engine) and returns when the emit loop ends), so whatever is bound here is
        // bound for that entire duration. That is what makes the allocator and the decoder registry
        // right to bind — both stateless engine-scoped instances — and exactly why REQUEST_SESSION is
        // left out: PersistenceSessionBox lazily takes a pooled JDBC connection and holds it for the
        // scope's duration, so one read inside a live feed would pin a connection for as long as the
        // client stays connected. A streaming handler that needs the database wants a short-lived
        // session per emit — a design, not a binding copied across.
        ScopedValue.Carrier carrier = ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator);
        if (requestBodyDecoderRegistry != null) {
            carrier = carrier.where(
                    HttpKernelProviders.HTTP_REQUEST_BODY_DECODER_REGISTRY, requestBodyDecoderRegistry);
        }
        ScopedValue.Carrier streamScope = carrier;
        authorize(request, resolveRequirement(request), denial, () -> streamScope.run(openStream));
    }


    /**
     * Resolves the route requirement and runs {@code admitted} if the request passes it.
     *
     * @return {@code false} if the request was denied and a response has already been written
     */
    /**
     * Asks the bound policy what this route requires.
     *
     * <p>A route the application never described is decided by the policy, not by this driver. With
     * no policy bound the requirement is permit-all — an opt-in seam, so an application that
     * declares nothing carries no edge authorization at all.
     *
     * <p>That is NOT "unchanged behaviour": up to 0.10 this driver enforced a {@code /secure} prefix
     * with {@code security:read} / {@code security:write} compiled in, and ADR-061 obligation 4
     * deleted the convention deliberately. An application that relied on the prefix and declares no
     * policy serves those routes to anonymous callers. The release notes carry the migration step.
     */
    private RouteRequirement resolveRequirement(HttpRequest request) {
        return routePolicy == null
                ? RouteRequirement.permitAll()
                : routePolicy.requirementFor(request.method(), request.path());
    }

    private boolean authorize(HttpRequest request, RouteRequirement requirement,
                              Supplier<HttpExchange> exchange, Runnable admitted) {
        if (requirement != null && requirement.kind() == RouteRequirement.Kind.PERMIT_ALL) {
            // No requirement means no interceptor run, so the handler sees no PRINCIPAL_CONTEXT even
            // when the caller presented a valid token. A route that wants identity without demanding
            // a scope declares authenticated(), which is a different Kind — not permitAll().
            admitted.run();
            return true;
        }
        // Identity is required — or the policy returned null, which is a defect the enforcer
        // turns into a denial rather than an admission.
        boolean intercepted = securityInterceptor != null
                && interceptRequest(request, () -> handleAuthorizedRequest(requirement, request, exchange, admitted));
        if (!intercepted) {
            exchange.get().respond(HttpResponse.noBody(HttpStatus.UNAUTHORIZED, request.version()));
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
                                         Supplier<HttpExchange> exchange,
                                         Runnable admitted) {
        PrincipalContext principal = KernelProviders.PRINCIPAL_CONTEXT.isBound()
                ? KernelProviders.PRINCIPAL_CONTEXT.get()
                : null;

        // A switch EXPRESSION, so javac requires every RouteDecision to be answered. As a statement
        // it did not, and a decision added later would match no arm: the request would fall out of
        // this method having neither run the handler nor written a response, which the dispatcher's
        // respond-once tail then turns into a 500. A new way to deny would arrive as an unexplained
        // server error rather than as a compile failure — and as an admission, on the paths where
        // falling through means the handler already ran. Under artifact skew (RouteDecision lives in
        // the SPI, this dispatcher in Community) javac's synthetic MatchException makes it a loud
        // failure rather than either.
        HttpStatus denial = switch (RouteAuthorizationEnforcer.decide(requirement, principal)) {
            case ADMIT -> null;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
        };

        if (denial == null) {
            admitted.run();
        } else {
            exchange.get().respond(HttpResponse.noBody(denial, request.version()));
        }
    }

    /**
     * Runs the handler, binding a request-scoped persistence session unless the route declared
     * {@link RouteRequirement.Execution#LONG_RUNNING} (ADR-077).
     *
     * <p>{@code persistence.md}'s "One HTTP request is one connection" is the promise for a
     * {@code PROMPT} route and is unchanged. A {@code LONG_RUNNING} route gets no box, so each
     * persistence call acquires and releases through the engine — the ownership rule every path
     * outside a request session already runs, flow threads included. The trade is explicit: more
     * acquires, and therefore more {@code RlsConnectionInterceptor} round-trips, bought against not
     * pinning a pooled connection across a block whose own work draws from that same pool.
     */
    private void handleRequest(HttpMethod method,
                               HttpRequest request,
                               HttpExchange exchange,
                               HttpHandler handler,
                               boolean longRunning) {
        boolean readOnly = isReadOnlyMethod(method);
        PersistenceSessionBox box = longRunning
                ? null
                : new PersistenceSessionBox(
                        persistenceEngine,
                        TransactionIsolation.READ_COMMITTED,
                        readOnly);
        long startedAt = longRunning ? System.nanoTime() : 0L;

        Runnable invocation = () -> {
            try {
                handler.handle(exchange);
            } catch (RuntimeException _) {
                if (!isResponded(exchange)) {
                    exchange.respond(HttpResponse.noBody(HttpStatus.INTERNAL_SERVER_ERROR, request.version()));
                }
            } finally {
                completeRequest(request, box, startedAt);
            }
        };

        // Re-establish request-scoped bindings on the transport reactor thread (which does not inherit
        // the kernel carrier scope): the per-request persistence session and — for write-over-HTTP —
        // the request-body decoder registry the generated handler resolves via
        // HttpKernelProviders.httpRequestBodyDecoderRegistry() (ADR-036 / W7 boot-path fix).
        //
        // MEMORY_ALLOCATOR belongs in the same list and was missing from it. The kernel binds it as a
        // FOUNDATION carrier binding around the boot callback, and the reactor threads are started
        // with Thread.ofPlatform(), which does not inherit a ScopedValue — so kernel code that needs
        // it on a request captures it at construction instead (NativeTcpTransportProvider does
        // exactly that). Generated application code cannot: HttpRequestDecodingContext mandates an
        // allocator, and the generated parseBody resolves one from this slot per request. Unbound, it
        // raised NoSuchElementException inside the handler's try, which the handler reported as
        // 400 Bad Request — a server-side missing binding blamed on the caller's body.
        ScopedValue.Carrier carrier = ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator);
        if (box != null) {
            carrier = carrier.where(CommunityHttpRequestProcessor.REQUEST_SESSION, box);
        }
        if (requestBodyDecoderRegistry != null) {
            carrier = carrier.where(
                    HttpKernelProviders.HTTP_REQUEST_BODY_DECODER_REGISTRY, requestBodyDecoderRegistry);
        }
        carrier.run(invocation);
    }

    /**
     * Closes out one request: returns the pooled connection a {@code PROMPT} route held, or records
     * how long a {@code LONG_RUNNING} route actually ran.
     *
     * <p>The event is a single-phase commit with a hand-measured duration rather than
     * {@code begin()}/{@code commit()} around the handler. This runs on a virtual thread, and a JFR
     * event straddling a blocking operation on one is a known crash shape in this repository — and a
     * handler declared {@code LONG_RUNNING} is by definition one that blocks.
     */
    private static void completeRequest(HttpRequest request, PersistenceSessionBox box, long startedAt) {
        if (box == null) {
            RouteExecutionEvent.emitLongRunning(
                    request.method().name(), request.path(), System.nanoTime() - startedAt);
        } else {
            box.release();
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
