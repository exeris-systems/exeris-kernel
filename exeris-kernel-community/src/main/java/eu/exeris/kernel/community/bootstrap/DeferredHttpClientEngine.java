/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpProvider;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;

import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("PMD.CloseResource")
final class DeferredHttpClientEngine implements HttpClientEngine {

    private final HttpProvider provider;
    private final HttpConfig config;
    private final AtomicBoolean closed = new AtomicBoolean(false);

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
