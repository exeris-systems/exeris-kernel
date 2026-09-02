/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
/**
 * SPI: duplex (WebSocket) transport contracts — {@link eu.exeris.kernel.spi.websocket.WebSocketProvider},
 * its engine, the per-connection exchange and session, the handshake decision, and configuration.
 *
 * <p>Ruled by ADR-084. Its own package rather than an extension of {@code spi.http} for two reasons:
 * the handshake is the only part that is HTTP, and the stability matrix carries {@code spi.http} as
 * {@code mixed} with a per-surface breakdown, which a new family would muddy rather than join.
 *
 * <p><strong>{@code preview}.</strong> The gate for promotion is benchmark evidence — concurrent
 * connections, frame throughput, backpressure under a slow reader, teardown of a dead peer — not the
 * TCK going green. A contract test proves a shape is honoured, not that it survives, and for a
 * long-lived duplex protocol that is exactly where the two diverge.
 *
 * @since 0.12.0
 */
package eu.exeris.kernel.spi.websocket;
