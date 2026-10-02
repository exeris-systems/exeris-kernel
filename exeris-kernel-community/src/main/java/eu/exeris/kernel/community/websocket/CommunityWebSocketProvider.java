/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.websocket;

import eu.exeris.kernel.spi.websocket.WebSocketConfig;
import eu.exeris.kernel.spi.websocket.WebSocketProvider;
import eu.exeris.kernel.spi.websocket.WebSocketServerEngine;

/**
 * The open-tier WebSocket provider: RFC 6455 over the community TCP carrier.
 *
 * <h2>What this binding does not do yet, stated rather than discovered</h2>
 *
 * <p><b>{@code keepAliveIntervalMillis} is not honoured — no server-initiated pings are sent.</b>
 * The function a keepalive usually serves here IS served, by a different mechanism: the carrier
 * receives {@code idleTimeoutMillis} and {@code NativeTcpIdleReaper} reclaims a connection that has
 * moved no bytes for that long, so a dead peer is detected and its resources released. What is
 * missing is the other use — holding a NAT or proxy path open through a quiet period, which only an
 * outbound frame can do. A client that sends its own pings is answered: a PING is always replied to
 * with a PONG carrying the same payload, as RFC 6455 §5.5.2 requires.
 *
 * <p>The knob is left on the SPI rather than removed because an enterprise engine with its own timer
 * wheel can honour it; this provider's javadoc is where a consumer of THIS provider finds out that
 * it does not. Promotion of the surface from {@code preview} is gated on benchmark evidence
 * (ADR-084 §10), and this is one of the gaps that evidence has to close.
 */
public final class CommunityWebSocketProvider implements WebSocketProvider {

    /** Open-tier priority; enterprise providers outrank it (ADR-021). */
    private static final int COMMUNITY_PRIORITY = 0;

    /**
     * Instantiated reflectively by {@code ServiceLoader} through this module's
     * {@code META-INF/services} registration of {@link WebSocketProvider}; not meant to be
     * constructed directly.
     */
    public CommunityWebSocketProvider() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    @Override
    public WebSocketServerEngine createServerEngine(WebSocketConfig config) {
        return new CommunityWebSocketServerEngine(config);
    }

    @Override
    public String providerId() {
        return "community-websocket";
    }

    @Override
    public String providerName() {
        return "ExerisCommunity/WebSocket";
    }

    @Override
    public int priority() {
        return COMMUNITY_PRIORITY;
    }
}
