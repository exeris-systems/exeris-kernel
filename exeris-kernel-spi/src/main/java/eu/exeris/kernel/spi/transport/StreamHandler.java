/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.transport;

/**
 * SPI: Callback for incoming {@link TransportStream} instances.
 *
 * <h2>Threading Model</h2>
 * <p>The transport engine invokes {@link #handle(TransportStream)} on a dedicated
 * virtual thread (the "1 VT per stream" model). The implementation may block
 * freely — the virtual thread unmounts from its carrier, leaving the carrier
 * event loop unblocked.
 *
 * <h2>Ownership</h2>
 * <p>The handler receives full ownership of the stream. It MUST close the stream
 * when processing is complete (typically via try-with-resources).
 *
 * <h2>Protocol Blindness</h2>
 * <p>The handler receives the same {@link TransportStream} interface regardless of
 * whether the underlying transport is TCP (Community) or QUIC (Enterprise).
 * Higher-level protocol decoding (HTTP/3 frames, QPACK) is the handler's concern,
 * not the SPI's.
 *
 * @see TransportEngine#setStreamHandler(StreamHandler)
 * @see TransportStream
 * @since 0.5.0
 */
@FunctionalInterface
public interface StreamHandler {

    /**
     * Handles an incoming transport stream.
     *
     * <p>Called by the transport engine on a virtual thread. The implementation
     * MUST close the stream when done.
     *
     * @param stream the incoming stream (caller owns lifecycle)
     */
    void handle(TransportStream stream);
}
