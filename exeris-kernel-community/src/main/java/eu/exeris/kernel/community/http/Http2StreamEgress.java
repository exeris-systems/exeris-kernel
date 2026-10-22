/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.core.http.http2.Http2ErrorCode;
import eu.exeris.kernel.core.http.sse.StreamEgress;
import eu.exeris.kernel.spi.http.StreamEvent;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.transport.TransportStream;

/**
 * Community: the HTTP/2 side of a held-open event stream — it gives {@code HttpStreamEngine} one
 * {@link Http2OutboundStream} of an {@link Http2ConnectionWriter}, so the head, the events and the end
 * of the stream are HEADERS, DATA and END_STREAM frames on the connection, and a stream that ends
 * leaves the connection open. An abortive end is {@code RST_STREAM(CANCEL)}.
 *
 * <p><b>Allocation:</b> one instance per stream; its work is the writer's.
 * <p><b>Thread confinement:</b> the stream's own thread, one caller at a time; the writer orders it
 * against the connection's other threads.
 * <p><b>Ownership:</b> borrows the writer, the stream and the transport, none of which it closes;
 * the connection and the stream's task release them.
 */
final class Http2StreamEgress implements StreamEgress {

    private final Http2ConnectionWriter writer;
    private final Http2OutboundStream stream;
    private final TransportStream transport;
    private final MemoryAllocator eventAllocator;

    /**
     * Binds the egress to one response stream.
     *
     * @param writer         the connection's writer
     * @param stream         the response stream, registered with the writer
     * @param transport      the connection's transport stream, whose id keys the stream's JFR events
     * @param eventAllocator allocator for the buffers events are framed into
     */
    /* default */ Http2StreamEgress(Http2ConnectionWriter writer,
                                    Http2OutboundStream stream,
                                    TransportStream transport,
                                    MemoryAllocator eventAllocator) {
        this.writer = writer;
        this.stream = stream;
        this.transport = transport;
        this.eventAllocator = eventAllocator;
    }

    @Override
    public TransportStream transport() {
        return transport;
    }

    @Override
    public int protocolStreamId() {
        return stream.streamId;
    }

    @Override
    public boolean writeHead() {
        return writer.writeStreamHead(stream);
    }

    @Override
    public Outcome writeEvent(StreamEvent event, int encodedLength, long authDeadlineEpochMillis,
                              long eventsEmitted) {
        return switch (writer.sendEvent(stream, event, encodedLength, authDeadlineEpochMillis,
                eventsEmitted, eventAllocator)) {
            case WRITTEN -> Outcome.WRITTEN;
            case CLOSED -> Outcome.CLOSED;
            case DEADLINE -> Outcome.DEADLINE;
        };
    }

    @Override
    public void finish() {
        writer.endStream(stream);
    }

    @Override
    public void abort() {
        writer.resetLocal(stream, Http2ErrorCode.CANCEL);
    }
}
