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
 * @since 0.5.0
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

    @Override
    public TransportStream openStream() {
        NativeTcpStream stream = streamRef.get();
        if (!isOpen() || stream == null) {
            throw new IllegalStateException("Connection is closed or stream is not available");
        }
        return stream;
    }

    @Override
    public TransportStream openUnidirectionalStream() {
        throw new UnsupportedOperationException("Community TCP does not support unidirectional streams");
    }

    @Override
    public String remoteAddress() {
        return remoteAddress;
    }

    @Override
    public int remotePort() {
        return remotePort;
    }

    @Override
    public boolean isOpen() {
        return open.get();
    }

    @Override
    public Object attachment() {
        return attachment.get();
    }

    @Override
    public void setAttachment(Object newAttachment) {
        attachment.set(newAttachment);
    }

    @Override
    public boolean tick() {
        return false;
    }

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
