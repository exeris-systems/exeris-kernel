/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportStream;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Community TCP connection model: one connection maps to one bidirectional stream.
 *
 * <p>State ({@code open}, the attachment, the bound stream) is held in atomics so any thread may
 * query or close a connection concurrently with the carrier reactor serving its stream. This class
 * owns no buffer and no native resource of its own; {@link #close()} delegates the actual teardown
 * to the bound {@link NativeTcpStream}.
 *
 * @since 0.5
 */
final class NativeTcpConnection implements TransportConnection {

    private final long connectionId;
    private final String remoteAddress;
    private final int remotePort;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final AtomicReference<Object> attachment = new AtomicReference<>();
    private final AtomicReference<NativeTcpStream> streamRef = new AtomicReference<>();

    /* default */ NativeTcpConnection(long connectionId, String remoteAddress, int remotePort) {
        this.connectionId = connectionId;
        this.remoteAddress = remoteAddress;
        this.remotePort = remotePort;
    }

    /* default */ long connectionId() {
        return connectionId;
    }

    /* default */ void bindSingleStream(NativeTcpStream stream) {
        if (!streamRef.compareAndSet(null, stream)) {
            throw new IllegalStateException("TCP connection already has a bound stream");
        }
    }

    /**
     * Returns this connection's single stream.
     *
     * @return the bound stream
     * @throws IllegalStateException if the connection is closed, or no stream has been bound yet
     */
    @Override
    public TransportStream openStream() {
        NativeTcpStream stream = streamRef.get();
        if (!isOpen() || stream == null) {
            throw new IllegalStateException("Connection is closed or stream is not available");
        }
        return stream;
    }

    /**
     * Always fails: the Community TCP driver has no unidirectional stream type — every
     * connection carries exactly one bidirectional stream.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    public TransportStream openUnidirectionalStream() {
        throw new UnsupportedOperationException("Community TCP does not support unidirectional streams");
    }

    /**
     * Returns the remote peer's address, as resolved when the connection was accepted or dialled.
     *
     * @return the remote host address
     */
    @Override
    public String remoteAddress() {
        return remoteAddress;
    }

    /**
     * Returns the remote peer's port, as resolved when the connection was accepted or dialled.
     *
     * @return the remote port
     */
    @Override
    public int remotePort() {
        return remotePort;
    }

    /**
     * Whether this connection is still open.
     *
     * @return {@code true} until {@link #close()} (or carrier-driven teardown) has run
     */
    @Override
    public boolean isOpen() {
        return open.get();
    }

    /**
     * Returns the caller-set attachment.
     *
     * @return the current attachment, or {@code null} if none has been set
     */
    @Override
    public Object attachment() {
        return attachment.get();
    }

    /**
     * Replaces the attachment.
     *
     * @param newAttachment the value to attach; may be {@code null}
     */
    @Override
    public void setAttachment(Object newAttachment) {
        attachment.set(newAttachment);
    }

    /**
     * No-op: the Community TCP driver has no per-connection periodic work for a caller to drive.
     *
     * @return {@code false}, always
     */
    @Override
    public boolean tick() {
        return false;
    }

    /**
     * Closes this connection and its bound stream, if any. Idempotent.
     */
    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        try (NativeTcpStream _ = streamRef.get()) {
            // TWR closes stream if non-null; NativeTcpStream.close() is idempotent
        }
    }

    /* default */ void markClosedByCarrier() {
        open.set(false);
    }
}
