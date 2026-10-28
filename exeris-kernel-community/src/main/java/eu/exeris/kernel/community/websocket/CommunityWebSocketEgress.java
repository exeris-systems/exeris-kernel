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
 * Everything that goes out on one connection: the lock that orders the frames, and the credit that
 * bounds how many data bytes the transport may hold for a peer that is not reading.
 *
 * <p><b>The lock is never held across a wait on the peer.</b> Every frame goes to the transport
 * through {@code TransportStream#queueWrite}, which returns once the frame is in the stream's
 * outbound order and does not wait for the peer. The receive path answers a ping and a close through
 * this class, so a lock held while a data write waited on a peer that stopped reading would stall the
 * very frame loop that has to read that peer's next frame.
 *
 * <p><b>Frame order is the lock's order.</b> RFC 6455 forbids interleaving the frames of two messages
 * on one connection, and the transport queues in the order {@code queueWrite} is called under the
 * lock, so concurrent senders are ordered rather than rejected.
 *
 * <p><b>Data frames are admitted against credit; control frames never wait.</b> Credit is the number
 * of bytes handed to the transport and not yet released by it, against the stream's outbound bound
 * ({@code TransportConfig#maxQueuedWriteBytes}). A data frame is admitted while that count is below
 * the bound, so a single frame larger than the bound is still admitted when nothing is outstanding.
 * A data writer without credit publishes itself, re-reads the count and parks <em>without</em> the
 * egress lock; every release of a frame's bytes unparks it, and so does {@link #close()}. Data
 * writers take turns on a second lock held across that wait, which keeps the order of messages and
 * leaves at most one parked writer. Control frames (ping, pong, close) are small, are counted as
 * outstanding so the count stays true, and are enqueued without ever looking at it, so the frame loop
 * is never stalled by a parked data writer. A slow peer therefore blocks every data sender on the
 * connection, not just the one that filled the window, and nothing else.
 *
 * <h2>Why every frame has its own off-heap buffer</h2>
 *
 * <p>{@code queueWrite} takes ownership of the loan and releases it when the frame's bytes have left
 * the stream's queue, so a buffer cannot be reused for the next frame while the transport may still
 * hold it. The loan is off-heap because the transport hands its segment straight to a POSIX
 * {@code send()} downcall built without {@code Linker.Option.critical}, which rejects a heap segment.
 *
 * <p><b>Allocation:</b> one pooled network buffer per frame, sized to the frame; the encoding of a
 * message into it allocates only the message's UTF-8 length probe.
 * <p><b>Thread confinement:</b> none — any thread may call {@code writeText}, {@code writeFrame} or
 * {@code sendCloseOnce}; the locks above order them.
 * <p><b>Ownership:</b> each frame's buffer is owned by this class until {@code queueWrite} returns
 * and by the transport afterwards, which closes it once the bytes leave the stream's queue; a buffer
 * the transport refuses is closed here. This instance holds no buffer between calls.
 */
final class CommunityWebSocketEgress implements AutoCloseable {

    private static final int CLOSE_CODE_BYTES = 2;

    private final TransportStream stream;
    private final MemoryAllocator allocator;
    private final CommunityWebSocketEgressCredit credit;
    private final ReentrantLock lock = new ReentrantLock();
    private final ReentrantLock dataTurn = new ReentrantLock();
    private final AtomicBoolean closeFrameSent = new AtomicBoolean(false);

    /**
     * Creates the egress of one connection.
     *
     * @param stream             the connection's stream
     * @param allocator          where each frame's buffer comes from
     * @param maxQueuedWriteBytes the stream's outbound bound in bytes, which is the credit a data
     *                           writer is admitted against; {@code -1} or any non-positive value for
     *                           no bound, so a data write never waits
     */
    /* default */ CommunityWebSocketEgress(TransportStream stream, MemoryAllocator allocator,
                                           int maxQueuedWriteBytes) {
        this.stream = stream;
        this.allocator = allocator;
        this.credit = new CommunityWebSocketEgressCredit(stream.streamId(), maxQueuedWriteBytes);
    }

    /**
     * Writes one text message as a single frame, waiting for credit first.
     *
     * @return {@code true} once the frame is with the transport, {@code false} when the connection
     *         closed before it could be admitted
     * @throws IllegalStateException if the calling thread is interrupted while it waits (its
     *                               interrupt status is restored)
     */
    /* default */ boolean writeText(String message) {
        return sendData(WebSocketFrameWriter.frameSize(utf8Length(message)),
                buffer -> WebSocketFrameWriter.writeText(buffer.segment(), 0, message));
    }

    /**
     * Writes one frame. A control opcode never waits; any other opcode waits for credit as
     * {@link #writeText} does.
     *
     * @return {@code true} once the frame is with the transport, {@code false} when the connection
     *         closed before a data frame could be admitted
     * @throws IllegalStateException if the calling thread is interrupted while a data frame waits (its
     *                               interrupt status is restored)
     */
    /* default */ boolean writeFrame(WebSocketOpcode opcode, byte[] payload) {
        FrameEncoder encoder = buffer -> WebSocketFrameWriter.write(buffer.segment(), 0, opcode, payload);
        int frameBytes = WebSocketFrameWriter.frameSize(payload.length);
        if (opcode.isControl()) {
            emit(frameBytes, encoder);
            return true;
        }
        return sendData(frameBytes, encoder);
    }

    /**
     * Writes the close frame, at most once for the life of the connection. It never waits for credit.
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
        try {
            emit(WebSocketFrameWriter.frameSize(CLOSE_CODE_BYTES + utf8Length(reason)),
                    buffer -> WebSocketFrameWriter.writeClose(buffer.segment(), 0, code, reason));
            return true;
        } catch (RuntimeException _) {
            return false;
        }
    }

    private boolean sendData(int frameBytes, FrameEncoder encoder) {
        dataTurn.lock();
        try {
            if (!credit.await()) {
                return false;
            }
            emit(frameBytes, encoder);
            return true;
        } finally {
            dataTurn.unlock();
        }
    }

    /**
     * Encodes one frame into its own buffer and hands it to the transport under the egress lock,
     * which does not wait for the peer. The buffer is the transport's from a normal return and this
     * method's on any throw.
     */
    // CloseResource / UseTryWithResources: the buffer changes owner at queueWrite and is closed here
    // only when nothing took it.
    @SuppressWarnings({"PMD.CloseResource", "PMD.UseTryWithResources"})
    private void emit(int frameBytes, FrameEncoder encoder) {
        LoanedBuffer frame = allocator.allocateNetwork(frameBytes);
        boolean handedOver = false;
        try {
            long written = encoder.encodeInto(frame);
            frame.setSize(written);
            credit.account(frame, written);
            lock.lock();
            try {
                stream.queueWrite(frame, Math.toIntExact(written));
            } finally {
                lock.unlock();
            }
            handedOver = true;
        } finally {
            if (!handedOver) {
                frame.close();
            }
        }
    }

    @FunctionalInterface
    private interface FrameEncoder {
        long encodeInto(LoanedBuffer buffer);
    }

    /** Ends the connection's egress: a data writer that is waiting for credit returns. Idempotent. */
    @Override
    public void close() {
        credit.close();
    }

    private static int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
