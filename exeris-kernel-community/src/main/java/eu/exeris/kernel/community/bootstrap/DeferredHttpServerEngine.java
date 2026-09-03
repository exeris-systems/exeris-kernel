/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpProvider;
import eu.exeris.kernel.spi.http.HttpServerEngine;

import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("PMD.CloseResource")
final class DeferredHttpServerEngine implements HttpServerEngine {

    private final HttpProvider provider;
    private final HttpConfig config;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    @SuppressWarnings("java:S3077") // safe publication; the referent owns its thread-safety
    private volatile HttpServerEngine delegate;
    @SuppressWarnings("java:S3077") // safe publication; the referent owns its thread-safety
    private volatile HttpHandler handler;

    /* default */ DeferredHttpServerEngine(HttpProvider provider, HttpConfig config) {
        this.provider = provider;
        this.config = config;
    }

    @Override
    public synchronized void setHandler(HttpHandler handler) {
        if (isRunning()) {
            throw new IllegalStateException("Cannot set handler after start()");
        }
        this.handler = handler;
        if (delegate != null) {
            delegate.setHandler(handler);
        }
    }

    @Override
    public synchronized void start() {
        if (closed.get()) {
            throw new IllegalStateException("Server engine is closed");
        }
        if (delegate != null) {
            throw new IllegalStateException("Server engine is already started");
        }
        HttpServerEngine local = provider.createServerEngine(config);
        HttpHandler localHandler = handler;
        if (localHandler != null) {
            local.setHandler(localHandler);
        }
        delegate = local;
        local.start();
    }

    @Override
    public void stop() {
        HttpServerEngine local = delegate;
        if (local != null) {
            local.stop();
        }
    }

    @Override
    public boolean isRunning() {
        HttpServerEngine local = delegate;
        return local != null && local.isRunning() && !closed.get();
    }

    @Override
    public String engineName() {
        HttpServerEngine local = delegate;
        return local != null ? local.engineName() : provider.providerName() + "/bootstrap-server";
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        HttpServerEngine local = delegate;
        if (local != null) {
            local.close();
        }
    }
}
