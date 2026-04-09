/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMode;
import eu.exeris.kernel.spi.http.HttpResponseBodyEncoderRegistry;
import eu.exeris.kernel.spi.http.HttpServerEngine;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.transport.TransportEngine;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

final class CommunityHttpServerEngine implements HttpServerEngine {

    private static final String ENGINE_NAME = "community-http-server";

    private final HttpConfig config;
    private final MemoryAllocator allocator;
    private final TransportEngine transport;
    private final int listenPort;
    private final HttpResponseBodyEncoderRegistry encoderRegistry;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile HttpHandler handler;

    /* default */ CommunityHttpServerEngine(HttpConfig config) {
        this(config, HttpResponseBodyEncoderRegistry.empty());
    }

    /* default */ CommunityHttpServerEngine(HttpConfig config,
                                            HttpResponseBodyEncoderRegistry encoderRegistry) {
        HttpConfig nonNullConfig = Objects.requireNonNull(config, "config must not be null");
        int resolvedPort = nonNullConfig.port() == 0
                ? CommunityHttpTransportFactory.nextFreePort()
                : nonNullConfig.port();
        this.config = nonNullConfig;
        this.allocator = CommunityHttpTransportFactory.resolveAllocator();
        this.transport = CommunityHttpTransportFactory.buildTransport(nonNullConfig, resolvedPort, this.allocator);
        this.listenPort = resolvedPort;
        this.encoderRegistry = Objects.requireNonNull(encoderRegistry, "encoderRegistry must not be null");
    }

    /* default */ CommunityHttpServerEngine(HttpConfig config,
                                            MemoryAllocator allocator,
                                            TransportEngine transport,
                                            int listenPort,
                                            HttpResponseBodyEncoderRegistry encoderRegistry) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.allocator = Objects.requireNonNull(allocator, "allocator must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.listenPort = listenPort;
        this.encoderRegistry = Objects.requireNonNull(encoderRegistry, "encoderRegistry must not be null");
    }

    @Override
    public void setHandler(HttpHandler handler) {
        if (running.get()) {
            throw new IllegalStateException("Cannot set handler after start()");
        }
        this.handler = Objects.requireNonNull(handler, "handler must not be null");
    }

    @Override
    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("Server engine is closed");
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        HttpHandler localHandler = handler;
        if (localHandler == null && (config.mode() == HttpMode.SERVER || config.mode() == HttpMode.DUAL)) {
            running.set(false);
            throw new IllegalStateException("HttpHandler must be set before start()");
        }

        boolean startFailed = true;
        try {
            CommunityHttpRequestProcessor processor =
                    new CommunityHttpRequestProcessor(allocator, encoderRegistry, config);
            transport.setStreamHandler(stream -> processor.process(stream, handler));
            transport.start();
            CommunityHttpLifecycleEvent.emit("start", listenPort);
            startFailed = false;
        } finally {
            if (startFailed) {
                running.set(false);
            }
        }
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        transport.stop();
        CommunityHttpLifecycleEvent.emit("stop", listenPort);
    }

    @Override
    public boolean isRunning() {
        return running.get() && !closed.get();
    }

    @Override
    public String engineName() {
        return ENGINE_NAME;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (running.get()) {
            stop();
        }
        transport.close();
    }

    /* default */ int listenPort() {
        return listenPort;
    }

}
