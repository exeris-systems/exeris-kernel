/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.websocket;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportStream;
import eu.exeris.kernel.spi.websocket.WebSocketConfig;
import eu.exeris.kernel.spi.websocket.WebSocketHandler;
import eu.exeris.kernel.spi.websocket.WebSocketHandshakeHandler;
import eu.exeris.kernel.spi.websocket.WebSocketServerEngine;
import eu.exeris.kernel.spi.websocket.WebSocketSession;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An embeddable duplex endpoint: {@code createServerEngine} then {@code setHandler} then
 * {@code start()}, with no kernel boot in between (ADR-084 §2).
 *
 * <p>Lifecycle deliberately mirrors {@code CommunityHttpServerEngine} — same running/closed pair,
 * same refusal to set a handler after start, same port resolution for {@code port == 0}. A consumer
 * that already drives the HTTP engine should not have to learn a second shape.
 */
final class CommunityWebSocketServerEngine implements WebSocketServerEngine {

    private final WebSocketConfig config;
    // Closed by this engine ONLY when this engine created it — see ownsAllocator. An ambient one is
    // owned by whoever bound it, and closing another component's allocator is the failure this
    // deliberately does not risk.
    private final MemoryAllocator allocator;
    /** True when no ambient allocator was bound, so this engine created one and must release it. */
    private final boolean ownsAllocator;
    private final TransportEngine transport;
    private final int listenPort;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    @SuppressWarnings("java:S3077") // safe publication; the referent owns its thread-safety
    private volatile WebSocketHandler handler;
    @SuppressWarnings("java:S3077") // safe publication; the referent owns its thread-safety
    private volatile WebSocketHandshakeHandler handshakeHandler;

    /* default */ CommunityWebSocketServerEngine(WebSocketConfig config) {
        WebSocketConfig nonNull = Objects.requireNonNull(config, "config must not be null");
        int resolvedPort = nonNull.port() == 0
                ? CommunityWebSocketTransportFactory.nextFreePort()
                : nonNull.port();
        this.config = nonNull;
        CommunityWebSocketTransportFactory.ResolvedAllocator resolved =
                CommunityWebSocketTransportFactory.resolveAllocator();
        this.ownsAllocator = resolved.ours();
        this.allocator = resolved.allocator();
        this.transport = CommunityWebSocketTransportFactory.buildTransport(nonNull, resolvedPort,
                this.allocator);
        this.listenPort = resolvedPort;
    }

    @Override
    public void setHandler(WebSocketHandler handler) {
        if (running.get()) {
            throw new IllegalStateException("Cannot set handler after start()");
        }
        this.handler = Objects.requireNonNull(handler, "handler must not be null");
    }

    @Override
    public void setHandshakeHandler(WebSocketHandshakeHandler handshakeHandler) {
        if (running.get()) {
            throw new IllegalStateException("Cannot set handshake handler after start()");
        }
        this.handshakeHandler = handshakeHandler;
    }

    @Override
    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("Server engine is closed");
        }
        if (handler == null) {
            throw new IllegalStateException("WebSocketHandler must be set before start()");
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        boolean startFailed = true;
        try {
            transport.setStreamHandler(this::serve);
            transport.start();
            CommunityWebSocketLifecycleEvent.emit("start", listenPort);
            startFailed = false;
        } finally {
            if (startFailed) {
                running.set(false);
            }
        }
    }

    private void serve(TransportStream stream) {
        CommunityWebSocketUpgrade.Outcome outcome =
                CommunityWebSocketUpgrade.negotiate(stream, config, handshakeHandler, allocator);
        if (!outcome.accepted()) {
            stream.close();
            return;
        }
        // Identity is per CONNECTION, which is what the one-server-instance-per-session model rests
        // on: a consumer keys its state by this id and must not see two connections share one.
        WebSocketSession session = new WebSocketSession(UUID.randomUUID(), outcome.subprotocol(),
                ambientIsolationKey());
        // Reverse order on exit: the exchange closes first so its close frame reaches the peer
        // before the carrier goes, which is the difference between a clean close and a reset.
        try (TransportStream carrier = stream;
             CommunityWebSocketExchange exchange =
                     new CommunityWebSocketExchange(carrier, session, allocator,
                             config.maxMessageBytes())) {
            handler.handle(exchange);
        }
    }

    private static Optional<String> ambientIsolationKey() {
        // Read once at connection time, not per message: a WebSocket connection outlives any single
        // request, and re-reading an ambient context that the handler's own thread may have rebound
        // would make the session's key change under a consumer keying state by it.
        if (!KernelProviders.STORAGE_CONTEXT.isBound()) {
            return Optional.empty();
        }
        return KernelProviders.STORAGE_CONTEXT.get().isolationKey();
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        transport.stop();
        CommunityWebSocketLifecycleEvent.emit("stop", listenPort);
    }

    @Override
    public int boundPort() {
        return listenPort;
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
        if (ownsAllocator) {
            allocator.close();
        }
    }
}
