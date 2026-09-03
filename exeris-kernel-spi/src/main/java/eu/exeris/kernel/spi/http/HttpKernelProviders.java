/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

import java.util.Optional;

/**
 * Central {@link ScopedValue} slots for all HTTP SPI providers resolved during bootstrap.
 *
 * <h2>Separation from {@code KernelProviders}</h2>
 * <p>Both {@code KernelProviders} and {@code HttpKernelProviders} live in
 * {@code exeris-kernel-spi}. They remain separate to keep the generic kernel-provider
 * registry protocol-blind and avoid coupling non-HTTP subsystems to HTTP-specific
 * provider slots. This preserves The Wall by keeping SPI contracts explicit and
 * dependency direction one-way (implementations depend on SPI, never inverse).
 *
 * <h2>Binding (bootstrap side)</h2>
 * <pre>{@code
 * HttpProvider provider  = java.util.ServiceLoader.load(HttpProvider.class)
 *         .stream()
 *         .map(java.util.ServiceLoader.Provider::get)
 *         .max(java.util.Comparator.comparingInt(HttpProvider::priority))
 *         .orElseThrow(() -> eu.exeris.kernel.spi.exceptions.http.HttpException
 *                 .providerBootstrapFailure("unknown", null));
 * HttpServerEngine server = provider.createServerEngine(HttpConfig.defaultServer());
 * server.setHandler(myRouter);
 *
 * ScopedValue
 *     .where(HttpKernelProviders.HTTP_PROVIDER,       provider)
 *     .where(HttpKernelProviders.HTTP_SERVER_ENGINE,  server)
 *     .run(() -> {
 *         server.start();
 *         keepAlive();
 *     });
 * }</pre>
 *
 * <h2>Reading (subsystem / handler side)</h2>
 * <pre>{@code
 * HttpServerEngine engine = HttpKernelProviders.httpServerEngine();
 * }</pre>
 *
 * @since 0.5.0
 */
public final class HttpKernelProviders {

    /**
     * The active {@link HttpProvider} factory (bound once during HTTP bootstrap).
     *
     * <p>Use this slot only in bootstrap code that needs to introspect or reconfigure
     * the provider. Application code should use {@link #HTTP_SERVER_ENGINE} or
     * {@link #HTTP_CLIENT_ENGINE} directly.
     */
    public static final ScopedValue<HttpProvider> HTTP_PROVIDER = ScopedValue.newInstance();

    /**
     * The kernel-wide {@link HttpServerEngine} (created from {@link #HTTP_PROVIDER}).
     *
     * <p>Bound during HTTP bootstrap and inherited by every virtual thread in the
     * kernel scope — zero constructor injection needed in handler code.
     *
     * <h2>Usage</h2>
     * <pre>{@code
     * boolean running = HttpKernelProviders.HTTP_SERVER_ENGINE.get().isRunning();
     * }</pre>
     */
    public static final ScopedValue<HttpServerEngine> HTTP_SERVER_ENGINE = ScopedValue.newInstance();

    /**
     * Optional bootstrap-time override for the server {@link HttpHandler}.
     *
     * <p>When bound, HTTP bootstrap may use this handler instead of the default
     * subsystem handler. Intended for deterministic integration-test fixtures.
     */
    public static final ScopedValue<HttpHandler> HTTP_SERVER_HANDLER = ScopedValue.newInstance();

    /**
     * The optional {@link HttpClientEngine} (created from {@link #HTTP_PROVIDER}).
     *
     * <p>Bound during HTTP bootstrap only when the configured {@link HttpMode} includes
     * client functionality ({@link HttpMode#CLIENT} or {@link HttpMode#DUAL}).
     * Check {@link ScopedValue#isBound()} before accessing, or use the
     * {@link #httpClientEngine()} convenience accessor.
     */
    public static final ScopedValue<HttpClientEngine> HTTP_CLIENT_ENGINE = ScopedValue.newInstance();

    /**
     * Optional client-side typed request body encoder registry
     * ({@link HttpRequestBodyEncoderRegistry}).
     *
     * <p>Bound during HTTP bootstrap when the configured provider exposes one
     * (see {@link HttpProvider#requestBodyEncoderRegistry()}). Consumers that do
     * not perform typed body binding (raw {@link HttpClientEngine#send(HttpRequest)}
     * callers) do not require this slot to be bound. Use
     * {@link #httpRequestBodyEncoderRegistry()} to read defensively.
     *
     * @since 0.8.0
     */
    public static final ScopedValue<HttpRequestBodyEncoderRegistry> HTTP_REQUEST_BODY_ENCODER_REGISTRY =
            ScopedValue.newInstance();

    /**
     * Optional client-side typed response body decoder registry
     * ({@link HttpResponseBodyDecoderRegistry}).
     *
     * <p>Bound during HTTP bootstrap when the configured provider exposes one
     * (see {@link HttpProvider#responseBodyDecoderRegistry()}). Consumers that do
     * not perform typed body binding (raw {@link HttpClientEngine#send(HttpRequest)}
     * callers) do not require this slot to be bound. Use
     * {@link #httpResponseBodyDecoderRegistry()} to read defensively.
     *
     * @since 0.8.0
     */
    public static final ScopedValue<HttpResponseBodyDecoderRegistry> HTTP_RESPONSE_BODY_DECODER_REGISTRY =
            ScopedValue.newInstance();

    /**
     * Optional server-side typed request body decoder registry
     * ({@link HttpRequestBodyDecoderRegistry}).
     *
     * <p>Bound during HTTP bootstrap when the configured provider exposes one
     * (see {@link HttpProvider#requestBodyDecoderRegistry()}). Generated request
     * handlers read this slot to decode typed request bodies (ADR-036); handlers
     * that never decode a body (read-only resources) do not require it to be bound.
     * Use {@link #httpRequestBodyDecoderRegistry()} to read defensively.
     *
     * @since 0.8.0
     */
    public static final ScopedValue<HttpRequestBodyDecoderRegistry> HTTP_REQUEST_BODY_DECODER_REGISTRY =
            ScopedValue.newInstance();

    /**
     * Optional per-route authorization policy ({@link HttpRoutePolicy}), supplied by the application.
     *
     * <p>Bound during HTTP bootstrap when the application declares one (ADR-061). The transport
     * admission path reads this slot to decide whether a request may reach its handler. When unbound,
     * no per-route requirement is applied — the kernel behaves as it did before 0.11, which is why an
     * application that declares nothing sees no change. Use {@link #httpRoutePolicy()} to read
     * defensively.
     *
     * @since 0.11.0
     */
    public static final ScopedValue<HttpRoutePolicy> HTTP_ROUTE_POLICY = ScopedValue.newInstance();

    private HttpKernelProviders() {
        // Static ScopedValue slots only — never instantiated.
    }

    /**
     * Returns the active {@link HttpProvider}.
     *
     * @return the bound provider
     * @throws java.util.NoSuchElementException if the slot is not bound (HTTP not bootstrapped)
     */
    public static HttpProvider httpProvider() {
        return HTTP_PROVIDER.get();
    }

    /**
     * Returns the active {@link HttpServerEngine}.
     *
     * @return the bound server engine
     * @throws java.util.NoSuchElementException if the slot is not bound
     */
    public static HttpServerEngine httpServerEngine() {
        return HTTP_SERVER_ENGINE.get();
    }

    /**
     * Returns an optional bootstrap-time server handler override.
     *
     * @return an {@link Optional} containing the override when bound, or empty otherwise
     */
    public static Optional<HttpHandler> httpServerHandler() {
        return HTTP_SERVER_HANDLER.isBound()
                ? Optional.of(HTTP_SERVER_HANDLER.get())
                : Optional.empty();
    }

    /**
     * Returns the optional {@link HttpClientEngine} if one was configured.
     *
     * @return an {@link Optional} containing the engine if bound, or empty otherwise
     */
    public static Optional<HttpClientEngine> httpClientEngine() {
        return HTTP_CLIENT_ENGINE.isBound()
                ? Optional.of(HTTP_CLIENT_ENGINE.get())
                : Optional.empty();
    }

    /**
     * Returns the optional client-side typed request body encoder registry,
     * if one was bound during HTTP bootstrap.
     *
     * @return an {@link Optional} containing the registry when bound, or empty otherwise
     * @since 0.8.0
     */
    public static Optional<HttpRequestBodyEncoderRegistry> httpRequestBodyEncoderRegistry() {
        return HTTP_REQUEST_BODY_ENCODER_REGISTRY.isBound()
                ? Optional.of(HTTP_REQUEST_BODY_ENCODER_REGISTRY.get())
                : Optional.empty();
    }

    /**
     * Returns the optional client-side typed response body decoder registry,
     * if one was bound during HTTP bootstrap.
     *
     * @return an {@link Optional} containing the registry when bound, or empty otherwise
     * @since 0.8.0
     */
    public static Optional<HttpResponseBodyDecoderRegistry> httpResponseBodyDecoderRegistry() {
        return HTTP_RESPONSE_BODY_DECODER_REGISTRY.isBound()
                ? Optional.of(HTTP_RESPONSE_BODY_DECODER_REGISTRY.get())
                : Optional.empty();
    }

    /**
     * Returns the optional server-side typed request body decoder registry,
     * if one was bound during HTTP bootstrap.
     *
     * @return an {@link Optional} containing the registry when bound, or empty otherwise
     * @since 0.8.0
     */
    public static Optional<HttpRequestBodyDecoderRegistry> httpRequestBodyDecoderRegistry() {
        return HTTP_REQUEST_BODY_DECODER_REGISTRY.isBound()
                ? Optional.of(HTTP_REQUEST_BODY_DECODER_REGISTRY.get())
                : Optional.empty();
    }

    /**
     * Returns the optional per-route authorization policy, if the application bound one.
     *
     * @return an {@link Optional} containing the policy when bound, or empty otherwise
     * @since 0.11.0
     */
    public static Optional<HttpRoutePolicy> httpRoutePolicy() {
        return HTTP_ROUTE_POLICY.isBound()
                ? Optional.of(HTTP_ROUTE_POLICY.get())
                : Optional.empty();
    }
}
