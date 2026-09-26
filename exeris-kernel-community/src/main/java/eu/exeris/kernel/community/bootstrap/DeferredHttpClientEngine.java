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

    @Override
    public HttpResponse send(HttpRequest request) {
        if (closed.get()) {
            throw new IllegalStateException("Client engine is closed");
        }
        HttpClientEngine local = delegate;
        if (local == null) {
            throw new IllegalStateException("Client engine is not running");
        }
        return local.send(request);
    }

    /**
     * Returns {@link HttpConfig#defaultAuthority()} of the configuration this engine builds its
     * delegate from.
     *
     * <p>The default peer is a property of that configuration, not of the delegate, so it is
     * answered from the configuration: the same value before {@link #start()}, while running and
     * after {@link #close()}, and the same whichever provider's engine {@code start()} builds. This
     * is the engine an application reaches through {@code HttpKernelProviders.httpClientEngine()},
     * and a caller that resolves the peer before enrichment (ADR-074) reads it here; the interface
     * default of {@code null} would hide the configured peer from that caller while the delegate
     * still dialled it inside {@link #send(HttpRequest)}.
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
