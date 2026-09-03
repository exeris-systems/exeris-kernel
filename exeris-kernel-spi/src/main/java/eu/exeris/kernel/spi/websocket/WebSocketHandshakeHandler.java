/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.websocket;

import eu.exeris.kernel.spi.http.HttpRequest;

/**
 * SPI: the decision point before a connection exists.
 *
 * <p>Optional. When no handshake handler is set, the engine's origin allowlist decides alone — and
 * it <strong>refuses</strong> an origin it was not given, which is the half of ADR-084 §6 that
 * matters most. A callback defaulting to acceptance would mean a consumer who never writes one is
 * open, and the cost of forgetting would land on their users rather than on their build.
 *
 * <h2>Why this exists at all</h2>
 * <p>A WebSocket handshake is not subject to CORS, so a server that ignores {@code Origin} can be
 * opened by any page the user has visited, carrying their cookies. A browser also cannot set request
 * headers on a WebSocket, which leaves {@code Origin}, cookies, {@code Sec-WebSocket-Protocol} and
 * the query string as the only channels a consumer has for authenticating — all of them in the
 * request, none of them reachable without this callback.
 *
 * <p>The request arrives as an {@link HttpRequest} because the handshake <em>is</em> an HTTP GET and
 * that record already carries the headers, path and authority a decision needs. Its {@code body} is
 * meaningless here and will be {@code null}; a parallel carrier duplicating the rest to avoid one
 * null component would be the worse trade.
 *
 * <p>This is also where a returning client is recognised, which is what makes consumer-side session
 * resumption possible at all — see {@link WebSocketSession}.
 *
 * @since 0.12.0
 */
@FunctionalInterface
public interface WebSocketHandshakeHandler {

    /**
     * Decides whether to accept the connection.
     *
     * <p>Runs <em>after</em> the engine's origin allowlist and can only narrow it. An origin the
     * allowlist does not carry is refused before this callback is reached, so a consumer cannot
     * accidentally re-open what the configuration closed — and one that genuinely needs a wider set
     * widens the allowlist, which is a visible, reviewable act rather than a line inside a callback
     * nobody re-reads.
     *
     * @param request the handshake request; never {@code null}, {@code body()} is {@code null}
     * @return the decision; must not be {@code null}
     */
    WebSocketHandshake decide(HttpRequest request);
}
