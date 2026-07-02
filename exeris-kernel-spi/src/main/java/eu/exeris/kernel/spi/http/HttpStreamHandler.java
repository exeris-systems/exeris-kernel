/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.http;

import eu.exeris.kernel.spi.exceptions.http.StreamClosedException;

/**
 * SPI: Application-level handler for a server-push (SSE) stream — the streaming sibling of
 * {@link HttpHandler}.
 *
 * <h2>Contract</h2>
 * <p>Implementations receive an open {@link HttpStreamExchange} and run an imperative emit loop, calling
 * {@link HttpStreamExchange#emit(StreamEvent)} for each event until the producer is exhausted (then
 * {@link HttpStreamExchange#close()}) or the stream closes underneath them. A streaming-flagged route
 * resolves to an {@code HttpStreamHandler}; it never receives a respond-once {@link HttpExchange}, and a
 * non-streaming route never receives an {@code HttpStreamExchange} (ADR-043).
 *
 * <h2>Disconnect is the unwind</h2>
 * <p>On client disconnect or fail-closed teardown, {@code emit} throws the unchecked
 * {@link StreamClosedException}; letting it propagate is the correct way to end the handler — the engine
 * then runs teardown. There is no {@code awaitDisconnect()} to poll and no parked-VT leak.
 *
 * <h2>Threading Model</h2>
 * <p>Each invocation runs on its own virtual thread (1 VT per stream). Handlers MUST NOT park carrier
 * threads (no {@code synchronized} on identity objects, no {@link ThreadLocal} access). {@code ScopedValue}
 * is the correct mechanism for propagating stream-scoped state.
 *
 * <h2>No Magic DI</h2>
 * <p>Handlers are plain Java objects. Dependencies are injected via constructors. No Spring, Guice, or
 * Jakarta Inject annotations are permitted.
 *
 * @since 0.10.0
 */
@FunctionalInterface
public interface HttpStreamHandler {

    /**
     * Handles the given server-push stream.
     *
     * @param exchange the open stream exchange to push to; never {@code null}
     */
    void handle(HttpStreamExchange exchange);
}
