/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.transport.TransportStream;

/**
 * Community: everything one HTTP/2 connection owns while its frame loop runs — the transport, the
 * handler, the decode-side {@link Http2SessionContext}, the {@link Http2ConnectionWriter} that orders
 * every write, and the {@link Http2StreamScope} that holds the tasks forked for responses a flow-control
 * window could not carry in one write.
 *
 * <p><b>Allocation:</b> the scope is opened on the first fork, not on construction, because most
 * connections never need one.
 * <p><b>Thread confinement:</b> the frame loop's thread, except {@link #writer()}, which any thread
 * may use; the scope's owner is the thread that first forks.
 * <p><b>Ownership:</b> owns the writer and the scope; {@link #close()} ends them. The session, the
 * transport and the handler are borrowed: whoever created the session closes it, after this
 * connection, so the senders are joined before the session's buffers are released.
 */
final class Http2Connection implements AutoCloseable {

    private final TransportStream transport;
    private final HttpHandler handler;
    private final Http2SessionContext session;
    private final Http2ConnectionWriter writer;
    private Http2StreamScope streamScope;

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
     * Returns the scope that response tasks fork into, opening it on first use. Its tasks inherit
     * the transport scope slots bound on the calling thread at that moment. Called by the frame loop
     * only, which is then the scope's owner.
     *
     * @return the connection's scope
     */
    /* default */ Http2StreamScope streamScope() {
        if (streamScope == null) {
            streamScope = new Http2StreamScope();
        }
        return streamScope;
    }

    /**
     * Ends the connection. The order is load-bearing: the writer first, which marks every response
     * stream closed and wakes its sender, then the scope, which interrupts and joins the senders;
     * joining first would wait on a sender that only the writer can wake.
     */
    @Override
    public void close() {
        writer.closeConnection();
        if (streamScope != null) {
            streamScope.close();
        }
    }
}
