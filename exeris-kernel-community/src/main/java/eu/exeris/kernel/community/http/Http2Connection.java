/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.core.concurrent.StructuredScope;
import eu.exeris.kernel.core.transport.TransportScopes;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.transport.TransportStream;

/**
 * Community: everything one HTTP/2 connection owns while its frame loop runs — the transport, the
 * handler, the decode-side {@link Http2SessionContext}, the {@link Http2ConnectionWriter} that orders
 * every write, and the {@link StructuredScope} that holds the tasks forked for responses a flow-control
 * window could not carry in one write.
 *
 * <p><b>Allocation:</b> the scope is opened on the first fork, not on construction, because most
 * connections never need one.
 * <p><b>Thread confinement:</b> the frame loop's thread, except {@link #writer()}, which any thread
 * may use; the scope's owner is the thread that first forks.
 * <p><b>Ownership:</b> owns the session, the writer and the scope; {@link #close()} ends them. The
 * transport and the handler are borrowed.
 */
final class Http2Connection implements AutoCloseable {

    private final TransportStream transport;
    private final HttpHandler handler;
    private final Http2SessionContext session;
    private final Http2ConnectionWriter writer;
    private StructuredScope streamScope;

    /**
     * Binds the parts of one connection together.
     *
     * @param transport the connection's transport stream
     * @param handler   the handler requests are dispatched to
     * @param session   the decode-side session state
     * @param writer    the connection's writer
     */
    /* default */ Http2Connection(TransportStream transport,
                    HttpHandler handler,
                    Http2SessionContext session,
                    Http2ConnectionWriter writer) {
        this.transport = transport;
        this.handler = handler;
        this.session = session;
        this.writer = writer;
    }

    /* default */ TransportStream transport() {
        return transport;
    }

    /* default */ HttpHandler handler() {
        return handler;
    }

    /* default */ Http2SessionContext session() {
        return session;
    }

    /* default */ Http2ConnectionWriter writer() {
        return writer;
    }

    /**
     * Returns the scope that response tasks fork into, opening it on first use with the
     * {@link TransportScopes} slots bound on the calling thread, or without bindings when none is
     * bound. Called by the frame loop only, which is then the scope's owner.
     *
     * @return the connection's scope
     */
    /* default */ StructuredScope streamScope() {
        if (streamScope == null) {
            ScopedValue.Carrier carrier = null;
            carrier = bindIfBound(carrier, TransportScopes.STREAM_PRIORITY);
            carrier = bindIfBound(carrier, TransportScopes.STREAM_ID);
            carrier = bindIfBound(carrier, TransportScopes.ENGINE_NAME);
            carrier = bindIfBound(carrier, TransportScopes.DRAIN_COORDINATOR);
            carrier = bindIfBound(carrier, TransportScopes.STREAM_WORK);
            streamScope = carrier == null
                    ? StructuredScope.openWithoutBindings()
                    : StructuredScope.open(carrier);
        }
        return streamScope;
    }

    /**
     * Ends the connection. The order is load-bearing: the writer first, which marks every response
     * stream closed and wakes its sender, then the scope, which interrupts and joins the senders —
     * joining first would wait on a sender that only the writer can wake — then the session.
     */
    @Override
    public void close() {
        writer.closeConnection();
        try (session) {
            if (streamScope != null) {
                streamScope.close();
            }
        }
    }

    private static <T> ScopedValue.Carrier bindIfBound(ScopedValue.Carrier carrier, ScopedValue<T> slot) {
        if (!slot.isBound()) {
            return carrier;
        }
        return carrier == null ? ScopedValue.where(slot, slot.get()) : carrier.where(slot, slot.get());
    }
}
