/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.http;

import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK fixture: an outbound request body that counts every {@link #close()} made on it, so a
 * contract case can assert that {@link eu.exeris.kernel.spi.http.HttpClientEngine#send} neither
 * closed nor retained the body it was handed; {@link #assertStillOwnedByCaller} is that assertion.
 *
 * <p>The body is backed by an on-heap segment, which {@link LoanedBuffer#segment()} permits; it
 * takes no memory from an allocator, so the contract that uses it needs none bound. Reference
 * counting follows {@link LoanedBuffer}: {@link #slice} and {@link #view} take a reference on this
 * body and release it when closed, and a {@link #peek} view takes none. Once the count reaches zero,
 * {@link #segment()}, {@link #slice}, {@link #view}, {@link #peek}, {@link #retain()},
 * {@link #setSize} and {@link #addCloseAction} throw {@link IllegalStateException}, while
 * {@link #size()}, {@link #capacity()}, {@link #refCount()} and {@link #isAlive()} still answer and
 * {@link #close()} is a no-op beyond being counted. {@link #closeCalls()} counts only the calls made
 * on this body itself, not the closes of a slice or view taken from it.
 *
 * <p><b>Allocation:</b> allocates (one heap array per body, one handle per slice, view or peek)
 * <p><b>Thread confinement:</b> virtual-thread-safe — the counts are atomic, so an engine may read
 * the body on a thread other than the caller's
 * <p><b>Ownership:</b> the contract case that creates the body is its caller, and closes it after
 * its assertions
 *
 * @since 0.12
 */
final class CountingRequestBody implements LoanedBuffer {

    private final MemorySegment segment;
    private final CountingRequestBody parent;
    private final AtomicInteger references = new AtomicInteger(1);
    private final AtomicInteger closeCalls = new AtomicInteger();
    private final Deque<Runnable> closeActions = new ArrayDeque<>();
    private final AtomicLong size;

    private CountingRequestBody(MemorySegment segment, long size, CountingRequestBody parent) {
        this.segment = segment;
        this.size = new AtomicLong(size);
        this.parent = parent;
    }

    /**
     * Creates a live body holding a copy of {@code payload}, with a reference count of one.
     *
     * @param payload the body bytes; must not be {@code null}
     * @return a live body whose {@link #size()} is {@code payload.length}
     */
    static CountingRequestBody of(byte[] payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        byte[] copy = payload.clone();
        return new CountingRequestBody(MemorySegment.ofArray(copy), copy.length, null);
    }

    /**
     * Returns how many times {@link #close()} has been called on this body.
     *
     * @return the number of close calls, including calls on an already-released body
     */
    int closeCalls() {
        return closeCalls.get();
    }

    /**
     * Returns a copy of the body's valid bytes.
     *
     * @return the first {@link #size()} bytes of the body
     * @throws IllegalStateException if the body has been released
     */
    byte[] bytes() {
        return segment().asSlice(0, size.get()).toArray(ValueLayout.JAVA_BYTE);
    }

    /**
     * Asserts that this body is still wholly the caller's after a {@code send} returned or threw:
     * never closed, alive, holding only the caller's reference, and holding {@code expected}.
     *
     * @param expected the bytes the body was created with
     * @param after    what the send did, for the failure message — {@code "returns"} or
     *                 {@code "throws"}
     */
    void assertStillOwnedByCaller(byte[] expected, String after) {
        // The caller owns the body across send, whether send returns or throws: an engine that
        // closed it would free memory the caller still releases, and one that retained it would
        // keep the memory alive after the caller's release.
        assertThat(closeCalls())
                .as("send must not close request.body(); the caller releases it")
                .isZero();
        assertThat(isAlive())
                .as("request.body() must still be alive after send " + after)
                .isTrue();
        assertThat(refCount())
                .as("send must not retain request.body() beyond its own return")
                .isEqualTo(1);
        assertThat(bytes())
                .as("the body the caller still owns must be intact")
                .isEqualTo(expected);
    }

    /**
     * Returns a copy of the valid bytes of a buffer, or an empty array for a {@code null} one.
     *
     * @param buffer a live buffer, or {@code null}
     * @return the first {@code buffer.size()} bytes, or an empty array
     */
    static byte[] bytesOf(LoanedBuffer buffer) {
        if (buffer == null) {
            return new byte[0];
        }
        return buffer.segment().asSlice(0, buffer.size()).toArray(ValueLayout.JAVA_BYTE);
    }

    @Override
    public MemorySegment segment() {
        requireAlive();
        return segment;
    }

    @Override
    public long size() {
        return size.get();
    }

    @Override
    public long capacity() {
        return segment.byteSize();
    }

    @Override
    public LoanedBuffer slice(long offset, long length) {
        requireAlive();
        MemorySegment fragment = segment.asSlice(offset, length);
        retainReference();
        return new CountingRequestBody(fragment, length, this);
    }

    @Override
    public LoanedBuffer view() {
        requireAlive();
        long extent = size.get();
        MemorySegment fragment = segment.asSlice(0, extent).asReadOnly();
        retainReference();
        return new CountingRequestBody(fragment, extent, this);
    }

    @Override
    public LoanedBuffer peek(long offset, long length) {
        requireAlive();
        return new PeekView(this, segment.asSlice(offset, length));
    }

    @Override
    public void retain() {
        retainReference();
    }

    @Override
    public void close() {
        closeCalls.incrementAndGet();
        releaseReference();
    }

    @Override
    public int refCount() {
        return references.get();
    }

    @Override
    public void setSize(long newSize) {
        requireAlive();
        if (newSize < 0 || newSize > capacity()) {
            throw new IllegalArgumentException("size " + newSize + " outside [0, " + capacity() + "]");
        }
        size.set(newSize);
    }

    @Override
    public boolean isAlive() {
        return references.get() > 0;
    }

    @Override
    public void addCloseAction(Runnable action) {
        Objects.requireNonNull(action, "action must not be null");
        requireAlive();
        synchronized (closeActions) {
            closeActions.push(action);
        }
    }

    private void requireAlive() {
        if (!isAlive()) {
            throw new IllegalStateException("request body has been released");
        }
    }

    private void retainReference() {
        references.updateAndGet(count -> {
            if (count == 0) {
                throw new IllegalStateException("request body has been released");
            }
            return count + 1;
        });
    }

    private void releaseReference() {
        int before = references.getAndUpdate(count -> count == 0 ? 0 : count - 1);
        if (before != 1) {
            return;
        }
        synchronized (closeActions) {
            while (!closeActions.isEmpty()) {
                closeActions.pop().run();
            }
        }
        if (parent != null) {
            parent.releaseReference();
        }
    }

    /**
     * A non-owning view: it takes no reference, and its {@code close} and {@code retain} are no-ops.
     */
    private static final class PeekView implements LoanedBuffer {

        private final CountingRequestBody owner;
        private final MemorySegment segment;

        private PeekView(CountingRequestBody owner, MemorySegment segment) {
            this.owner = owner;
            this.segment = segment;
        }

        @Override
        public MemorySegment segment() {
            owner.requireAlive();
            return segment;
        }

        @Override
        public long size() {
            return segment.byteSize();
        }

        @Override
        public long capacity() {
            return segment.byteSize();
        }

        @Override
        public LoanedBuffer slice(long offset, long length) {
            return new PeekView(owner, segment().asSlice(offset, length));
        }

        @Override
        public LoanedBuffer view() {
            return new PeekView(owner, segment());
        }

        @Override
        public LoanedBuffer peek(long offset, long length) {
            return new PeekView(owner, segment().asSlice(offset, length));
        }

        @Override
        public void retain() {
            // A peek view owns no reference, so there is nothing to retain.
        }

        @Override
        public void close() {
            // A peek view owns no reference, so there is nothing to release.
        }

        @Override
        public int refCount() {
            return owner.refCount();
        }

        @Override
        public void setSize(long newSize) {
            throw new UnsupportedOperationException("a peek view has a fixed extent");
        }

        @Override
        public boolean isAlive() {
            return owner.isAlive();
        }

        @Override
        public void addCloseAction(Runnable action) {
            throw new IllegalStateException("a peek view owns no reference and takes no close action");
        }
    }
}
