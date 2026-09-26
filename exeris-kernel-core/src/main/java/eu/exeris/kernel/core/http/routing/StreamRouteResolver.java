/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.routing;

import eu.exeris.kernel.spi.http.HttpMethod;

/**
 * Resolves a request to a streaming (SSE) route, or answers that it is not one.
 *
 * <p>A driver that serves stream routes consults the handler bound to
 * {@link eu.exeris.kernel.spi.http.HttpKernelProviders#HTTP_SERVER_HANDLER} through this interface,
 * once per request and before dispatch (ADR-043 obligation 7, amendment A1): a non-{@code null}
 * answer opens an {@link eu.exeris.kernel.spi.http.HttpStreamExchange} and runs the returned handler;
 * {@code null} sends the request to {@link eu.exeris.kernel.spi.http.HttpHandler#handle}. A bound
 * handler that does not implement this interface serves respond-once routes only: a stream route
 * behind it is never matched. {@link HttpRouter} implements it; a handler that wraps a router
 * implements it by delegating.
 *
 * <h2>Where it runs</h2>
 * <p>On the thread the driver reads the request on, before route authorization, and outside the
 * bindings the kernel establishes around a handler. An implementation decides from {@code method}
 * and {@code path} alone and reads no {@link ScopedValue}: a principal, tenant or session read here
 * is absent, or belongs to something else running on that thread. Per-request work belongs in the
 * handler it returns, which runs after authorization and inside those bindings. It is called
 * concurrently for every connection, so an implementation is thread-safe; it does not block and does
 * not throw, and a driver is not required to answer a throw from here with a response.
 *
 * <h2>Cost</h2>
 * <p>Every request pays for this call and most requests are not streams, so a miss is the common
 * case and an implementation keeps it free of allocation. A wrapper that delegates adds nothing to a
 * miss. A hit may allocate; it is paid once per stream, not per event.
 *
 * <h2>Wrapping a router</h2>
 * <p>A wrapper delegates. When a binding of its own must reach the stream handler, it wraps the
 * handler it gets back, derives any per-request value inside that wrapper, and forwards the captured
 * parameters unchanged:
 * {@snippet lang="java" :
 * public StreamMatch resolveStream(HttpMethod method, String path) {
 *     StreamMatch match = router.resolveStream(method, path);   // path forwarded as received
 *     if (match == null) {
 *         return null;
 *     }
 *     HttpStreamHandler route = match.handler();
 *     return new StreamMatch(exchange -> ScopedValue.where(TENANT, tenantOf(exchange))
 *             .run(() -> route.handle(exchange)), match.params());
 * }
 * }
 * <p>The driver runs the returned handler inside the bindings it establishes for the stream, so the
 * nesting is the respond-once one: kernel outermost, then the wrapper, then the route. A wrapper that
 * rebinds a slot the kernel also binds shadows the kernel's value for that stream. The driver applies
 * {@code params} to the exchange it passes in, so a wrapper that drops them hands the route an empty
 * {@link eu.exeris.kernel.spi.http.HttpStreamExchange#pathParams()}. A wrapper with nothing to bind
 * returns its delegate's match unchanged.
 *
 * <h2>A binding around a stream lives as long as the stream</h2>
 * <p>A stream handler runs for the stream's whole life, minutes rather than milliseconds, so whatever
 * a wrapper binds around it is held until the client goes away. Bind immutable or stateless values,
 * such as a tenant or a storage context. Never bind anything pooled or lazily acquired, such as a
 * persistence session: one read inside a live feed would hold a pooled connection for as long as the
 * client stays connected, which is why the kernel binds no request session around a stream. A stream
 * handler that needs the database takes a short-lived session per unit of work, and receives the
 * providers it needs through its constructor, because the stream's thread does not carry the
 * kernel's boot bindings.
 *
 * <p>Core API: the SPI compatibility gate and {@code docs/stability-matrix.md} cover
 * {@code exeris-kernel-spi} only.
 *
 * @since 0.12
 */
// Implemented next to HttpHandler by a named class and never written as a lambda:
// @FunctionalInterface would advertise the one use it is not for.
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface StreamRouteResolver {

    /**
     * Resolves {@code (method, path)} to a streaming route.
     *
     * @param method the request method; never {@code null}
     * @param path   the request path as received; it may carry a query string, which takes no part
     *               in matching; never {@code null}
     * @return the route's handler and captured parameters, or {@code null} when the request is not
     *         a stream
     */
    StreamMatch resolveStream(HttpMethod method, String path);
}
