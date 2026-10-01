/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpProvider;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An {@link HttpClientEngine} that exists at {@code initialize()} and is built at {@code start()} —
 * the client-side counterpart of {@link DeferredHttpServerEngine}, for the same reason: a real client
 * engine resolves {@code KernelProviders.MEMORY_ALLOCATOR} at construction, and that binding is not
 * yet visible while {@link CommunityHttpSubsystem} is still initialising.
 */
@SuppressWarnings("PMD.CloseResource")
final class DeferredHttpClientEngine implements HttpClientEngine {

    private final HttpProvider provider;
    private final HttpConfig config;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    @SuppressWarnings("java:S3077") // safe publication; the referent owns its thread-safety
    private volatile HttpClientEngine delegate;

    /* default */ DeferredHttpClientEngine(HttpProvider provider, HttpConfig config) {
        this.provider = provider;
        this.config = config;
    }

    @Override
    public synchronized void start() {
        if (closed.get()) {
            throw new IllegalStateException("Client engine is closed");
        }
        if (delegate != null) {
            throw new IllegalStateException("Client engine is already started");
        }
        HttpClientEngine local = provider.createClientEngine(config);
        delegate = local;
        local.start();
    }

    /**
     * Sends {@code request} through the engine {@link #start()} built, addressed to
     * {@link #defaultAuthority()} when it names no peer.
     *
     * <p>Addressing it here keeps the peer this engine reports and the peer an unaddressed request
     * reaches the same, whether or not the delegate reads {@link HttpConfig#defaultAuthority()}
     * itself. A request that names its peer, or any request when no default is configured, reaches
     * the delegate unchanged.
     *
     * @param request outbound request; must not be {@code null}
     * @return the delegate's response
     * @throws NullPointerException  if {@code request} is {@code null}, in every lifecycle state
     * @throws IllegalStateException if the engine has not been started or has been closed
     */
    @Override
    public HttpResponse send(HttpRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (closed.get()) {
            throw new IllegalStateException("Client engine is closed");
        }
        HttpClientEngine local = delegate;
        if (local == null) {
            throw new IllegalStateException("Client engine is not running");
        }
        HttpRequest addressed = request.authority() == null
                ? request.withAuthority(config.defaultAuthority())
                : request;
        return local.send(addressed);
    }

    /**
     * Returns {@link HttpConfig#defaultAuthority()} of the configuration this engine builds its
     * delegate from.
     *
     * <p>The default peer is a property of that configuration, not of the delegate, so it is
     * answered from the configuration: the same value before {@link #start()}, while running and
     * after {@link #close()}, and the same whichever provider's engine {@code start()} builds. This
     * is the engine an application reaches through {@code HttpKernelProviders.httpClientEngine()},
     * and a caller that resolves the peer before enrichment (ADR-074) reads it here.
     * {@link #send(HttpRequest)} addresses an unaddressed request to the same value, so the peer
     * reported is the peer reached.
     *
     * @return the configured default peer as {@code host:port}, or {@code null} when none is
     *         configured and an unaddressed request is refused
     */
    @Override
    public String defaultAuthority() {
        return config.defaultAuthority();
    }

    @Override
    public boolean isRunning() {
        HttpClientEngine local = delegate;
        return local != null && local.isRunning() && !closed.get();
    }

    @Override
    public String engineName() {
        HttpClientEngine local = delegate;
        return local != null ? local.engineName() : provider.providerName() + "/bootstrap-client";
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        HttpClientEngine local = delegate;
        if (local != null) {
            local.close();
        }
    }
}
