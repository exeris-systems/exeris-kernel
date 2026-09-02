/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.websocket;

import eu.exeris.kernel.core.websocket.WebSocketFrameWriter;
import eu.exeris.kernel.core.websocket.WebSocketOpcode;
import eu.exeris.kernel.spi.transport.TransportStream;
import eu.exeris.kernel.spi.websocket.WebSocketCloseCode;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Everything that goes out on one connection, and the single lock that orders it.
 *
 * <p><b>The lock is the contract, not an implementation detail.</b> RFC 6455 forbids interleaving
 * the frames of two messages on one connection, so concurrent senders are ordered rather than
 * rejected — and a slow peer therefore blocks <em>every</em> sender on that connection, not just the
 * one that filled the window. Writing parks the virtual thread; nothing is queued on the heap
 * (ADR-043 obligation 4).
 */
final class CommunityWebSocketEgress {

    private static final int CLOSE_CODE_BYTES = 2;

    private final TransportStream stream;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicBoolean closeFrameSent = new AtomicBoolean(false);

    /* default */ CommunityWebSocketEgress(TransportStream stream) {
        this.stream = stream;
    }

    /* default */ void writeText(String message) {
        lock.lock();
        try {
            byte[] frame = new byte[WebSocketFrameWriter.frameSize(utf8Length(message))];
            long written = WebSocketFrameWriter.writeText(MemorySegment.ofArray(frame), 0, message);
            stream.write(MemorySegment.ofArray(frame), Math.toIntExact(written));
        } finally {
            lock.unlock();
        }
    }

    /* default */ void writeFrame(WebSocketOpcode opcode, byte[] payload) {
        lock.lock();
        try {
            byte[] frame = new byte[WebSocketFrameWriter.frameSize(payload.length)];
            long written = WebSocketFrameWriter.write(MemorySegment.ofArray(frame), 0, opcode,
                    payload);
            stream.write(MemorySegment.ofArray(frame), Math.toIntExact(written));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Writes the close frame, at most once for the life of the connection.
     *
     * <p>RuntimeException is caught rather than a named type on purpose: this is teardown, and a
     * transport may fail a write to a gone peer in whatever way its driver expresses. A close frame
     * is a courtesy on a connection that is ending either way, so nothing here may turn teardown
     * into a throw.
     *
     * @return whether this call is the one that sent it
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    /* default */ boolean sendCloseOnce(WebSocketCloseCode code, String reason) {
        if (!code.sendable() || !closeFrameSent.compareAndSet(false, true)) {
            return false;
        }
        lock.lock();
        try {
            byte[] frame = new byte[WebSocketFrameWriter.frameSize(
                    CLOSE_CODE_BYTES + utf8Length(reason))];
            long written = WebSocketFrameWriter.writeClose(MemorySegment.ofArray(frame), 0, code,
                    reason);
            stream.write(MemorySegment.ofArray(frame), Math.toIntExact(written));
            return true;
        } catch (RuntimeException unwritable) {
            return false;
        } finally {
            lock.unlock();
        }
    }

    private static int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
