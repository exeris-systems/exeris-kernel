/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.websocket;

/**
 * SPI: discovers a duplex transport implementation.
 *
 * <p>Mirrors {@code HttpProvider}: resolved through {@code ServiceLoader}, highest {@link #priority()}
 * wins, and {@link #createServerEngine(WebSocketConfig)} hands back an engine that is not yet
 * started. Same shape for the same reason — a consumer can construct one from two public calls and
 * never touch {@code KernelBootstrap}.
 *
 * <h2>SPI Compliance</h2>
 * <p>Implementation-blind: no reference to sockets, frame buffers, TLS handles or event loops.
 *
 * @since 0.12.0
 */
public interface WebSocketProvider {

    /**
     * Creates a not-yet-started server engine.
     *
     * @param config endpoint configuration; must not be null
     * @return an initialised engine in the created state; never {@code null}
     */
    WebSocketServerEngine createServerEngine(WebSocketConfig config);

    /**
     * A stable identifier for this provider, used to select between several on the classpath.
     *
     * @return the provider id; never {@code null} or blank
     */
    String providerId();

    /**
     * A human-readable name for diagnostics.
     *
     * @return the provider name; never {@code null} or blank
     */
    String providerName();

    /**
     * Selection priority; the highest wins. Community providers use 0 and enterprise ones 100,
     * matching the open-core convention the other provider SPIs follow.
     *
     * @return the priority
     */
    default int priority() {
        return 0;
    }
}
