/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.events;

import eu.exeris.kernel.spi.events.EventPayload;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Community's heap-backed {@link EventPayload}: a {@code byte[]} plus an
 * {@link AtomicInteger} reference count, exposed as a read-only Panama FFM
 * {@link MemorySegment} view. The Community bus, queue, outbox adapters and JDBC event-log
 * bindings all use this as their concrete payload.
 *
 * <p><b>Allocation:</b> the copying constructor path ({@link #CommunityHeapEventPayload(byte[])})
 * allocates a defensive {@code byte[]} copy via {@link Arrays#copyOf}; {@link #wrap} does not.
 * Either way, every instance allocates one {@link MemorySegment} view and one
 * {@link AtomicInteger} refCount holder.
 * <p><b>Thread confinement:</b> any thread — {@link #retain()} and {@link #close()} are
 * lock-free CAS loops on the refCount field, safe for concurrent callers.
 * <p><b>Ownership:</b> heap-backed, so reaching refCount 0 does not free anything explicitly —
 * the array becomes eligible for garbage collection once no reference to this payload remains.
 * {@link #segment()} throws once refCount has reached 0.
 */
final class CommunityHeapEventPayload implements EventPayload {

    private final byte[] bytes;
    private final MemorySegment segment;
    private final AtomicInteger refCount;

    /* default */ CommunityHeapEventPayload(byte[] bytes) {
        this(bytes, true);
    }

    private CommunityHeapEventPayload(byte[] bytes, boolean copy) {
        this.bytes = copy ? Arrays.copyOf(bytes, bytes.length) : bytes;
        this.segment = MemorySegment.ofArray(this.bytes).asReadOnly();
        this.refCount = new AtomicInteger(1);
    }

    /**
     * Wraps {@code bytes} without copying — takes ownership of the array itself.
     *
     * <p>The caller MUST NOT retain a reference to {@code bytes} or mutate it after this call:
     * doing so would mutate the payload's backing storage out from under whoever calls
     * {@link #segment()} next, since {@code asReadOnly()} prevents writes through the segment
     * but not through the original array reference.
     *
     * @param bytes the array to wrap (non-null)
     * @return a new payload at refCount 1, owning {@code bytes} directly
     */
    /* default */ static CommunityHeapEventPayload wrap(byte[] bytes) {
        return new CommunityHeapEventPayload(bytes, false);
    }

    /**
     * Returns a read-only view over the backing array.
     *
     * @return the read-only {@link MemorySegment}
     * @throws IllegalStateException if refCount has already reached 0
     */
    @Override
    public MemorySegment segment() {
        if (!isAlive()) {
            throw new IllegalStateException("payload already released");
        }
        return segment;
    }

    /**
     * Returns the backing array's length. Safe to call at any refCount, including 0.
     *
     * @return the payload length in bytes
     */
    @Override
    public int length() {
        return bytes.length;
    }

    /**
     * Increments refCount via a CAS loop.
     *
     * @throws IllegalStateException if refCount has already reached 0
     */
    @Override
    public void retain() {
        while (true) {
            int current = refCount.get();
            if (current <= 0) {
                throw new IllegalStateException("payload already released");
            }
            if (refCount.compareAndSet(current, current + 1)) {
                return;
            }
        }
    }

    /**
     * Decrements refCount via a CAS loop; a call once refCount has already reached 0 is a
     * silent no-op rather than a thrown exception.
     */
    @Override
    public void close() {
        while (true) {
            int current = refCount.get();
            if (current <= 0) {
                return;
            }
            if (refCount.compareAndSet(current, current - 1)) {
                return;
            }
        }
    }

    /**
     * Returns the current refCount, floored at 0.
     *
     * @return the current refCount, never negative
     */
    @Override
    public int refCount() {
        return Math.max(0, refCount.get());
    }

    /**
     * Returns whether refCount is still above 0.
     *
     * @return {@code true} if refCount &gt; 0
     */
    @Override
    public boolean isAlive() {
        return refCount.get() > 0;
    }

    /**
     * Copies {@code payload}'s bytes into a new array, or returns a new empty array for a
     * {@code null} payload or a zero-length one — this method never returns {@code null}.
     *
     * @param payload the payload to copy from, or {@code null}
     * @return a new {@code byte[]} holding the payload's bytes, or an empty array
     */
    /* default */ static byte[] copyBytes(EventPayload payload) {
        if (payload == null || payload.length() == 0) {
            return new byte[0];
        }
        return payload.segment().toArray(ValueLayout.JAVA_BYTE);
    }
}
