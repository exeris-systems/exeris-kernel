/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.spi.websocket.WebSocketConfig;
import eu.exeris.kernel.spi.websocket.WebSocketHandler;
import eu.exeris.kernel.spi.websocket.WebSocketHandshakeHandler;
import eu.exeris.kernel.spi.websocket.WebSocketProvider;
import eu.exeris.kernel.spi.websocket.WebSocketServerEngine;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link WebSocketServerEngine} that exists at {@code initialize()} and is built at {@code start()}.
 *
 * <h2>Why the indirection is not optional</h2>
 * <p>Bootstrap runs every subsystem's {@code initialize()} before it composes {@code
 * providerBindings()}, so {@code KernelProviders.MEMORY_ALLOCATOR} is <em>not</em> bound while a
 * subsystem is initialising — declaring {@code dependsOn("memory")} orders the phases, it does not
 * make the binding visible earlier. A WebSocket engine resolves its allocator at construction, so
 * constructing one during {@code initialize()} either fails or quietly opens a second memory budget
 * beside the kernel's.
 *
 * <p>But the engine reference must exist by then anyway, because {@code providerBindings()} publishes
 * it into kernel scope. Holding the provider and config and building the delegate at {@code start()}
 * is what satisfies both, and it is the same answer {@link DeferredHttpServerEngine} already gives to
 * the identical ordering problem.
 */
final class DeferredWebSocketServerEngine implements WebSocketServerEngine {

    private final WebSocketProvider provider;
    private final WebSocketConfig config;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    @SuppressWarnings("java:S3077") // safe publication; the referent owns its thread-safety
    private volatile WebSocketServerEngine delegate;
    @SuppressWarnings("java:S3077") // safe publication; the referent owns its thread-safety
    private volatile WebSocketHandler handler;
    @SuppressWarnings("java:S3077") // safe publication; the referent owns its thread-safety
    private volatile WebSocketHandshakeHandler handshakeHandler;

    /* default */ DeferredWebSocketServerEngine(WebSocketProvider provider, WebSocketConfig config) {
        this.provider = provider;
        this.config = config;
    }

    @Override
    public synchronized void setHandler(WebSocketHandler handler) {
        this.handler = handler;
        if (delegate != null) {
            delegate.setHandler(handler);
        }
    }

    @Override
    public synchronized void setHandshakeHandler(WebSocketHandshakeHandler handshakeHandler) {
        this.handshakeHandler = handshakeHandler;
        if (delegate != null) {
            delegate.setHandshakeHandler(handshakeHandler);
        }
    }

    @Override
    public synchronized void start() {
        if (closed.get()) {
            throw new IllegalStateException("WebSocket server engine is closed");
        }
        if (delegate == null) {
            delegate = provider.createServerEngine(config);
            if (handler != null) {
                delegate.setHandler(handler);
            }
            if (handshakeHandler != null) {
                delegate.setHandshakeHandler(handshakeHandler);
            }
        }
        delegate.start();
    }

    @Override
    public synchronized void stop() {
        if (delegate != null) {
            delegate.stop();
        }
    }

    /**
     * The listening port, or {@code -1} before {@code start()} built the delegate.
     *
     * <p>{@code -1} rather than an exception: this engine is published into kernel scope from
     * {@code providerBindings()}, which runs before {@code start()}, so something reading the slot
     * early must get an answer rather than a failure.
     *
     * @return the bound port, or {@code -1} when not yet started
     */
    @Override
    public int boundPort() {
        WebSocketServerEngine current = delegate;
        return current == null ? -1 : current.boundPort();
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (delegate != null) {
            delegate.close();
        }
    }
}
