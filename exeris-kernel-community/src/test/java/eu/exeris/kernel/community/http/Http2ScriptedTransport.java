/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportStream;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Test transport for an HTTP/2 connection: inbound bytes are fed by the test and read by the frame
 * loop, outbound bytes are reassembled into frames the test can wait for, and a write can be made to
 * block inside the transport to model a slow write under the writer's lock.
 *
 * <p>Waits are bounds on a condition, never a sleep: {@link #awaitFrame} fails with an
 * {@link AssertionError} when the bound passes.
 */
final class Http2ScriptedTransport implements TransportStream {

    /** One outbound frame as the peer would see it. */
    record Frame(int type, int flags, int streamId, byte[] payload) {
        boolean isEndStream() {
            return (flags & 0x01) != 0;
        }
    }

    private static final byte[] EOF = new byte[0];
    private static final int FRAME_HEADER = 9;

    private final LinkedBlockingQueue<byte[]> inbound = new LinkedBlockingQueue<>();
    private byte[] current;
    private int cursor;
    private boolean eofSeen;

    private final Object reassemblyLock = new Object();
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private final LinkedBlockingQueue<Frame> frames = new LinkedBlockingQueue<>();
    private final List<Frame> all = new CopyOnWriteArrayList<>();

    private final boolean holdQueuedBuffers;
    private final List<LoanedBuffer> held = new ArrayList<>();

    private final AtomicBoolean inWrite = new AtomicBoolean();
    private volatile boolean overlapObserved;

    private volatile Predicate<Frame> blockMatching;
    private volatile CountDownLatch blockLatch = new CountDownLatch(0);
    private volatile CountDownLatch blockedEntered = new CountDownLatch(1);

    private volatile RuntimeException queueWriteFailure;
    private volatile boolean closed;

    Http2ScriptedTransport() {
        this(false);
    }

    /**
     * @param holdQueuedBuffers when {@code true}, a buffer passed to {@code queueWrite} stays open
     *                          until {@link #releaseHeld()}, which models a peer that does not read
     */
    Http2ScriptedTransport(boolean holdQueuedBuffers) {
        this.holdQueuedBuffers = holdQueuedBuffers;
    }

    // ------------------------------------------------------------------ inbound

    void feed(byte[] bytes) {
        inbound.add(bytes);
    }

    void feedEof() {
        inbound.add(EOF);
    }

    @Override
    public int read(MemorySegment target, int maxBytes) {
        if (closed) {
            throw new IllegalStateException("Stream is closed");
        }
        if (eofSeen) {
            return -1;
        }
        if (current == null || cursor >= current.length) {
            byte[] next;
            try {
                next = inbound.take();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return -1;
            }
            if (next == EOF) {
                eofSeen = true;
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
        accept(source, length);
    }

    @Override
    public void queueWrite(LoanedBuffer buffer, int length) {
        RuntimeException failure = queueWriteFailure;
        if (failure != null) {
            throw failure;
        }
        try {
            accept(buffer.segment(), length);
        } finally {
            if (holdQueuedBuffers) {
                synchronized (held) {
                    held.add(buffer);
                }
            } else {
                buffer.close();
            }
        }
    }

    private void accept(MemorySegment source, int length) {
        if (!inWrite.compareAndSet(false, true)) {
            overlapObserved = true;
        }
        try {
            byte[] bytes = new byte[length];
            MemorySegment.copy(source, 0, MemorySegment.ofArray(bytes), 0, length);
            boolean mustBlock = false;
            synchronized (reassemblyLock) {
                pending.writeBytes(bytes);
                byte[] buffered = pending.toByteArray();
                int offset = 0;
                while (buffered.length - offset >= FRAME_HEADER) {
                    int payloadLength = ((buffered[offset] & 0xFF) << 16)
                            | ((buffered[offset + 1] & 0xFF) << 8) | (buffered[offset + 2] & 0xFF);
                    if (buffered.length - offset < FRAME_HEADER + payloadLength) {
                        break;
                    }
                    byte[] payload = new byte[payloadLength];
                    System.arraycopy(buffered, offset + FRAME_HEADER, payload, 0, payloadLength);
                    Frame frame = new Frame(
                            buffered[offset + 3] & 0xFF,
                            buffered[offset + 4] & 0xFF,
                            ((buffered[offset + 5] & 0x7F) << 24) | ((buffered[offset + 6] & 0xFF) << 16)
                                    | ((buffered[offset + 7] & 0xFF) << 8) | (buffered[offset + 8] & 0xFF),
                            payload);
                    Predicate<Frame> blocker = blockMatching;
                    if (blocker != null && blocker.test(frame)) {
                        mustBlock = true;
                    }
                    all.add(frame);
                    frames.add(frame);
                    offset += FRAME_HEADER + payloadLength;
                }
                pending.reset();
                pending.write(buffered, offset, buffered.length - offset);
            }
            if (mustBlock) {
                blockedEntered.countDown();
                awaitUninterruptibly(blockLatch);
            }
        } finally {
            inWrite.set(false);
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A write that completes a frame matching {@code predicate} waits inside the transport until
     * {@link #releaseBlockedWrite()}.
     */
    void blockWritesMatching(Predicate<Frame> predicate) {
        blockLatch = new CountDownLatch(1);
        blockedEntered = new CountDownLatch(1);
        blockMatching = predicate;
    }

    void awaitBlockedWrite(Duration bound) throws InterruptedException {
        if (!blockedEntered.await(bound.toNanos(), TimeUnit.NANOSECONDS)) {
            throw new AssertionError("no write blocked within " + bound);
        }
    }

    void releaseBlockedWrite() {
        blockMatching = null;
        blockLatch.countDown();
    }

    /** Every later {@code queueWrite} throws {@code failure} without taking the buffer. */
    void failQueueWrites(RuntimeException failure) {
        queueWriteFailure = failure;
    }

    /** Closes every buffer a holding transport kept. */
    void releaseHeld() {
        List<LoanedBuffer> toClose;
        synchronized (held) {
            toClose = new ArrayList<>(held);
            held.clear();
        }
        toClose.forEach(LoanedBuffer::close);
    }

    boolean overlapObserved() {
        return overlapObserved;
    }

    /** Every frame written so far, in wire order. */
    List<Frame> seen() {
        return List.copyOf(all);
    }

    /**
     * Waits for the next frame, in wire order, that matches; frames before it stay in {@link #seen()}.
     */
    Frame awaitFrame(Predicate<Frame> match, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new AssertionError("no matching frame within " + timeout + "; seen " + describe());
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

    private String describe() {
        StringBuilder text = new StringBuilder();
        for (Frame frame : all) {
            text.append("[type=").append(frame.type()).append(" stream=").append(frame.streamId())
                    .append(" flags=").append(frame.flags()).append(" len=").append(frame.payload().length)
                    .append(']');
        }
        return text.toString();
    }

    /** Polls {@code condition} until it holds or {@code timeout} passes; a bound, not a delay. */
    static void awaitCondition(BooleanSupplier condition, Duration timeout, String what) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached within " + timeout + ": " + what);
            }
            Thread.yield();
        }
    }

    // ------------------------------------------------------------------ rest of the contract

    @Override
    public long streamId() {
        return 7L;
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

    @Override
    public void close() {
        closed = true;
    }

    boolean isClosed() {
        return closed;
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
