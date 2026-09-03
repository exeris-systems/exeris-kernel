/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.websocket.WebSocketConfig;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Builds the {@code websocket} subsystem's configuration, and decides whether it runs at all.
 *
 * <h2>Off unless asked for, and that is the load-bearing part</h2>
 * <p>{@code websocket.enabled} defaults to {@code false}. Adding a subsystem to the Community
 * provider list makes it available to every kernel boot, and a WebSocket listener is a network
 * surface: a deployment that upgrades to this version and gains an open socket it never configured
 * would be a security regression delivered as a feature. {@code http} can infer its own mode from a
 * configured port because it is the subsystem an application boots the kernel *for*; a duplex
 * endpoint is not, so inference is the wrong default here and the key is explicit.
 *
 * <p>Every other key mirrors a {@link WebSocketConfig} component and defaults to the constant that
 * record already publishes, so the documented default and the effective one cannot drift.
 */
final class CommunityWebSocketConfigResolver {

    /** RFC 6455 has no default port; a duplex endpoint that is enabled must be told where to listen. */
    private static final int DEFAULT_PORT = 8081;

    /**
     * Loopback, asked of the JDK rather than written down: an IPv6-only host has no {@code 127.0.0.1}
     * to bind, and the literal would fail there for a reason the operator could not read from config.
     */
    private static final String DEFAULT_BIND_HOST =
            InetAddress.getLoopbackAddress().getHostAddress();

    private CommunityWebSocketConfigResolver() {
    }

    /* default */ static boolean isEnabled(ConfigProvider configProvider) {
        return configProvider.getBoolean("websocket.enabled").orElse(false);
    }

    /* default */ static WebSocketConfig buildWebSocketConfig(ConfigProvider configProvider) {
        String bindHost = configProvider.getString("websocket.bindHost")
                .filter(host -> !host.isBlank())
                .orElse(DEFAULT_BIND_HOST);
        int port = configProvider.getInt("websocket.port").orElse(DEFAULT_PORT);
        int maxConnections = configProvider.getInt("websocket.maxConnections")
                .orElse(WebSocketConfig.DEFAULT_MAX_CONNECTIONS);
        long idleTimeoutMillis = configProvider.getLong("websocket.idleTimeoutMillis")
                .orElse(WebSocketConfig.DEFAULT_IDLE_TIMEOUT_MILLIS);
        long keepAliveIntervalMillis = configProvider.getLong("websocket.keepAliveIntervalMillis")
                .orElse(WebSocketConfig.DEFAULT_KEEP_ALIVE_INTERVAL_MILLIS);
        long maxMessageBytes = configProvider.getLong("websocket.maxMessageBytes")
                .orElse(WebSocketConfig.DEFAULT_MAX_MESSAGE_BYTES);

        return new WebSocketConfig(bindHost, port, maxConnections, idleTimeoutMillis,
                keepAliveIntervalMillis, maxMessageBytes, allowedOrigins(configProvider));
    }

    /**
     * Origins permitted to open a connection, comma-separated.
     *
     * <p>Absent means the empty set, which {@code WebSocketConfig} documents as accepting <em>no</em>
     * browser origin — the refusing default ADR-084 §6 asks for, reached by leaving the key alone
     * rather than by writing one.
     */
    private static Set<String> allowedOrigins(ConfigProvider configProvider) {
        return configProvider.getString("websocket.allowedOrigins")
                .map(raw -> Arrays.stream(raw.split(","))
                        .map(String::strip)
                        .filter(origin -> !origin.isEmpty())
                        .collect(LinkedHashSet<String>::new, Set::add, Set::addAll))
                .map(Set::copyOf)
                .orElseGet(Set::of);
    }
}
