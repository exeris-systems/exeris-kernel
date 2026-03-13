/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.http;

import java.util.Optional;

/**
 * Central {@link ScopedValue} slots for all HTTP SPI providers resolved during bootstrap.
 *
 * <h2>Separation from {@code KernelProviders}</h2>
 * <p>{@code KernelProviders} lives in {@code exeris-kernel-spi}, which has zero Exeris
 * dependencies. {@code HttpKernelProviders} lives in {@code exeris-kernel-spi-http}, which
 * depends on {@code exeris-kernel-spi}. Merging these registries would create a circular
 * dependency: {@code spi → spi-http → spi}. Separation is The Wall in practice.
 *
 * <h2>Binding (bootstrap side)</h2>
 * <pre>{@code
 * HttpProvider provider  = HttpProvider.selectHighestPriority();
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
     * The optional {@link HttpClientEngine} (created from {@link #HTTP_PROVIDER}).
     *
     * <p>Bound during HTTP bootstrap only when the configured {@link HttpMode} includes
     * client functionality ({@link HttpMode#CLIENT} or {@link HttpMode#DUAL}).
     * Check {@link ScopedValue#isBound()} before accessing, or use the
     * {@link #httpClientEngine()} convenience accessor.
     */
    public static final ScopedValue<HttpClientEngine> HTTP_CLIENT_ENGINE = ScopedValue.newInstance();

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
     * Returns the optional {@link HttpClientEngine} if one was configured.
     *
     * @return an {@link Optional} containing the engine if bound, or empty otherwise
     */
    public static Optional<HttpClientEngine> httpClientEngine() {
        return HTTP_CLIENT_ENGINE.isBound()
                ? Optional.of(HTTP_CLIENT_ENGINE.get())
                : Optional.empty();
    }
}
