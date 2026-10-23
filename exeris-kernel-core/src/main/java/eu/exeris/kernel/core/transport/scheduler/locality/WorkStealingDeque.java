/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.scheduler.locality;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * A growable Chase-Lev work-stealing deque.
 *
 * <p>The owner pushes and pops at the bottom with no compare-and-set except when it takes the
 * last element; thieves take from the top with a compare-and-set. Indices only grow, so the
 * compare-and-set on {@code top} is free of ABA, and an index maps to a slot by masking.
 *
 * <p>Memory ordering: {@code top} and {@code bottom} are read and written with volatile semantics
 * where the algorithm needs a store-then-load order (the owner's pop and the thief's steal) and
 * with release/acquire elsewhere; the volatile accesses are in one synchronization order, which
 * supplies the sequentially consistent fence the algorithm requires. A slot is published by its
 * release store before {@code bottom} advances. A thief clears the slot it took with a
 * compare-and-set from the task it took, so it can never erase a task the owner has since pushed
 * into the same place.
 *
 * <p>When the array grows the old one is left intact: a thief that already holds it still reads
 * the element it is about to take from there.
 */
final class WorkStealingDeque {

    private static final VarHandle TOP;
    private static final VarHandle BOTTOM;
    private static final VarHandle ARRAY;
    private static final VarHandle SLOT = MethodHandles.arrayElementVarHandle(Object[].class);

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            TOP = lookup.findVarHandle(WorkStealingDeque.class, "top", long.class);
            BOTTOM = lookup.findVarHandle(WorkStealingDeque.class, "bottom", long.class);
            ARRAY = lookup.findVarHandle(WorkStealingDeque.class, "array", Object[].class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings("unused")
    private long top;
    @SuppressWarnings("unused")
    private long bottom;
    @SuppressWarnings("unused")
    private Object[] array;

    WorkStealingDeque(int initialCapacity) {
        int capacity = Integer.highestOneBit(Math.max(4, initialCapacity - 1) << 1);
        ARRAY.setRelease(this, new Object[capacity]);
    }

    /** Owner only. */
    void push(Runnable task) {
        long b = (long) BOTTOM.getOpaque(this);
        long t = (long) TOP.getAcquire(this);
        Object[] a = (Object[]) ARRAY.getOpaque(this);
        if (b - t >= a.length - 1) {
            a = grow(a, t, b);
        }
        SLOT.setRelease(a, (int) (b & (a.length - 1)), task);
        BOTTOM.setRelease(this, b + 1);
    }

    /** Owner only. Takes the most recently pushed task. */
    Runnable pop() {
        long b = (long) BOTTOM.getOpaque(this) - 1;
        Object[] a = (Object[]) ARRAY.getOpaque(this);
        BOTTOM.setVolatile(this, b);
        long t = (long) TOP.getVolatile(this);
        if (t > b) {
            BOTTOM.setRelease(this, b + 1);
            return null;
        }
        int index = (int) (b & (a.length - 1));
        Object task = SLOT.getAcquire(a, index);
        if (t == b) {
            boolean won = TOP.compareAndSet(this, t, t + 1);
            BOTTOM.setRelease(this, b + 1);
            if (!won) {
                return null;
            }
        }
        SLOT.setRelease(a, index, null);
        return (Runnable) task;
    }

    /** Any thread. Takes the oldest task; {@code null} when empty or when another taker won. */
    Runnable steal() {
        long t = (long) TOP.getVolatile(this);
        long b = (long) BOTTOM.getVolatile(this);
        if (t >= b) {
            return null;
        }
        Object[] a = (Object[]) ARRAY.getAcquire(this);
        int index = (int) (t & (a.length - 1));
        Object task = SLOT.getAcquire(a, index);
        if (!TOP.compareAndSet(this, t, t + 1)) {
            return null;
        }
        SLOT.compareAndSet(a, index, task, null);
        return (Runnable) task;
    }

    /** Owner only. */
    boolean isEmpty() {
        return (long) BOTTOM.getOpaque(this) - (long) TOP.getVolatile(this) <= 0;
    }

    /** Any thread; an estimate, since the two indices are read one after the other. */
    int size() {
        long b = (long) BOTTOM.getVolatile(this);
        long t = (long) TOP.getVolatile(this);
        return (int) Math.max(0, Math.min(b - t, Integer.MAX_VALUE));
    }

    private Object[] grow(Object[] old, long t, long b) {
        Object[] grown = new Object[old.length << 1];
        for (long i = t; i < b; i++) {
            grown[(int) (i & (grown.length - 1))] = old[(int) (i & (old.length - 1))];
        }
        ARRAY.setRelease(this, grown);
        return grown;
    }
}
