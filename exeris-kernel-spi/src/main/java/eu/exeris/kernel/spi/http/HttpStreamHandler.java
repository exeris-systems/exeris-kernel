/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

import eu.exeris.kernel.spi.exceptions.http.StreamClosedException;

/**
 * SPI: Application-level handler for a server-push (SSE) stream — the streaming sibling of
 * {@link HttpHandler}.
 *
 * <p>A streaming-flagged route resolves to an {@code HttpStreamHandler}: it never receives a
 * respond-once {@link HttpExchange}, and a non-streaming route never receives an
 * {@link HttpStreamExchange} (ADR-043).
 *
 * <p><b>Thread confinement:</b> owner thread — each invocation runs on its own virtual thread, one
 * per stream, and the exchange it is given belongs to that thread alone
 * <p><b>Ownership:</b> the handler owns nothing it is handed — the engine owns the framing buffers
 * behind {@link HttpStreamExchange#emit(StreamEvent)} and reclaims the stream when the handler
 * returns
 *
 * @implSpec An implementation:
 *           <ul>
 *             <li>runs an imperative emit loop, calling
 *                 {@link HttpStreamExchange#emit(StreamEvent)} per event until the producer is
 *                 exhausted and then {@link HttpStreamExchange#close()}, or until the stream closes
 *                 underneath it;</li>
 *             <li>MUST NOT park a carrier thread — no {@code synchronized} block on an identity
 *                 object, no {@link ThreadLocal} access; {@code ScopedValue} is the mechanism for
 *                 stream-scoped state;</li>
 *             <li>is a plain Java object taking its dependencies through its constructor: no
 *                 Spring, Guice or Jakarta Inject annotation is honoured here.</li>
 *           </ul>
 * @apiNote <b>Disconnect is the unwind.</b> On client disconnect or fail-closed teardown
 *          {@code emit} throws the unchecked {@link StreamClosedException}, and letting it
 *          propagate is the way to end the handler — the engine then runs teardown. There is no
 *          {@code awaitDisconnect()} to poll, and therefore no parked virtual thread left behind by
 *          a handler that forgot to poll it.
 * @since 0.10
 */
@FunctionalInterface
public interface HttpStreamHandler {

    /**
     * Pushes events onto an already-open stream until the producer is exhausted or the peer is
     * gone; the response head has been written before this is called.
     *
     * @param exchange the open stream exchange to push to; never {@code null}
     * @implSpec Close the exchange when the producer is exhausted, and let
     *           {@link StreamClosedException} propagate when it is not — returning normally and
     *           returning on a throw are the two documented ends of a stream, and swallowing the
     *           throw only delays the same teardown.
     */
    void handle(HttpStreamExchange exchange);
}
