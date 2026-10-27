/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.sse;

import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.http.StreamEvent;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The credit window bounds the bytes queued, not the size of one event: an event larger than the window
 * is admitted once nothing else is outstanding, and an event that does not fit the remaining window parks
 * while anything is outstanding. The transport here is a fake that records each queued buffer and holds
 * it until the test releases it, so the test owns the moment credit returns.
 */
@DisplayName("HttpStreamEngine credit window admits an oversized event when nothing is outstanding")
class HttpStreamEngineCreditWindowTest {

    private static final int WINDOW = 1_024;
    private static final int FRAMING_BYTES = "data: ".length() + 2;
    private static final Duration BOUND = Duration.ofSeconds(5);
    private static final Arena ARENA = Arena.ofShared();
    private static final MemoryAllocator ALLOCATOR = new ReleasingAllocator();

    @AfterAll
    static void closeArena() {
        ARENA.close();
    }

    @ParameterizedTest(name = "an event of {0} encoded bytes against a 1024-byte window is admitted")
    @ValueSource(ints = {WINDOW + 1, 4 * WINDOW})
    @DisplayName("nothing outstanding: an event larger than the window is queued once, without parking")
    void anOversizedEventIsAdmittedWhenNothingIsOutstanding(int encodedLength) throws InterruptedException {
        HoldingStream stream = new HoldingStream();
        HttpStreamEngine engine = open(stream);
        StreamEvent event = eventOfEncodedLength(encodedLength);
        assertThat(SseEventEncoder.encodedLength(event)).isEqualTo(encodedLength);

        Thread emitter = Thread.ofPlatform().daemon().start(() -> engine.emit(event));
        assertThat(emitter.join(BOUND)).as("emit of an oversized event returns").isTrue();

        assertThat(engine.outstandingBytes()).isEqualTo(encodedLength);
        assertThat(stream.queueWrites()).isEqualTo(1);
        stream.releaseAll();
        engine.close();
    }

    @Test
    @DisplayName("a small event parks behind an admitted oversized one and resumes when it is released")
    void aSmallEventParksWhileTheOversizedOneIsOutstanding() throws InterruptedException {
        HoldingStream stream = new HoldingStream();
        HttpStreamEngine engine = open(stream);
        Thread first = Thread.ofPlatform().daemon().start(() -> engine.emit(eventOfEncodedLength(WINDOW + 1)));
        assertThat(first.join(BOUND)).isTrue();
        assertThat(stream.queueWrites()).isEqualTo(1);

        AtomicReference<Thread> second = new AtomicReference<>();
        second.set(Thread.ofPlatform().daemon().start(() -> engine.emit(eventOfEncodedLength(100))));
        awaitParkedInAwaitCredit(second);
        assertThat(stream.queueWrites()).as("the second event is not queued while credit is held").isEqualTo(1);

        stream.releaseAll();
        assertThat(second.get().join(BOUND)).as("the parked emit resumes once credit returns").isTrue();
        assertThat(stream.queueWrites()).as("both events are queued").isEqualTo(2);
        stream.releaseAll();
        engine.close();
    }

    @Test
    @DisplayName("a second oversized event waits for the first to be released, then is admitted")
    void aSecondOversizedEventWaitsForTheFirst() throws InterruptedException {
        HoldingStream stream = new HoldingStream();
        HttpStreamEngine engine = open(stream);
        Thread first = Thread.ofPlatform().daemon().start(() -> engine.emit(eventOfEncodedLength(WINDOW + 1)));
        assertThat(first.join(BOUND)).isTrue();

        AtomicReference<Thread> second = new AtomicReference<>();
        second.set(Thread.ofPlatform().daemon().start(() -> engine.emit(eventOfEncodedLength(WINDOW + 1))));
        awaitParkedInAwaitCredit(second);
        assertThat(stream.queueWrites()).as("one oversized event at a time").isEqualTo(1);

        stream.releaseAll();
        assertThat(second.get().join(BOUND)).isTrue();
        assertThat(stream.queueWrites()).as("both oversized events are queued").isEqualTo(2);
        assertThat(engine.outstandingBytes()).isEqualTo(WINDOW + 1);
        stream.releaseAll();
        engine.close();
    }

    private static HttpStreamEngine open(TransportStream stream) {
        HttpRequest request = HttpRequest.noBody(HttpMethod.GET, "/events", HttpVersion.HTTP_1_1, List.of());
        return HttpStreamEngine.open(request, stream, ALLOCATOR, 0L, WINDOW);
    }

    private static StreamEvent eventOfEncodedLength(int encodedLength) {
        return StreamEvent.of("x".repeat(encodedLength - FRAMING_BYTES));
    }

    // Waits, bounded and without sleeping, until the thread is parked inside the engine's credit wait: the
    // observation the stream TCK makes of an emitter, taken from its stack.
    private static void awaitParkedInAwaitCredit(AtomicReference<Thread> thread) {
        awaitCondition(() -> {
            Thread emitter = thread.get();
            if (emitter == null) {
                return false;
            }
            Thread.State state = emitter.getState();
            if (state != Thread.State.WAITING && state != Thread.State.TIMED_WAITING) {
                return false;
            }
            for (StackTraceElement frame : emitter.getStackTrace()) {
                if ("awaitCredit".equals(frame.getMethodName())
                        && frame.getClassName().endsWith("HttpStreamEngine")) {
                    return true;
                }
            }
            return false;
        });
    }

    private static void awaitCondition(BooleanSupplier condition) {
        long deadline = System.nanoTime() + BOUND.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the emitter did not park in awaitCredit within " + BOUND);
            }
            Thread.yield();
        }
    }

    /** Records each queued buffer and keeps it open until {@link #releaseAll()}. */
    private static final class HoldingStream implements TransportStream {

        private final List<LoanedBuffer> queued = new CopyOnWriteArrayList<>();
        private final AtomicInteger queueWrites = new AtomicInteger();

        int queueWrites() {
            return queueWrites.get();
        }

        void releaseAll() {
            List<LoanedBuffer> held = new ArrayList<>(queued);
            queued.removeAll(held);
            held.forEach(LoanedBuffer::close);
        }

        @Override
        public int read(MemorySegment target, int maxBytes) {
            return -1;
        }

        @Override
        public void write(MemorySegment source, int length) {
            // The response head: not under test.
        }

        @Override
        public void queueWrite(LoanedBuffer buffer, int length) {
            queueWrites.incrementAndGet();
            queued.add(buffer);
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
            throw new UnsupportedOperationException("not used by this test");
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

    /** Hands out buffers that run their close actions exactly once, as the engine's credit return needs. */
    private static final class ReleasingAllocator implements MemoryAllocator {

        @Override
        public LoanedBuffer allocate(AllocationHint hint) {
            return new ReleasingBuffer(ARENA.allocate(hint.sizeBytes(), 8L));
        }

        @Override
        public LoanedBuffer allocateNetwork(int estimatedBytes) {
            return new ReleasingBuffer(ARENA.allocate(estimatedBytes, 8L));
        }

        @Override
        public LoanedBuffer allocateCarrierSlab(int carrierIndex) {
            return allocate(AllocationHint.SMALL);
        }

        @Override
        public LoanedBuffer allocateInfrastructure(long sizeBytes) {
            return new ReleasingBuffer(ARENA.allocate(sizeBytes, 8L));
        }

        @Override
        public MemoryStats stats() {
            return MemoryStats.zero();
        }

        @Override
        public void close() {
            // arena lifecycle managed by the test
        }
    }

    private static final class ReleasingBuffer implements LoanedBuffer {

        private final MemorySegment segment;
        private final List<Runnable> closeActions = new CopyOnWriteArrayList<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private long size;

        ReleasingBuffer(MemorySegment segment) {
            this.segment = segment;
            this.size = segment.byteSize();
        }

        @Override
        public MemorySegment segment() {
            return segment;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public long capacity() {
            return segment.byteSize();
        }

        @Override
        public void setSize(long newSize) {
            size = newSize;
        }

        @Override
        public LoanedBuffer slice(long offset, long length) {
            return new ReleasingBuffer(segment.asSlice(offset, length));
        }

        @Override
        public LoanedBuffer view() {
            return this;
        }

        @Override
        public LoanedBuffer peek(long offset, long length) {
            return new ReleasingBuffer(segment.asSlice(offset, length));
        }

        @Override
        public void retain() {
            // single owner in this test
        }

        @Override
        public int refCount() {
            return closed.get() ? 0 : 1;
        }

        @Override
        public boolean isAlive() {
            return !closed.get();
        }

        @Override
        public void addCloseAction(Runnable action) {
            closeActions.add(action);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                closeActions.forEach(Runnable::run);
            }
        }
    }
}
