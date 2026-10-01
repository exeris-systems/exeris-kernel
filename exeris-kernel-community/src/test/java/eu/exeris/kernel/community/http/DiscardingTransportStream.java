/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportStream;

import java.lang.foreign.MemorySegment;

/**
 * A held-open stream that accepts the SSE head and every event and never reads: for a test that
 * drives {@code CommunityHttpStreamDispatcher#dispatchStream} and asserts on what the stream handler
 * saw, not on what reached a socket.
 */
final class DiscardingTransportStream implements TransportStream {

    @Override
    public int read(MemorySegment target, int maxBytes) {
        return -1;
    }

    @Override
    public void write(MemorySegment source, int length) {
        // Discarded: the wire is not what is under test.
    }

    @Override
    public void queueWrite(LoanedBuffer buffer, int length) {
        buffer.close();
    }

    @Override
    public long streamId() {
        return 1L;
    }

    @Override
    public boolean isBidirectional() {
        return true;
    }

    @Override
    public boolean isClientInitiated() {
        return true;
    }

    @Override
    public TransportConnection connection() {
        return null;
    }

    @Override
    public boolean hasPendingData() {
        return false;
    }

    @Override
    public void close() {
        // no-op
    }
}
