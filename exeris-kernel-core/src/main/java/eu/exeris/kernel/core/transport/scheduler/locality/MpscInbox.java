/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.scheduler.locality;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A multi-producer single-consumer inbox over a power-of-two ring, with an unbounded overflow
 * queue for the moment the ring is full.
 *
 * <p>A producer claims a place by compare-and-set on {@code producerIndex} and then stores into
 * the slot, so there is an interval in which the index has advanced and the slot is still null.
 * The consumer treats that interval as non-empty: {@link #isEmpty()} compares indices, not slots,
 * and {@link #poll()} waits for the slot to fill. Treating it as empty would let the owner park
 * after the producer had already read the park state, which loses the wakeup.
 *
 * <p>The consumer clears a slot before it advances {@code consumerIndex}, and a producer claims
 * only a place less than one ring length ahead of {@code consumerIndex}, so a claimed slot is
 * always null. The overflow queue is consulted only when the ring is empty, so ring order is
 * preserved and overflow tasks are served after it.
 */
final class MpscInbox {

    private static final VarHandle PRODUCER;
    private static final VarHandle CONSUMER;
    private static final VarHandle SLOT = MethodHandles.arrayElementVarHandle(Object[].class);

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            PRODUCER = lookup.findVarHandle(MpscInbox.class, "producerIndex", long.class);
            CONSUMER = lookup.findVarHandle(MpscInbox.class, "consumerIndex", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Object[] ring;
    private final int mask;
    private final ConcurrentLinkedQueue<Runnable> overflow = new ConcurrentLinkedQueue<>();
    @SuppressWarnings("unused")
    private long producerIndex;
    @SuppressWarnings("unused")
    private long consumerIndex;

    MpscInbox(int capacity) {
        int size = Integer.highestOneBit(Math.max(2, capacity - 1) << 1);
        this.ring = new Object[size];
        this.mask = size - 1;
    }

    /** Any thread. */
    void offer(Runnable task) {
        for (;;) {
            long p = (long) PRODUCER.getVolatile(this);
            if (p - (long) CONSUMER.getAcquire(this) >= ring.length) {
                overflow.offer(task);
                return;
            }
            if (PRODUCER.compareAndSet(this, p, p + 1)) {
                SLOT.setRelease(ring, (int) (p & mask), task);
                return;
            }
        }
    }

    /** Consumer only. */
    Runnable poll() {
        long c = (long) CONSUMER.getOpaque(this);
        int index = (int) (c & mask);
        Object task = SLOT.getAcquire(ring, index);
        if (task == null) {
            if (c == (long) PRODUCER.getVolatile(this)) {
                return overflow.poll();
            }
            do {
                Thread.onSpinWait();
                task = SLOT.getAcquire(ring, index);
            } while (task == null);
        }
        SLOT.setRelease(ring, index, null);
        CONSUMER.setRelease(this, c + 1);
        return (Runnable) task;
    }

    /** Consumer only. */
    boolean isEmpty() {
        return (long) CONSUMER.getOpaque(this) == (long) PRODUCER.getVolatile(this) && overflow.isEmpty();
    }

    /** Any thread. */
    int sizeEstimate() {
        long n = (long) PRODUCER.getVolatile(this) - (long) CONSUMER.getVolatile(this);
        return (int) Math.max(0, Math.min(n, Integer.MAX_VALUE)) + (overflow.isEmpty() ? 0 : 1);
    }
}
