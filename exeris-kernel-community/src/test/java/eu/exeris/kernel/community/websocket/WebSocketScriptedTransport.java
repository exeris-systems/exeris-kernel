/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.websocket;

import eu.exeris.kernel.spi.exceptions.transport.TransportStreamClosedException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportStream;

import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test transport for a WebSocket connection: inbound bytes are fed by the test and read by the frame
 * loop, outbound frames are decoded as the peer would see them, and a buffer passed to
 * {@code queueWrite} can be kept open until the test releases it, which models a peer that does not
 * read. Closing the stream releases what it still holds, as a transport does when a stream ends.
 *
 * <p>Waits are bounds on a condition, never a sleep.
 */
final class WebSocketScriptedTransport implements TransportStream {

    /** One outbound frame as the peer sees it; server frames are never masked. */
    record Frame(int opcode, byte[] payload) {
        String text() {
            return new String(payload, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    static final int TEXT = 0x1;
    static final int CLOSE = 0x8;
    static final int PING = 0x9;
    static final int PONG = 0xA;

    private static final byte[] EOF = new byte[0];
    private static final byte[] MASK = {0x11, 0x22, 0x33, 0x44};

    private final LinkedBlockingQueue<byte[]> inbound = new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<Frame> frames = new LinkedBlockingQueue<>();
    private final List<Frame> all = new CopyOnWriteArrayList<>();
    private final List<LoanedBuffer> held = new ArrayList<>();
    private final boolean holdQueuedBuffers;
    private final AtomicInteger plainWrites = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<RuntimeException> queueWriteFailure = new AtomicReference<>();
    private byte[] current;
    private int cursor;

    /**
     * @param holdQueuedBuffers when {@code true}, a buffer passed to {@code queueWrite} stays open
     *                          until {@link #releaseHeld()} or {@link #close()}
     */
    WebSocketScriptedTransport(boolean holdQueuedBuffers) {
        this.holdQueuedBuffers = holdQueuedBuffers;
    }

    // ------------------------------------------------------------------ inbound

    /** A masked client frame, as a peer sends it. */
    static byte[] clientFrame(int opcode, byte[] payload) {
        byte[] out = new byte[2 + MASK.length + payload.length];
        out[0] = (byte) (0x80 | opcode);
        out[1] = (byte) (0x80 | payload.length);
        System.arraycopy(MASK, 0, out, 2, MASK.length);
        for (int i = 0; i < payload.length; i++) {
            out[2 + MASK.length + i] = (byte) (payload[i] ^ MASK[i % MASK.length]);
        }
        return out;
    }

    void feed(byte[] bytes) {
        inbound.add(bytes);
    }

    void feedEof() {
        inbound.add(EOF);
    }

    @Override
    public int read(MemorySegment target, int maxBytes) {
        if (current == null || cursor >= current.length) {
            byte[] next;
            try {
                next = inbound.take();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return -1;
            }
            if (next == EOF) {
                return -1;
            }
            current = next;
            cursor = 0;
        }
        int toCopy = Math.min(maxBytes, current.length - cursor);
        MemorySegment.copy(MemorySegment.ofArray(current), cursor, target, 0, toCopy);
        cursor += toCopy;
        return toCopy;
    }

    // ------------------------------------------------------------------ outbound

    @Override
    public void write(MemorySegment source, int length) {
        plainWrites.incrementAndGet();
        throw new UnsupportedOperationException("a WebSocket frame is sent through queueWrite");
    }

    @Override
    public void queueWrite(LoanedBuffer buffer, int length) {
        RuntimeException failure = queueWriteFailure.get();
        if (failure != null) {
            throw failure;
        }
        if (closed.get()) {
            throw TransportStreamClosedException.locallyClosed();
        }
        byte[] bytes = new byte[length];
        MemorySegment.copy(buffer.segment(), 0, MemorySegment.ofArray(bytes), 0, length);
        Frame frame = new Frame(bytes[0] & 0x0F, java.util.Arrays.copyOfRange(bytes, 2, length));
        all.add(frame);
        frames.add(frame);
        if (holdQueuedBuffers) {
            synchronized (held) {
                held.add(buffer);
            }
        } else {
            buffer.close();
        }
    }

    /** Every later {@code queueWrite} throws {@code failure} and leaves the buffer with the caller. */
    void failQueueWrites(RuntimeException failure) {
        queueWriteFailure.set(failure);
    }

    /** Stops failing {@code queueWrite}. */
    void stopFailingQueueWrites() {
        queueWriteFailure.set(null);
    }

    /** How many plain {@code write} calls were made; a WebSocket frame never takes that path. */
    int plainWrites() {
        return plainWrites.get();
    }

    /** How many buffers the transport still keeps open. */
    int heldCount() {
        synchronized (held) {
            return held.size();
        }
    }

    /** Closes every buffer held, which is what the peer reading them would do. */
    void releaseHeld() {
        List<LoanedBuffer> toClose;
        synchronized (held) {
            toClose = new ArrayList<>(held);
            held.clear();
        }
        toClose.forEach(LoanedBuffer::close);
    }

    /** Every frame written so far, in wire order. */
    List<Frame> seen() {
        return List.copyOf(all);
    }

    /** Waits for the next frame, in wire order, that matches; others stay in {@link #seen()}. */
    Frame awaitFrame(java.util.function.Predicate<Frame> match, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new AssertionError("no matching frame within " + timeout + "; seen " + all.size() + " frames");
            }
            try {
                Frame frame = frames.poll(remaining, TimeUnit.NANOSECONDS);
                if (frame != null && match.test(frame)) {
                    return frame;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for a frame", interrupted);
            }
        }
    }

    // ------------------------------------------------------------------ rest of the contract

    @Override
    public long streamId() {
        return 9L;
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
        return new Peer();
    }

    @Override
    public boolean hasPendingData() {
        return false;
    }

    /** Ends the stream: later writes throw, and the buffers it still holds are released. */
    @Override
    public void close() {
        closed.set(true);
        releaseHeld();
        inbound.add(EOF);
    }

    private static final class Peer implements TransportConnection {
        @Override
        public TransportStream openStream() {
            throw new UnsupportedOperationException();
        }

        @Override
        public TransportStream openUnidirectionalStream() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String remoteAddress() {
            return "127.0.0.1";
        }

        @Override
        public int remotePort() {
            return 1;
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public Object attachment() {
            return null;
        }

        @Override
        public void setAttachment(Object attachment) {
            // No attachment is kept.
        }

        @Override
        public boolean tick() {
            return false;
        }

        @Override
        public void close() {
            // Nothing to close.
        }
    }
}
