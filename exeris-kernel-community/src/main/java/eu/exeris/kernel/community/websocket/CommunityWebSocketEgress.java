/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.websocket;

import eu.exeris.kernel.core.websocket.WebSocketFrameWriter;
import eu.exeris.kernel.core.websocket.WebSocketOpcode;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.transport.TransportStream;
import eu.exeris.kernel.spi.websocket.WebSocketCloseCode;

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
 *
 * <h2>Why the buffer is off-heap and per-connection</h2>
 *
 * <p>{@code TransportStream.write} documents its source as an off-heap segment, and that is load
 * bearing rather than advisory: {@code NativeTcpStreamPlainSocketIo} passes the segment straight to
 * a POSIX {@code send()} downcall built without {@code Linker.Option.critical}, which <b>rejects a
 * heap segment</b> — and the rejection is swallowed by a {@code catch (Throwable)} that quietly
 * falls back to NIO. So a heap buffer here does not fail; it throws and is caught on <em>every
 * single frame</em>, taking the slow path off the seam this transport exists to provide. Measured,
 * not reasoned about: an earlier revision of this class used {@code MemorySegment.ofArray} and every
 * write logged {@code IllegalArgumentException: Heap segment not allowed} in that fallback.
 *
 * <p>One buffer for the life of the connection, grown when a frame does not fit, rather than one
 * allocation per frame: a duplex connection sends many small messages over a long life, and
 * per-frame allocation would put the allocator on the hot path to no purpose. Reuse is safe because
 * every write already holds the lock above.
 */
final class CommunityWebSocketEgress implements AutoCloseable {

    private static final int CLOSE_CODE_BYTES = 2;
    private static final int INITIAL_BUFFER_BYTES = 1024;

    private final TransportStream stream;
    private final MemoryAllocator allocator;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicBoolean closeFrameSent = new AtomicBoolean(false);

    private LoanedBuffer outbound;

    /* default */ CommunityWebSocketEgress(TransportStream stream, MemoryAllocator allocator) {
        this.stream = stream;
        this.allocator = allocator;
        this.outbound = allocator.allocateNetwork(INITIAL_BUFFER_BYTES);
    }

    /* default */ void writeText(String message) {
        lock.lock();
        try {
            emit(WebSocketFrameWriter.frameSize(utf8Length(message)),
                    buffer -> WebSocketFrameWriter.writeText(buffer.segment(), 0, message));
        } finally {
            lock.unlock();
        }
    }

    /* default */ void writeFrame(WebSocketOpcode opcode, byte[] payload) {
        lock.lock();
        try {
            emit(WebSocketFrameWriter.frameSize(payload.length),
                    buffer -> WebSocketFrameWriter.write(buffer.segment(), 0, opcode, payload));
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
            emit(WebSocketFrameWriter.frameSize(CLOSE_CODE_BYTES + utf8Length(reason)),
                    buffer -> WebSocketFrameWriter.writeClose(buffer.segment(), 0, code, reason));
            return true;
        } catch (RuntimeException _) {
            return false;
        } finally {
            lock.unlock();
        }
    }

    /** Fills the connection's buffer with one frame and writes it. Caller holds the lock. */
    private void emit(int frameBytes, FrameEncoder encoder) {
        ensureCapacity(frameBytes);
        long written = encoder.encodeInto(outbound);
        outbound.setSize(written);
        stream.write(outbound.segment(), Math.toIntExact(written));
    }

    @FunctionalInterface
    private interface FrameEncoder {
        long encodeInto(LoanedBuffer buffer);
    }

    // CloseResource suppressed, with the ownership stated rather than assumed: the buffer allocated
    // here REPLACES the field and is closed by close(), while the one it replaces is closed on the
    // line below. PMD sees a local that escapes the method and cannot follow either half. A
    // try-with-resources here would free the buffer the connection is about to write into.
    @SuppressWarnings("PMD.CloseResource")
    private void ensureCapacity(int required) {
        if (outbound.capacity() >= required) {
            return;
        }
        LoanedBuffer grown = allocator.allocateNetwork(
                Math.max(required, Math.toIntExact(outbound.capacity()) * 2));
        outbound.close();
        outbound = grown;
    }

    @Override
    public void close() {
        lock.lock();
        try {
            outbound.close();
        } finally {
            lock.unlock();
        }
    }

    private static int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
